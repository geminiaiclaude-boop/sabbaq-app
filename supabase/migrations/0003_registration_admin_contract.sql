-- Complete the client/database registration and administration contracts without
-- changing the behaviour of existing account or licensing APIs.

-- ---------------------------------------------------------------- admin status
-- A dedicated, read-only authorization check avoids overloading a mutating admin
-- RPC with intentionally invalid input. require_admin() remains the single source
-- of truth for administrative membership.
create or replace function public.admin_session_status() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    return jsonb_build_object('is_admin', true);
end $$;

revoke all on function public.admin_session_status() from public, anon;
grant execute on function public.admin_session_status() to authenticated;

-- ------------------------------------------------------- signup profile trigger
-- Supabase's auth.users row is outside the public API. Read its metadata through
-- to_jsonb(NEW) so the plain-PostgreSQL test stand-in without that column remains
-- compatible; real Supabase rows expose raw_user_meta_data normally.
create or replace function public.create_driver_from_signup_metadata() returns trigger
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_metadata jsonb := to_jsonb(NEW) -> 'raw_user_meta_data';
    v_name     text;
    v_phone    text;
begin
    -- Invalid, absent, or non-string metadata deliberately leaves account creation
    -- untouched. The values accepted below produce exactly the normalized values
    -- checked by public.drivers: a trimmed 2..80-character name and canonical phone.
    if jsonb_typeof(v_metadata) is distinct from 'object'
       or jsonb_typeof(v_metadata -> 'full_name') is distinct from 'string'
       or jsonb_typeof(v_metadata -> 'phone') is distinct from 'string' then
        return NEW;
    end if;

    v_name := btrim(v_metadata ->> 'full_name');
    v_phone := regexp_replace(v_metadata ->> 'phone', '[[:space:]-]', '', 'g');
    if char_length(v_name) not between 2 and 80
       or v_phone !~ '^\+?[0-9]{7,15}$' then
        return NEW;
    end if;

    -- The trigger only ever creates the pending default row for NEW.id. It never
    -- accepts metadata for status, administrative privileges, or another user id.
    insert into public.drivers (id, full_name, phone)
    values (NEW.id, v_name, v_phone)
    on conflict (id) do nothing;
    return NEW;
end $$;

revoke all on function public.create_driver_from_signup_metadata() from public, anon, authenticated;

drop trigger if exists create_driver_from_signup_metadata on auth.users;
create trigger create_driver_from_signup_metadata
    after insert on auth.users
    for each row execute function public.create_driver_from_signup_metadata();
