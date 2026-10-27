-- Which Sabbaq build each driver runs: the version code the app sends with every licence check,
-- plus the version name and Android level from the driver's latest telemetry row.
create or replace function public.admin_driver_versions(p_drivers uuid[]) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare result jsonb;
begin
    perform public.require_admin();
    if p_drivers is null or cardinality(p_drivers) = 0 then return '[]'::jsonb; end if;
    if cardinality(p_drivers) > 200 then raise exception 'too_many_ids' using errcode = '22023'; end if;
    select coalesce(jsonb_agg(jsonb_build_object(
            'driver_id', d.id,
            'version_code', d.app_version,
            'last_seen_at', d.last_seen_at,
            'version_name', t.app_version,
            'android_api', t.android_api,
            'reported_at', t.received_at)), '[]'::jsonb)
    into result
    from public.drivers d
    left join lateral (
        select e.app_version, e.android_api, e.received_at from public.offer_telemetry_events e
        where e.ingested_by = d.id order by e.received_at desc limit 1
    ) t on true
    where d.id = any (p_drivers);
    return result;
end $$;
revoke all on function public.admin_driver_versions(uuid[]) from public, anon;
grant execute on function public.admin_driver_versions(uuid[]) to authenticated;
