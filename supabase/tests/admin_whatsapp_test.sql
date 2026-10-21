-- Administration WhatsApp number for subscription purchases. Run after bootstrap.sql,
-- all migrations and the earlier suites on a fresh local PostgreSQL database.
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
    ('30000000-0000-0000-0000-000000000001', 'wa-admin@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('30000000-0000-0000-0000-000000000002', null, '+966512388002', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb);
insert into public.admins (user_id) values ('30000000-0000-0000-0000-000000000001');

-- A driver cannot set the number.
set role authenticated;
set request.jwt.claim.sub = '30000000-0000-0000-0000-000000000002';
do $$ begin
    perform public.admin_set_whatsapp('966512345678');
    raise exception 'EXPECTATION FAILED: driver changed the administration number';
exception when insufficient_privilege then null;
end $$;
reset role;

-- The admin sets it in any natural format; the server stores wa.me digits.
set role authenticated;
set request.jwt.claim.sub = '30000000-0000-0000-0000-000000000001';
select pg_temp.expect(public.admin_set_whatsapp('0512345678') ->> 'admin_whatsapp' = '966512345678', 'local 05 format normalised');
select pg_temp.expect(public.admin_set_whatsapp('+966 51 234 5679') ->> 'admin_whatsapp' = '966512345679', 'spaced + format normalised');
select pg_temp.expect(public.admin_set_whatsapp('00966512345670') ->> 'admin_whatsapp' = '966512345670', '00 prefix normalised');
do $$ begin
    perform public.admin_set_whatsapp('12ab');
    raise exception 'EXPECTATION FAILED: invalid number accepted';
exception when sqlstate '22023' then null;
end $$;
select pg_temp.expect(public.admin_commercial_policy() ->> 'admin_whatsapp' = '966512345670', 'admin view carries the number');
reset role;

-- Every client sees it in the public offer, including before sign-in.
set role anon;
select pg_temp.expect(public.public_offer() ->> 'admin_whatsapp' = '966512345670', 'public offer carries the number');
reset role;

-- Clearing removes the purchase channel.
set role authenticated;
set request.jwt.claim.sub = '30000000-0000-0000-0000-000000000001';
select pg_temp.expect(public.admin_set_whatsapp('  ') ->> 'admin_whatsapp' is null, 'blank clears the number');
reset role;
select pg_temp.expect(public.public_offer() -> 'admin_whatsapp' = 'null'::jsonb, 'cleared number is null in the offer');
select pg_temp.expect(
    exists (select 1 from public.audit_log where action = 'set_admin_whatsapp' and actor = '30000000-0000-0000-0000-000000000001'),
    'number changes are audited'
);
