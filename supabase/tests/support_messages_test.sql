-- In-app support conversation contract. Run after bootstrap.sql, all migrations and the earlier
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
    ('7e3e0000-0000-0000-0000-000000000001', 'support-admin@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('7e3e0000-0000-0000-0000-000000000002', 'support-driver-a@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Support Alpha"}'::jsonb),
    ('7e3e0000-0000-0000-0000-000000000003', 'support-driver-b@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Support Beta"}'::jsonb),
    ('7e3e0000-0000-0000-0000-000000000004', 'support-no-profile@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb);
insert into public.admins (user_id) values ('7e3e0000-0000-0000-0000-000000000001');

set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000002';
select public.ensure_driver_profile();
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000003';
select public.ensure_driver_profile();
reset role;

-- No direct table access; anonymous and profile-less callers are refused.
set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000002';
do $$ begin
    perform 1 from public.support_messages;
    raise exception 'EXPECTATION FAILED: driver read messages directly';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_support_threads();
    raise exception 'EXPECTATION FAILED: driver listed support threads';
exception when insufficient_privilege then null;
end $$;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000004';
do $$ begin
    perform public.support_send(gen_random_uuid(), 'مرحبا');
    raise exception 'EXPECTATION FAILED: account without a driver profile wrote';
exception when insufficient_privilege then null;
end $$;
reset role;
set role anon;
do $$ begin
    perform public.support_my_thread();
    raise exception 'EXPECTATION FAILED: anonymous read support';
exception when insufficient_privilege then null;
end $$;
reset role;

-- Driver A writes; a retried send with the same client id is not duplicated; bad input refused.
set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000002';
select pg_temp.expect(jsonb_array_length(public.support_my_thread() -> 'messages') = 0, 'empty conversation before the first message');
select pg_temp.expect(not (public.support_send('00000000-0000-4000-8000-0000000000a1', '  سبّاق لا يقبل الطلبات  ', '1.4.0', '5eef581d3799dfc07675dedb7025613c') ->> 'duplicate')::boolean,
    'first message stored');
select pg_temp.expect((public.support_send('00000000-0000-4000-8000-0000000000a1', 'سبّاق لا يقبل الطلبات') ->> 'duplicate')::boolean,
    'retried send is idempotent');
select pg_temp.expect(public.support_my_thread() -> 'messages' -> 0 ->> 'body' = 'سبّاق لا يقبل الطلبات', 'body is trimmed');
do $$ begin
    perform public.support_send(gen_random_uuid(), '   ');
    raise exception 'EXPECTATION FAILED: blank message accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.support_send(gen_random_uuid(), repeat('a', 1001));
    raise exception 'EXPECTATION FAILED: oversized message accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.support_send(gen_random_uuid(), 'نص', 'not a version!');
    raise exception 'EXPECTATION FAILED: free-text app version accepted';
exception when sqlstate '22023' then null;
end $$;
-- Driver B cannot see A's conversation.
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000003';
select pg_temp.expect(jsonb_array_length(public.support_my_thread() -> 'messages') = 0, 'conversations are private per driver');
reset role;

-- The admin sees the unread thread with its diagnostics, opens it (marking it read) and replies.
set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000001';
select pg_temp.expect((public.admin_support_threads() -> 0 ->> 'admin_unread')::int = 1, 'admin sees one unread message');
select pg_temp.expect(public.admin_support_threads() -> 0 ->> 'last_correlation_id' = '5eef581d3799dfc07675dedb7025613c', 'support id travels with the thread');
select pg_temp.expect(public.admin_support_threads() -> 0 -> 'last_message' ->> 'sender' = 'driver', 'inbox previews the driver, not the automatic reply');
select pg_temp.expect(public.admin_support_thread('7e3e0000-0000-0000-0000-000000000002') ->> 'last_app_version' = '1.4.0', 'app version is shown');
select pg_temp.expect((public.admin_support_threads() -> 0 ->> 'admin_unread')::int = 0, 'opening the thread marks it read');
select pg_temp.expect(not (public.admin_support_reply('7e3e0000-0000-0000-0000-000000000002', '00000000-0000-4000-8000-0000000000b1',
    'فعّل إذن متابعة الطلبات من الإعداد.') ->> 'duplicate')::boolean, 'admin reply stored');
select pg_temp.expect((public.admin_support_reply('7e3e0000-0000-0000-0000-000000000002', '00000000-0000-4000-8000-0000000000b1',
    'فعّل إذن متابعة الطلبات من الإعداد.') ->> 'duplicate')::boolean, 'retried reply is idempotent');
do $$ begin
    perform public.admin_support_reply('7e3e0000-0000-0000-0000-0000000000ff', gen_random_uuid(), 'نص');
    raise exception 'EXPECTATION FAILED: reply to unknown driver accepted';
exception when sqlstate '22023' then null;
end $$;
reset role;

-- The driver gets the reply, an unread badge and a notification that opens the conversation.
set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.support_unread() = 1, 'driver badge shows the reply');
select pg_temp.expect(
    (select count(*) from jsonb_array_elements(public.my_notifications()) n where n ->> 'action' = 'open_support') = 1,
    'reply raises one notification');
select pg_temp.expect(jsonb_array_length(public.support_my_thread() -> 'messages') = 3, 'driver sees own message, automatic acknowledgement and reply');
select pg_temp.expect(public.support_my_thread() -> 'messages' -> 1 ->> 'sender' = 'admin', 'acknowledgement follows the first message');
select pg_temp.expect(public.support_unread() = 0, 'reading the conversation clears the badge');
reset role;

-- Closing then writing again reopens; the hourly bound applies.
set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000001';
select pg_temp.expect(public.admin_support_set_status('7e3e0000-0000-0000-0000-000000000002', 'closed'), 'admin closes the thread');
select pg_temp.expect(jsonb_array_length(public.admin_support_threads('open')) = 0, 'closed thread leaves the open inbox');
reset role;
set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.support_my_thread() ->> 'status' = 'closed', 'driver sees the closed state');
select pg_temp.expect((public.support_send(gen_random_uuid(), 'ما زالت المشكلة') ->> 'reopened')::boolean, 'writing reopens the thread');
do $$ declare i int; begin
    for i in 1..18 loop perform public.support_send(gen_random_uuid(), 'رسالة ' || i); end loop;
    perform public.support_send(gen_random_uuid(), 'رسالة زائدة');
    raise exception 'EXPECTATION FAILED: hourly bound not applied';
exception when sqlstate 'PT429' then null;
end $$;
reset role;

-- Retention removes long-idle closed threads only, and is server-only.
update public.support_threads set status = 'closed', last_message_at = now() - interval '200 days'
where driver_id = '7e3e0000-0000-0000-0000-000000000002';
set role authenticated;
set request.jwt.claim.sub = '7e3e0000-0000-0000-0000-000000000002';
do $$ begin
    perform public.purge_support_threads();
    raise exception 'EXPECTATION FAILED: client ran support retention';
exception when insufficient_privilege then null;
end $$;
reset role;
select pg_temp.expect(public.purge_support_threads() = 1, 'idle closed thread purged');
select pg_temp.expect(not exists (select 1 from public.support_messages where driver_id = '7e3e0000-0000-0000-0000-000000000002'),
    'its messages are gone');
