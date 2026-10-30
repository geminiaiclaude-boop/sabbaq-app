-- Destination zone policy contract (0017). Run after bootstrap.sql, all migrations and the
-- earlier suites on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
select ('20e00000-0000-0000-0000-00000000000' || n)::uuid, 'zone-' || n || '@example.test',
       '{"provider":"google","providers":["google"]}'::jsonb, jsonb_build_object('full_name', 'Zone ' || n)
from generate_series(1, 4) n;
insert into public.admins (user_id) values ('20e00000-0000-0000-0000-000000000001');
insert into public.drivers (id, full_name, phone, status) values
    ('20e00000-0000-0000-0000-000000000002', 'Saudi Driver', '+966500000002', 'active'),
    ('20e00000-0000-0000-0000-000000000003', 'Jordan Driver', '+962790000003', 'active'),
    ('20e00000-0000-0000-0000-000000000004', 'Other Driver', '+20100000004', 'active');

select pg_temp.expect(private.zone_country('+966 50 000 0000') = 'SA' and private.zone_country('962790000000') = 'JO'
    and private.zone_country('+20100000000') is null, 'country from the phone number');

set role authenticated;

-- Nothing published yet: no shared zones and no limit.
select set_config('request.jwt.claim.sub', '20e00000-0000-0000-0000-000000000002', false);
select pg_temp.expect(public.zone_policy() ->> 'country' = 'SA' and public.zone_policy() -> 'zones' = '[]'::jsonb
    and (public.zone_policy() ->> 'max_active_zones')::int = 0 and (public.zone_policy() ->> 'version')::int = 0,
    'empty policy before anything is published');

-- Only an admin may publish.
do $$ begin
    perform public.admin_set_global_zones('SA', true, '[]'::jsonb);
    raise exception 'EXPECTATION FAILED: a driver cannot publish zones';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_set_driver_zone_limit('20e00000-0000-0000-0000-000000000002', 50);
    raise exception 'EXPECTATION FAILED: a driver cannot raise their own limit';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    update public.drivers set max_active_zones = 50 where id = auth.uid();
    raise exception 'EXPECTATION FAILED: a driver cannot write their limit directly';
exception when insufficient_privilege then null;
end $$;

select set_config('request.jwt.claim.sub', '20e00000-0000-0000-0000-000000000001', false);
do $$
declare bad jsonb;
begin
    foreach bad in array array[
        '{}'::jsonb,
        '[{"name":"","points":[[24.6,46.6],[24.6,46.7],[24.7,46.7]]}]',
        '[{"name":"A","points":[[24.6,46.6],[24.6,46.7]]}]',
        '[{"name":"A","points":[[124.6,46.6],[24.6,46.7],[24.7,46.7]]}]',
        '[{"name":"A","points":[["24.6",46.6],[24.6,46.7],[24.7,46.7]]}]',
        '[{"name":"A","active":"yes","points":[[24.6,46.6],[24.6,46.7],[24.7,46.7]]}]']
    loop
        begin
            perform public.admin_set_global_zones('SA', true, bad);
            raise exception 'EXPECTATION FAILED: invalid zones accepted (%)', bad;
        exception when invalid_parameter_value then null;
        end;
    end loop;
    begin
        perform public.admin_set_global_zones('EG', true, '[]'::jsonb);
        raise exception 'EXPECTATION FAILED: unknown country accepted';
    exception when invalid_parameter_value then null;
    end;
end $$;

select pg_temp.expect((public.admin_set_global_zones('SA', true,
    '[{"name":"المطار","active":true,"points":[[24.95,46.69],[24.95,46.72],[24.97,46.72],[24.97,46.69]]}]') ->> 'version')::int = 1,
    'Saudi zones published, version 1');
select pg_temp.expect((public.admin_set_global_zones('JO', false,
    '[{"name":"Airport","points":[[31.71,35.98],[31.71,36.00],[31.73,36.00]]}]') ->> 'version')::int = 2,
    'Jordan zones saved switched off, version 2');
select pg_temp.expect((public.admin_set_zone_limits(3, 80) ->> 'default_max_zones')::int = 3, 'default limit set');
select pg_temp.expect((public.admin_set_driver_zone_limit('20e00000-0000-0000-0000-000000000003', 10) ->> 'own')::int = 10,
    'one driver gets their own limit');
do $$ begin
    perform public.admin_set_zone_limits(101, 10);
    raise exception 'EXPECTATION FAILED: limit above 100 accepted';
exception when invalid_parameter_value then null;
end $$;
select pg_temp.expect((select count(*) from public.audit_log where action in ('set_global_zones', 'set_zone_limits', 'set_driver_zone_limit')) = 4,
    'every admin change is audited');

-- Each driver sees their country's zones only while it is on, and their own limit first.
select set_config('request.jwt.claim.sub', '20e00000-0000-0000-0000-000000000002', false);
select pg_temp.expect(jsonb_array_length(public.zone_policy() -> 'zones') = 1 and public.zone_policy() -> 'zones' -> 0 ->> 'name' = 'المطار'
    and (public.zone_policy() ->> 'max_active_zones')::int = 3 and (public.zone_policy() ->> 'proximity_km')::numeric = 80
    and (public.zone_policy() ->> 'version')::int = 2, 'Saudi driver: Saudi zones, the default limit');
select set_config('request.jwt.claim.sub', '20e00000-0000-0000-0000-000000000003', false);
select pg_temp.expect(public.zone_policy() -> 'zones' = '[]'::jsonb and (public.zone_policy() ->> 'max_active_zones')::int = 10,
    'Jordan driver: country switched off, own limit');
select set_config('request.jwt.claim.sub', '20e00000-0000-0000-0000-000000000004', false);
select pg_temp.expect(public.zone_policy() ->> 'country' is null and public.zone_policy() -> 'zones' = '[]'::jsonb,
    'driver of another country gets no shared zones');
do $$ begin
    perform public.admin_zone_policy();
    raise exception 'EXPECTATION FAILED: a driver cannot read the full policy';
exception when insufficient_privilege then null;
end $$;

-- Back to the default.
select set_config('request.jwt.claim.sub', '20e00000-0000-0000-0000-000000000001', false);
select pg_temp.expect(public.admin_set_driver_zone_limit('20e00000-0000-0000-0000-000000000003', null) -> 'own' = 'null'::jsonb,
    'override cleared');
reset role;
