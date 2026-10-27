-- In-app notification centre: messages the administration sends to one driver or to everyone,
-- read through SECURITY DEFINER RPCs only. Clients never touch the tables directly.
create table if not exists public.driver_notifications (
    id          uuid primary key default gen_random_uuid(),
    driver_id   uuid references public.drivers (id) on delete cascade,
    title       text not null check (char_length(btrim(title)) between 2 and 80),
    body        text not null check (char_length(btrim(body)) between 2 and 500),
    kind        text not null default 'info' check (kind in ('info', 'warning', 'success', 'account', 'update')),
    action      text check (action is null or action in ('open_account', 'open_setup', 'open_filters', 'update_app')),
    created_by  uuid references auth.users (id) on delete set null,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz check (expires_at is null or expires_at > created_at)
);
create index if not exists driver_notifications_driver_time on public.driver_notifications (driver_id, created_at desc);
create index if not exists driver_notifications_broadcast_time on public.driver_notifications (created_at desc) where driver_id is null;
alter table public.driver_notifications enable row level security;
revoke all on public.driver_notifications from public, anon, authenticated;

create table if not exists public.driver_notification_reads (
    driver_id       uuid not null references public.drivers (id) on delete cascade,
    notification_id uuid not null references public.driver_notifications (id) on delete cascade,
    read_at         timestamptz not null default now(),
    primary key (driver_id, notification_id)
);
alter table public.driver_notification_reads enable row level security;
revoke all on public.driver_notification_reads from public, anon, authenticated;

-- Notifications visible to the signed-in driver: their own plus broadcasts, newest first,
-- excluding expired ones and broadcasts sent before the account existed.
create or replace function public.my_notifications(p_limit integer default 50) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare v_uid uuid := auth.uid(); v_since timestamptz; result jsonb;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    select created_at into v_since from public.drivers where id = v_uid;
    if v_since is null then return '[]'::jsonb; end if;
    select coalesce(jsonb_agg(jsonb_build_object(
            'id', n.id, 'title', n.title, 'body', n.body, 'kind', n.kind, 'action', n.action,
            'created_at', n.created_at, 'read', r.notification_id is not null) order by n.created_at desc), '[]'::jsonb)
    into result
    from (select * from public.driver_notifications
          where (driver_id = v_uid or (driver_id is null and created_at >= v_since - interval '1 day'))
            and (expires_at is null or expires_at > now())
            and created_at > now() - interval '90 days'
          order by created_at desc
          limit least(greatest(coalesce(p_limit, 50), 1), 100)) n
    left join public.driver_notification_reads r on r.notification_id = n.id and r.driver_id = v_uid;
    return result;
end $$;
revoke all on function public.my_notifications(integer) from public, anon;
grant execute on function public.my_notifications(integer) to authenticated;

-- Marks notifications read for the caller; ids the caller cannot see are ignored.
create or replace function public.mark_notifications_read(p_ids uuid[]) returns integer
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_uid uuid := auth.uid(); v_count integer;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if p_ids is null or cardinality(p_ids) = 0 then return 0; end if;
    if cardinality(p_ids) > 200 then raise exception 'too_many_ids' using errcode = '22023'; end if;
    if not exists (select 1 from public.drivers where id = v_uid) then return 0; end if;
    insert into public.driver_notification_reads (driver_id, notification_id)
    select v_uid, n.id from public.driver_notifications n
    where n.id = any (p_ids) and (n.driver_id = v_uid or n.driver_id is null)
    on conflict do nothing;
    get diagnostics v_count = row_count;
    return v_count;
end $$;
revoke all on function public.mark_notifications_read(uuid[]) from public, anon;
grant execute on function public.mark_notifications_read(uuid[]) to authenticated;

create or replace function public.admin_send_notification(
    p_driver uuid, p_title text, p_body text, p_kind text default 'info',
    p_action text default null, p_expires_at timestamptz default null
) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_id uuid;
begin
    perform public.require_admin();
    if p_driver is not null and not exists (select 1 from public.drivers where id = p_driver) then
        raise exception 'unknown_driver' using errcode = '22023';
    end if;
    if p_expires_at is not null and p_expires_at <= now() then
        raise exception 'expiry_in_past' using errcode = '22023';
    end if;
    insert into public.driver_notifications (driver_id, title, body, kind, action, created_by, expires_at)
    values (p_driver, btrim(p_title), btrim(p_body), coalesce(p_kind, 'info'), nullif(p_action, ''), auth.uid(), p_expires_at)
    returning id into v_id;
    return jsonb_build_object('id', v_id);
exception when check_violation then
    raise exception 'invalid_notification' using errcode = '22023';
end $$;
revoke all on function public.admin_send_notification(uuid, text, text, text, text, timestamptz) from public, anon;
grant execute on function public.admin_send_notification(uuid, text, text, text, text, timestamptz) to authenticated;

create or replace function public.admin_list_notifications(p_limit integer default 50) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare result jsonb;
begin
    perform public.require_admin();
    select coalesce(jsonb_agg(jsonb_build_object(
            'id', n.id, 'driver_id', n.driver_id, 'driver_name', d.full_name, 'title', n.title, 'body', n.body,
            'kind', n.kind, 'action', n.action, 'created_at', n.created_at, 'expires_at', n.expires_at,
            'read_count', (select count(*) from public.driver_notification_reads r where r.notification_id = n.id))
            order by n.created_at desc), '[]'::jsonb)
    into result
    from (select * from public.driver_notifications order by created_at desc
          limit least(greatest(coalesce(p_limit, 50), 1), 200)) n
    left join public.drivers d on d.id = n.driver_id;
    return result;
end $$;
revoke all on function public.admin_list_notifications(integer) from public, anon;
grant execute on function public.admin_list_notifications(integer) to authenticated;

create or replace function public.admin_delete_notification(p_id uuid) returns boolean
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    delete from public.driver_notifications where id = p_id;
    return found;
end $$;
revoke all on function public.admin_delete_notification(uuid) from public, anon;
grant execute on function public.admin_delete_notification(uuid) to authenticated;

create or replace function public.purge_driver_notifications(p_keep interval default interval '90 days') returns integer
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_deleted integer;
begin
    delete from public.driver_notifications
    where created_at < now() - p_keep or (expires_at is not null and expires_at < now() - interval '7 days');
    get diagnostics v_deleted = row_count;
    return v_deleted;
end $$;
revoke all on function public.purge_driver_notifications(interval) from public, anon, authenticated;

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'purge_driver_notifications';
        perform cron.schedule('purge_driver_notifications', '27 3 * * *', 'select public.purge_driver_notifications()');
    end if;
end $$;

comment on table public.driver_notifications is 'Administration messages to one driver (driver_id) or everyone (null). Retained 90 days by purge_driver_notifications() (pg_cron job purge_driver_notifications when available).';
