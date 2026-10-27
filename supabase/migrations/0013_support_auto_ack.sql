-- Automatic acknowledgement in support: when a driver opens a conversation (first message, or the
-- first after it was closed) the administration's side answers at once, so the driver knows the
-- message arrived. Automatic messages are marked so the admin inbox previews the driver's words.
alter table public.support_messages add column if not exists auto boolean not null default false;

create or replace function public.support_send(p_client_id uuid, p_body text, p_app_version text default null, p_correlation_id text default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_uid uuid := auth.uid(); v_body text := btrim(coalesce(p_body, '')); v_id uuid; v_status text; v_existed boolean;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if not exists (select 1 from public.drivers where id = v_uid) then raise exception 'unregistered' using errcode = '42501'; end if;
    if p_client_id is null or char_length(v_body) not between 1 and 1000 then raise exception 'invalid_message' using errcode = '22023'; end if;
    if p_app_version is not null and p_app_version !~ '^[A-Za-z0-9._-]{1,32}$' then raise exception 'invalid_message' using errcode = '22023'; end if;
    if p_correlation_id is not null and p_correlation_id !~ '^[a-f0-9]{16,64}$' then raise exception 'invalid_message' using errcode = '22023'; end if;

    -- The thread row lock serializes this driver's sends, so concurrent requests cannot all pass
    -- the hourly bound before any of them inserts.
    v_existed := exists (select 1 from public.support_threads where driver_id = v_uid);
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
    if not v_existed or v_status = 'closed' then
        insert into public.support_messages (driver_id, client_id, sender, body, auto, created_at)
        values (v_uid, gen_random_uuid(), 'admin',
                'استلمنا رسالتك ✅ سيرد عليك فريق سبّاق هنا في أقرب وقت خلال ساعات العمل، وسيصلك إشعار عند الرد.',
                true, now() + interval '1 millisecond');
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
                             from public.support_messages m where m.driver_id = t.driver_id and not m.auto
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
