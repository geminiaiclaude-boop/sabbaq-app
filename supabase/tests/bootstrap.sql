-- Minimal stand-in for the parts of Supabase the migration relies on,
-- so the schema and its security rules are exercised in plain PostgreSQL.
create schema if not exists auth;
create table if not exists auth.users (
    id uuid primary key,
    email text,
    phone text,
    phone_confirmed_at timestamptz,
    raw_app_meta_data jsonb not null default '{}'::jsonb,
    raw_user_meta_data jsonb not null default '{}'::jsonb,
    encrypted_password text
);
create or replace function auth.uid() returns uuid language sql stable as $$
    select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;
do $$ begin
    create role anon nologin;
exception when duplicate_object then null; end $$;
do $$ begin
    create role authenticated nologin;
exception when duplicate_object then null; end $$;
grant usage on schema public, auth to anon, authenticated;
grant execute on function auth.uid() to anon, authenticated;
