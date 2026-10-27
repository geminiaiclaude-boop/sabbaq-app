-- In-app support conversation: one thread per driver with the administration. Clients reach the
-- tables only through SECURITY DEFINER RPCs; every message is plain text, bounded and
-- idempotent on a client-generated id so a retried send never duplicates.
create table if not exists public.support_threads (
    driver_id              uuid primary key references public.drivers (id) on delete cascade,
    status                 text not null default 'open' check (status in ('open', 'closed')),
    driver_unread          integer not null default 0 check (driver_unread >= 0),
    admin_unread           integer not null default 0 check (admin_unread >= 0),
    last_message_at        timestamptz not null default now(),
    last_app_version       text check (last_app_version is null or last_app_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    last_correlation_id    text check (last_correlation_id is null or last_correlation_id ~ '^[a-f0-9]{16,64}$'),
    created_at             timestamptz not null default now()
);
create index if not exists support_threads_inbox on public.support_threads (status, last_message_at desc);
alter table public.support_threads enable row level security;
revoke all on public.support_threads from public, anon, authenticated;

create table if not exists public.support_messages (
    id          uuid primary key default gen_random_uuid(),
    driver_id   uuid not null references public.support_threads (driver_id) on delete cascade,
    client_id   uuid not null,
    sender      text not null check (sender in ('driver', 'admin')),
    body        text not null check (char_length(btrim(body)) between 1 and 1000),
    admin_id    uuid references auth.users (id) on delete set null,
    created_at  timestamptz not null default now(),
    unique (driver_id, client_id)
);
create index if not exists support_messages_thread_time on public.support_messages (driver_id, created_at desc);
alter table public.support_messages enable row level security;
revoke all on public.support_messages from public, anon, authenticated;

-- Admin replies reach the driver through the notification centre as well.
alter table public.driver_notifications drop constraint if exists driver_notifications_action_check;
alter table public.driver_notifications add constraint driver_notifications_action_check
    check (action is null or action in ('open_account', 'open_setup', 'open_filters', 'update_app', 'open_support'));

create or replace function public.support_thread_json(p_driver uuid, p_limit integer) returns jsonb
language sql stable security definer set search_path = pg_catalog, public as $$
    select jsonb_build_object(
        'status', coalesce(t.status, 'open'),
        'driver_unread', coalesce(t.driver_unread, 0),
        'admin_unread', coalesce(t.admin_unread, 0),
        'messages', coalesce((
            select jsonb_agg(jsonb_build_object('id', m.id, 'client_id', m.client_id, 'sender', m.sender,
                                                'body', m.body, 'created_at', m.created_at) order by m.created_at, m.id)
            from (select * from public.support_messages where driver_id = p_driver
                  order by created_at desc, id desc limit least(greatest(coalesce(p_limit, 100), 1), 200)) m), '[]'::jsonb))
    from (select 1) one
    left join public.support_threads t on t.driver_id = p_driver;
$$;
revoke all on function public.support_thread_json(uuid, integer) from public, anon, authenticated;

-- The signed-in driver's conversation (oldest first, last p_limit messages). Reading it marks the
-- administration's replies as seen.
create or replace function public.support_my_thread(p_limit integer default 100) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_uid uuid := auth.uid(); result jsonb;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if not exists (select 1 from public.drivers where id = v_uid) then raise exception 'unregistered' using errcode = '42501'; end if;
    result := public.support_thread_json(v_uid, p_limit);
    update public.support_threads set driver_unread = 0 where driver_id = v_uid and driver_unread <> 0;
    return result;
end $$;
revoke all on function public.support_my_thread(integer) from public, anon;
grant execute on function public.support_my_thread(integer) to authenticated;

-- Unread replies only, for the badge; does not mark anything read.
create or replace function public.support_unread() returns integer
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare v_count integer;
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    select driver_unread into v_count from public.support_threads where driver_id = auth.uid();
    return coalesce(v_count, 0);
end $$;
revoke all on function public.support_unread() from public, anon;
grant execute on function public.support_unread() to authenticated;

-- A driver message. Idempotent on p_client_id; at most 20 messages an hour; reopens a closed
-- thread. The app version and support id help the administration diagnose without asking.
create or replace function public.support_send(p_client_id uuid, p_body text, p_app_version text default null, p_correlation_id text default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_uid uuid := auth.uid(); v_body text := btrim(coalesce(p_body, '')); v_id uuid; v_status text;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if not exists (select 1 from public.drivers where id = v_uid) then raise exception 'unregistered' using errcode = '42501'; end if;
    if p_client_id is null or char_length(v_body) not between 1 and 1000 then raise exception 'invalid_message' using errcode = '22023'; end if;
    if p_app_version is not null and p_app_version !~ '^[A-Za-z0-9._-]{1,32}$' then raise exception 'invalid_message' using errcode = '22023'; end if;
    if p_correlation_id is not null and p_correlation_id !~ '^[a-f0-9]{16,64}$' then raise exception 'invalid_message' using errcode = '22023'; end if;

    -- The thread row lock serializes this driver's sends, so concurrent requests cannot all pass
    -- the hourly bound before any of them inserts.
    insert into public.support_threads (driver_id) values (v_uid) on conflict (driver_id) do nothing;
    select status into v_status from public.support_threads where driver_id = v_uid for update;
    select id into v_id from public.support_messages where driver_id = v_uid and client_id = p_client_id;
    if v_id is not null then return jsonb_build_object('id', v_id, 'duplicate', true); end if;
    if (select count(*) from public.support_messages
        where driver_id = v_uid and sender = 'driver' and created_at > now() - interval '1 hour') >= 20 then
        raise exception 'rate_limited' using errcode = 'PT429';
    end if;

    insert into public.support_messages (driver_id, client_id, sender, body)
    values (v_uid, p_client_id, 'driver', v_body)
    on conflict (driver_id, client_id) do nothing
    returning id into v_id;
    if v_id is null then
        select id into v_id from public.support_messages where driver_id = v_uid and client_id = p_client_id;
        return jsonb_build_object('id', v_id, 'duplicate', true);
    end if;
    update public.support_threads
    set status = 'open', admin_unread = admin_unread + 1, last_message_at = now(),
        last_app_version = coalesce(p_app_version, last_app_version),
        last_correlation_id = coalesce(p_correlation_id, last_correlation_id)
    where driver_id = v_uid;
    return jsonb_build_object('id', v_id, 'duplicate', false, 'reopened', v_status = 'closed');
end $$;
revoke all on function public.support_send(uuid, text, text, text) from public, anon;
grant execute on function public.support_send(uuid, text, text, text) to authenticated;

create or replace function public.admin_support_threads(p_status text default 'open', p_limit integer default 100) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare result jsonb;
begin
    perform public.require_admin();
    if p_status is not null and p_status not in ('open', 'closed') then raise exception 'invalid_status' using errcode = '22023'; end if;
    select coalesce(jsonb_agg(jsonb_build_object(
            'driver_id', t.driver_id, 'driver_name', d.full_name, 'driver_phone', d.phone, 'status', t.status,
            'admin_unread', t.admin_unread, 'last_message_at', t.last_message_at,
            'last_app_version', t.last_app_version, 'last_correlation_id', t.last_correlation_id,
            'last_message', (select jsonb_build_object('sender', m.sender, 'body', left(m.body, 140))
                             from public.support_messages m where m.driver_id = t.driver_id
                             order by m.created_at desc, m.id desc limit 1))
            order by t.admin_unread > 0 desc, t.last_message_at desc), '[]'::jsonb)
    into result
    from (select * from public.support_threads
          where p_status is null or status = p_status
          order by admin_unread > 0 desc, last_message_at desc
          limit least(greatest(coalesce(p_limit, 100), 1), 200)) t
    join public.drivers d on d.id = t.driver_id;
    return result;
end $$;
revoke all on function public.admin_support_threads(text, integer) from public, anon;
grant execute on function public.admin_support_threads(text, integer) to authenticated;

-- One driver's conversation for the administration; opening it marks the driver's messages read.
create or replace function public.admin_support_thread(p_driver uuid, p_limit integer default 200) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare result jsonb;
begin
    perform public.require_admin();
    if p_driver is null or not exists (select 1 from public.drivers where id = p_driver) then
        raise exception 'unknown_driver' using errcode = '22023';
    end if;
    result := public.support_thread_json(p_driver, p_limit) || (
        select jsonb_build_object('driver_name', d.full_name, 'driver_phone', d.phone,
                                  'last_app_version', t.last_app_version, 'last_correlation_id', t.last_correlation_id)
        from public.drivers d left join public.support_threads t on t.driver_id = d.id where d.id = p_driver);
    update public.support_threads set admin_unread = 0 where driver_id = p_driver and admin_unread <> 0;
    return result;
end $$;
revoke all on function public.admin_support_thread(uuid, integer) from public, anon;
grant execute on function public.admin_support_thread(uuid, integer) to authenticated;

-- An administration reply: idempotent on p_client_id, opens the thread if the driver never wrote,
-- and drops a notification so the driver sees it in the bell and as a phone alert.
create or replace function public.admin_support_reply(p_driver uuid, p_client_id uuid, p_body text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_body text := btrim(coalesce(p_body, '')); v_id uuid;
begin
    perform public.require_admin();
    if p_driver is null or not exists (select 1 from public.drivers where id = p_driver) then
        raise exception 'unknown_driver' using errcode = '22023';
    end if;
    if p_client_id is null or char_length(v_body) not between 1 and 1000 then raise exception 'invalid_message' using errcode = '22023'; end if;
    insert into public.support_threads (driver_id) values (p_driver) on conflict (driver_id) do nothing;
    insert into public.support_messages (driver_id, client_id, sender, body, admin_id)
    values (p_driver, p_client_id, 'admin', v_body, auth.uid())
    on conflict (driver_id, client_id) do nothing
    returning id into v_id;
    if v_id is null then
        select id into v_id from public.support_messages where driver_id = p_driver and client_id = p_client_id;
        return jsonb_build_object('id', v_id, 'duplicate', true);
    end if;
    update public.support_threads
    set status = 'open', driver_unread = driver_unread + 1, last_message_at = now()
    where driver_id = p_driver;
    insert into public.driver_notifications (driver_id, title, body, kind, action, created_by, expires_at)
    values (p_driver, 'رد جديد من دعم سبّاق', left(v_body, 160) || case when char_length(v_body) > 160 then '…' else '' end,
            'info', 'open_support', auth.uid(), now() + interval '30 days');
    return jsonb_build_object('id', v_id, 'duplicate', false);
end $$;
revoke all on function public.admin_support_reply(uuid, uuid, text) from public, anon;
grant execute on function public.admin_support_reply(uuid, uuid, text) to authenticated;

create or replace function public.admin_support_set_status(p_driver uuid, p_status text) returns boolean
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    if p_status not in ('open', 'closed') then raise exception 'invalid_status' using errcode = '22023'; end if;
    update public.support_threads set status = p_status where driver_id = p_driver;
    return found;
end $$;
revoke all on function public.admin_support_set_status(uuid, text) from public, anon;
grant execute on function public.admin_support_set_status(uuid, text) to authenticated;

-- Closed conversations idle for 180 days are removed with their messages.
create or replace function public.purge_support_threads(p_keep interval default interval '180 days') returns integer
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_deleted integer;
begin
    delete from public.support_threads where status = 'closed' and last_message_at < now() - p_keep;
    get diagnostics v_deleted = row_count;
    return v_deleted;
end $$;
revoke all on function public.purge_support_threads(interval) from public, anon, authenticated;

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'purge_support_threads';
        perform cron.schedule('purge_support_threads', '37 3 * * *', 'select public.purge_support_threads()');
    end if;
end $$;

comment on table public.support_threads is 'One support conversation per driver. Closed threads idle 180 days are removed by purge_support_threads() (pg_cron job purge_support_threads when available).';
