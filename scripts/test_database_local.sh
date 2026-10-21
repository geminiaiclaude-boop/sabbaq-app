#!/usr/bin/env bash
# Linux sandbox/local development only; does not connect to Supabase or remote databases.
set -euo pipefail
cd "$(dirname "$0")/.."
database="sabbaq_local_test_${BASHPID}"
local_pg=(--host=/var/run/postgresql --port=5432)
sudo -u postgres createdb "${local_pg[@]}" "$database"
trap 'sudo -u postgres dropdb "${local_pg[@]}" "$database"' EXIT
psql_local() { sudo -u postgres psql "${local_pg[@]}" -d "$database" -v ON_ERROR_STOP=1 "$@"; }
psql_local -f supabase/tests/bootstrap.sql
for migration in supabase/migrations/*.sql; do
  echo "MIGRATION: $migration"
  psql_local -f "$migration"
done
for suite in supabase/tests/license_test.sql supabase/tests/registration_admin_test.sql supabase/tests/saudi_commercial_trial_test.sql supabase/tests/account_deletion_test.sql; do
  echo "TEST FILE: $suite"
  psql_local -f "$suite"
done
