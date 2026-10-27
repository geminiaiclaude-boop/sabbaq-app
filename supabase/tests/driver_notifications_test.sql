-- In-app notification centre contract. Run after bootstrap.sql, all migrations and the earlier
-- suites on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('7e2e0000-0000-0000-0000-000000000001', 'notify-admin@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('7e2e0000-0000-0000-0000-000000000002', 'notify-driver-a@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Driver Alpha"}'::jsonb),
    ('7e2e0000-0000-0000-0000-000000000003', 'notify-driver-b@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Driver Beta"}'::jsonb);
insert into public.admins (user_id) values ('7e2e0000-0000-0000-0000-000000000001');

set role authenticated;
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000002';
select public.ensure_driver_profile();
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000003';
select public.ensure_driver_profile();
reset role;

-- Clients cannot read or write the tables directly, and anonymous callers get nothing.
set role authenticated;
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000002';
do $$ begin
    perform 1 from public.driver_notifications;
    raise exception 'EXPECTATION FAILED: driver read the table directly';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_send_notification(null, 'عنوان', 'نص الإشعار');
    raise exception 'EXPECTATION FAILED: driver sent a notification';
exception when insufficient_privilege then null;
end $$;
reset role;
set role anon;
do $$ begin
    perform public.my_notifications();
    raise exception 'EXPECTATION FAILED: anonymous read notifications';
exception when insufficient_privilege then null;
end $$;
reset role;

-- The admin sends one broadcast and one message to driver A; invalid input is refused.
set role authenticated;
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000001';
create temp table sent as select
    (public.admin_send_notification(null, 'تحديث متاح', 'نسخة جديدة من سبّاق متاحة الآن.', 'update', 'update_app') ->> 'id')::uuid as broadcast_id,
    (public.admin_send_notification('7e2e0000-0000-0000-0000-000000000002', 'اشتراكك', 'ينتهي اشتراكك بعد يومين.', 'account', 'open_account') ->> 'id')::uuid as direct_id;
do $$ begin
    perform public.admin_send_notification(null, 'x', 'نص');
    raise exception 'EXPECTATION FAILED: one-letter title accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.admin_send_notification(null, 'عنوان', 'نص الإشعار', 'shout');
    raise exception 'EXPECTATION FAILED: unknown kind accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.admin_send_notification(null, 'عنوان', 'نص الإشعار', 'info', 'open_url');
    raise exception 'EXPECTATION FAILED: unknown action accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.admin_send_notification('7e2e0000-0000-0000-0000-0000000000ff', 'عنوان', 'نص الإشعار');
    raise exception 'EXPECTATION FAILED: unknown driver accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.admin_send_notification(null, 'عنوان', 'نص الإشعار', 'info', null, now() - interval '1 hour');
    raise exception 'EXPECTATION FAILED: past expiry accepted';
exception when sqlstate '22023' then null;
end $$;
select pg_temp.expect(jsonb_array_length(public.admin_list_notifications()) >= 2, 'admin lists sent notifications');
reset role;
grant select on sent to authenticated;

-- Driver A sees both; driver B sees only the broadcast.
set role authenticated;
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000002';
select pg_temp.expect(jsonb_array_length(public.my_notifications()) = 2, 'driver A sees broadcast and direct message');
select pg_temp.expect(not (public.my_notifications() -> 0 ->> 'read')::boolean, 'new notifications are unread');
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000003';
select pg_temp.expect(jsonb_array_length(public.my_notifications()) = 1, 'driver B sees only the broadcast');
select pg_temp.expect(public.my_notifications() -> 0 ->> 'kind' = 'update', 'broadcast keeps its kind');

-- Driver B cannot mark driver A's message; marking is idempotent.
select pg_temp.expect(public.mark_notifications_read(array[(select direct_id from sent)]) = 0, 'foreign message cannot be marked');
select pg_temp.expect(public.mark_notifications_read(array[(select broadcast_id from sent)]) = 1, 'broadcast marked read');
select pg_temp.expect(public.mark_notifications_read(array[(select broadcast_id from sent)]) = 0, 'marking twice is a no-op');
select pg_temp.expect((public.my_notifications() -> 0 ->> 'read')::boolean, 'read state is returned');
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000002';
select pg_temp.expect(
    (select count(*) from jsonb_array_elements(public.my_notifications()) e where (e ->> 'read')::boolean) = 0,
    'one driver reading a broadcast does not mark it for another');
reset role;

-- Expired messages disappear; deleting removes them for everyone; retention is server-only.
update public.driver_notifications set created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
where id = (select direct_id from sent);
set role authenticated;
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000002';
select pg_temp.expect(jsonb_array_length(public.my_notifications()) = 1, 'expired message is hidden');
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000001';
select pg_temp.expect(public.admin_delete_notification((select broadcast_id from sent)), 'admin deletes the broadcast');
set request.jwt.claim.sub = '7e2e0000-0000-0000-0000-000000000003';
select pg_temp.expect(jsonb_array_length(public.my_notifications()) = 0, 'deleted broadcast is gone');
do $$ begin
    perform public.purge_driver_notifications();
    raise exception 'EXPECTATION FAILED: client ran retention';
exception when insufficient_privilege then null;
end $$;
reset role;
update public.driver_notifications set created_at = now() - interval '20 days', expires_at = now() - interval '10 days'
where id = (select direct_id from sent);
select pg_temp.expect(public.purge_driver_notifications() = 1, 'long-expired message purged');
