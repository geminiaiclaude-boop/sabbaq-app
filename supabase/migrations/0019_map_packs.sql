-- Offline city maps: a public, read-only Storage bucket `maps` holding one PMTiles archive per
-- city and `sa/manifest.json` listing them with their SHA-256 (scripts/maps/build_city_packs.py).
-- The app downloads its driver's city from here; only the service role (the build script) writes.
-- Supabase only; the local test database has no storage schema.
do $$
begin
    if exists (select 1 from information_schema.tables where table_schema = 'storage' and table_name = 'buckets') then
        insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
        values ('maps', 'maps', true, 52428800, array['application/octet-stream', 'application/json'])
        on conflict (id) do update set public = true, file_size_limit = excluded.file_size_limit,
            allowed_mime_types = excluded.allowed_mime_types;
    end if;
end $$;
