-- Creates the login role the backend connects with. Run once per environment as the database owner,
-- AFTER the migrations (which create nw_api, nw_correlation and nw_service):
--
--   psql "$OWNER_DATABASE_URL" -v app_password="$(openssl rand -hex 24)" -f infrastructure/scripts/create_app_login.sql
--
-- The role has no privileges of its own (NOINHERIT). It can only switch to the three restricted roles,
-- which the backend does at the start of every transaction. Put the password in the hosting
-- provider's secret store as part of DATABASE_URL; do not write it down anywhere else.
\set ON_ERROR_STOP on
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nw_app_login') THEN
        CREATE ROLE nw_app_login LOGIN NOINHERIT;
    END IF;
END $$;
ALTER ROLE nw_app_login PASSWORD :'app_password';
GRANT nw_api, nw_correlation, nw_service TO nw_app_login;
-- Supabase only: keep the auto-generated REST API away from this schema (it is not exposed by default).
-- REVOKE ALL ON SCHEMA nw FROM anon, authenticated;
