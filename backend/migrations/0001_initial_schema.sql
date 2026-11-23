-- Neighborhood Watchdog: initial schema.
-- Standard PostgreSQL only (13+). No Supabase-specific features and no extensions are required.
-- All tables live in schema "nw", which Supabase's auto-generated REST API does not expose.

CREATE SCHEMA IF NOT EXISTS nw;

-- ------------------------------------------------------------------ roles
-- nw_api:         request handling on behalf of one signed-in resident; fully subject to row-level security.
-- nw_correlation: the correlation engine; column-limited, cannot read addresses, e-mail or tokens.
-- nw_service:     trusted backend operations (registration, administration, notifications, retention).
-- The login role used by the backend is made a member of all three and must SET LOCAL ROLE in every
-- transaction. The application never runs queries as the owner role.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nw_api') THEN CREATE ROLE nw_api NOLOGIN; END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nw_correlation') THEN CREATE ROLE nw_correlation NOLOGIN; END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nw_service') THEN CREATE ROLE nw_service NOLOGIN; END IF;
    EXECUTE format('GRANT nw_api, nw_correlation, nw_service TO %I', current_user);
END $$;

GRANT USAGE ON SCHEMA nw TO nw_api, nw_correlation, nw_service;

-- ------------------------------------------------------------------ helpers
CREATE FUNCTION nw.set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END $$;

CREATE FUNCTION nw.current_user_id() RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.user_id', true), '')::uuid
$$;

-- ------------------------------------------------------------------ tables
CREATE TABLE nw.users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_subject  text UNIQUE,                 -- "sub" of the identity provider; null until first sign-in
    email         text UNIQUE,                 -- lower-cased; null after account deletion
    status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active', 'deleted')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nw.neighborhoods (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text NOT NULL,
    status      text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nw.households (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id           uuid NOT NULL REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    anonymous_reference       text NOT NULL,   -- random label, safe to show to other residents
    normalized_house_number   text NOT NULL,
    normalized_street_name    text NOT NULL,
    display_label             text,
    isp                       text NOT NULL DEFAULT 'unknown',
    latitude                  double precision CHECK (latitude BETWEEN -90 AND 90),
    longitude                 double precision CHECK (longitude BETWEEN -180 AND 180),
    membership_status         text NOT NULL DEFAULT 'approved'
                              CHECK (membership_status IN ('approved', 'active', 'suspended', 'removed')),
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    UNIQUE (neighborhood_id, normalized_house_number, normalized_street_name),
    UNIQUE (neighborhood_id, anonymous_reference),
    UNIQUE (id, neighborhood_id)
);
CREATE INDEX households_neighborhood_idx ON nw.households (neighborhood_id, membership_status);

CREATE TABLE nw.household_members (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id  uuid NOT NULL REFERENCES nw.households (id) ON DELETE CASCADE,
    user_id       uuid NOT NULL REFERENCES nw.users (id) ON DELETE CASCADE,
    role          text NOT NULL DEFAULT 'resident' CHECK (role IN ('resident', 'admin')),
    status        text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'removed')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (household_id, user_id)
);
-- A person belongs to at most one household at a time.
CREATE UNIQUE INDEX household_members_one_active_per_user ON nw.household_members (user_id) WHERE status = 'active';

CREATE TABLE nw.invitations (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id  uuid NOT NULL REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    household_id     uuid REFERENCES nw.households (id) ON DELETE CASCADE,
    token_hash       text NOT NULL UNIQUE,     -- SHA-256 of the token; the token itself is never stored
    role             text NOT NULL DEFAULT 'resident' CHECK (role IN ('resident', 'admin')),
    status           text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'used', 'revoked')),
    expires_at       timestamptz NOT NULL,
    used_at          timestamptz,
    used_by_user_id  uuid REFERENCES nw.users (id) ON DELETE SET NULL,
    created_by       uuid REFERENCES nw.users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invitations_neighborhood_idx ON nw.invitations (neighborhood_id, status);

CREATE TABLE nw.devices (
    id                  uuid PRIMARY KEY,      -- random install identifier generated by the app
    user_id             uuid NOT NULL REFERENCES nw.users (id) ON DELETE CASCADE,
    household_id        uuid NOT NULL REFERENCES nw.households (id) ON DELETE CASCADE,
    platform            text NOT NULL CHECK (platform IN ('android', 'ios')),
    os_version          text NOT NULL,
    app_version         text NOT NULL,
    capabilities        jsonb NOT NULL DEFAULT '{}'::jsonb,
    status              text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'revoked')),
    monitoring_enabled  boolean NOT NULL DEFAULT false,
    last_state          text NOT NULL DEFAULT 'UNKNOWN',
    last_event_at       timestamptz,           -- occurrence time of the newest event
    last_seen_at        timestamptz,           -- receipt time of the newest upload
    last_sequence       bigint,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX devices_household_idx ON nw.devices (household_id, status);
CREATE INDEX devices_user_idx ON nw.devices (user_id);

-- One row per uninterrupted monitoring run of a device. A new session starts when monitoring starts
-- or resumes after a gap; the previous one is closed with the reason.
CREATE TABLE nw.device_sessions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id          uuid NOT NULL REFERENCES nw.devices (id) ON DELETE CASCADE,
    started_at         timestamptz NOT NULL,
    last_heartbeat_at  timestamptz NOT NULL,
    ended_at           timestamptz,
    end_reason         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX device_sessions_device_idx ON nw.device_sessions (device_id, started_at DESC);
CREATE UNIQUE INDEX device_sessions_one_open ON nw.device_sessions (device_id) WHERE ended_at IS NULL;

CREATE TABLE nw.connectivity_events (
    id                  uuid PRIMARY KEY,      -- event UUID generated on the device: the idempotency key
    device_id           uuid NOT NULL REFERENCES nw.devices (id) ON DELETE CASCADE,
    household_id        uuid NOT NULL,
    neighborhood_id     uuid NOT NULL,
    sequence            bigint NOT NULL,
    event_type          text NOT NULL,
    connectivity_state  text NOT NULL,
    occurred_at         timestamptz NOT NULL,  -- when it happened on the device
    received_at         timestamptz NOT NULL,  -- when the backend received it
    reported_via        text NOT NULL,
    platform            text NOT NULL,
    os_version          text NOT NULL,
    app_version         text NOT NULL,
    confidence          text NOT NULL,
    diagnostics         jsonb NOT NULL DEFAULT '{}'::jsonb,
    capabilities        jsonb NOT NULL DEFAULT '{}'::jsonb,
    report_delay_s      double precision GENERATED ALWAYS AS (extract(epoch FROM (received_at - occurred_at))) STORED,
    excluded_reason     text,                  -- set when abuse controls keep the event out of correlation
    correlated_at       timestamptz,           -- null until the correlation engine has processed it
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (household_id, neighborhood_id) REFERENCES nw.households (id, neighborhood_id) ON DELETE CASCADE
);
CREATE INDEX connectivity_events_neighborhood_time_idx ON nw.connectivity_events (neighborhood_id, occurred_at);
CREATE INDEX connectivity_events_household_time_idx ON nw.connectivity_events (household_id, occurred_at);
CREATE INDEX connectivity_events_device_received_idx ON nw.connectivity_events (device_id, received_at);
CREATE INDEX connectivity_events_pending_idx ON nw.connectivity_events (neighborhood_id) WHERE correlated_at IS NULL;
CREATE INDEX connectivity_events_received_idx ON nw.connectivity_events (received_at);

CREATE TABLE nw.incident_candidates (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id   uuid NOT NULL REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    window_start      timestamptz NOT NULL,
    window_end        timestamptz NOT NULL,
    household_count   integer NOT NULL,
    score             double precision,
    status            text NOT NULL CHECK (status IN ('promoted', 'below_threshold')),
    detail            jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (neighborhood_id, window_start)
);

CREATE TABLE nw.incidents (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id           uuid NOT NULL REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    candidate_id              uuid REFERENCES nw.incident_candidates (id) ON DELETE SET NULL,
    scope                     text NOT NULL CHECK (scope IN ('household', 'neighborhood')),
    classification            text NOT NULL,
    alert_level               smallint NOT NULL CHECK (alert_level BETWEEN 0 AND 3),
    peak_alert_level          smallint NOT NULL DEFAULT 0 CHECK (peak_alert_level BETWEEN 0 AND 3),
    status                    text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'expired', 'merged')),
    at_home_evidence          boolean NOT NULL DEFAULT false,
    score                     double precision,
    score_breakdown           jsonb NOT NULL DEFAULT '[]'::jsonb,
    explanation               text NOT NULL,
    supporting_evidence       jsonb NOT NULL DEFAULT '[]'::jsonb,
    missing_evidence          jsonb NOT NULL DEFAULT '[]'::jsonb,
    started_at                timestamptz NOT NULL,
    last_event_at             timestamptz NOT NULL,
    resolved_at               timestamptz,
    affected_household_count  integer NOT NULL,
    active_household_count    integer NOT NULL,
    time_spread_s             double precision NOT NULL DEFAULT 0,
    approximate_extent_m      integer,          -- rounded; null when locations are unknown
    isp_diversity             jsonb NOT NULL DEFAULT '{}'::jsonb,
    failure_modes             jsonb NOT NULL DEFAULT '{}'::jsonb,
    delayed_report_count      integer NOT NULL DEFAULT 0,
    correlation_window_s      integer NOT NULL,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX incidents_neighborhood_idx ON nw.incidents (neighborhood_id, started_at DESC);
CREATE INDEX incidents_open_idx ON nw.incidents (neighborhood_id) WHERE status = 'open';

CREATE TABLE nw.incident_households (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id     uuid NOT NULL REFERENCES nw.incidents (id) ON DELETE CASCADE,
    household_id    uuid NOT NULL REFERENCES nw.households (id) ON DELETE CASCADE,
    failure_mode    text NOT NULL,
    first_event_at  timestamptz NOT NULL,
    restored_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (incident_id, household_id)
);
CREATE INDEX incident_households_household_idx ON nw.incident_households (household_id);

CREATE TABLE nw.notification_tokens (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid NOT NULL REFERENCES nw.users (id) ON DELETE CASCADE,
    device_id         uuid NOT NULL REFERENCES nw.devices (id) ON DELETE CASCADE,
    provider          text NOT NULL CHECK (provider IN ('fcm', 'apns')),
    token             text NOT NULL,
    apns_environment  text CHECK (apns_environment IN ('sandbox', 'production')),
    status            text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'invalid')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, token),
    UNIQUE (device_id)
);

CREATE TABLE nw.notification_preferences (
    user_id              uuid PRIMARY KEY REFERENCES nw.users (id) ON DELETE CASCADE,
    household_alerts     boolean NOT NULL DEFAULT true,
    neighborhood_alerts  boolean NOT NULL DEFAULT true,
    quiet_hours_enabled  boolean NOT NULL DEFAULT false,
    quiet_hours_start    time NOT NULL DEFAULT '22:00',
    quiet_hours_end      time NOT NULL DEFAULT '07:00',
    timezone             text NOT NULL DEFAULT 'UTC',
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE nw.notification_deliveries (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id      uuid NOT NULL REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    incident_id          uuid REFERENCES nw.incidents (id) ON DELETE SET NULL,
    token_id             uuid REFERENCES nw.notification_tokens (id) ON DELETE SET NULL,
    user_id              uuid REFERENCES nw.users (id) ON DELETE SET NULL,
    provider             text NOT NULL,
    kind                 text NOT NULL CHECK (kind IN ('alert', 'escalation', 'resolution')),
    alert_level          smallint NOT NULL,
    dedupe_key           text NOT NULL UNIQUE,
    title                text NOT NULL,
    body                 text NOT NULL,
    data                 jsonb NOT NULL DEFAULT '{}'::jsonb,
    status               text NOT NULL DEFAULT 'pending',
    attempts             integer NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz,
    expires_at           timestamptz NOT NULL,
    last_error           text,
    provider_message_id  text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notification_deliveries_pending_idx ON nw.notification_deliveries (next_attempt_at) WHERE status = 'pending';
CREATE INDEX notification_deliveries_neighborhood_idx ON nw.notification_deliveries (neighborhood_id, created_at DESC);

CREATE TABLE nw.admin_audit_logs (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id  uuid REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    actor_user_id    uuid REFERENCES nw.users (id) ON DELETE SET NULL,
    actor_type       text NOT NULL DEFAULT 'admin' CHECK (actor_type IN ('admin', 'operator', 'system')),
    action           text NOT NULL,
    target_type      text,
    target_id        uuid,
    metadata         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_audit_logs_neighborhood_idx ON nw.admin_audit_logs (neighborhood_id, created_at DESC);

-- Abuse and security signals (rate-limit hits, rejected events, failed invitation attempts).
-- Holds identifiers only: never tokens, addresses or raw IP addresses (IPs are stored as salted hashes).
CREATE TABLE nw.security_events (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id  uuid REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    kind             text NOT NULL,
    user_id          uuid,
    device_id        uuid,
    household_id     uuid,
    detail           jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX security_events_created_idx ON nw.security_events (created_at);
CREATE INDEX security_events_device_idx ON nw.security_events (device_id, kind, created_at);

-- Detection thresholds and retention settings. neighborhood_id NULL = global default override.
CREATE TABLE nw.system_configuration (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id  uuid REFERENCES nw.neighborhoods (id) ON DELETE CASCADE,
    key              text NOT NULL,
    value            jsonb NOT NULL,
    updated_by       uuid REFERENCES nw.users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX system_configuration_key_idx
    ON nw.system_configuration (COALESCE(neighborhood_id, '00000000-0000-0000-0000-000000000000'::uuid), key);

-- Fixed-window counters for rate limits that must hold across processes and restarts.
CREATE TABLE nw.rate_limit_counters (
    bucket        text NOT NULL,
    window_start  timestamptz NOT NULL,
    count         integer NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (bucket, window_start)
);
CREATE INDEX rate_limit_counters_window_idx ON nw.rate_limit_counters (window_start);

-- updated_at triggers
DO $$
DECLARE t text;
BEGIN
    FOR t IN SELECT table_name FROM information_schema.columns
             WHERE table_schema = 'nw' AND column_name = 'updated_at'
    LOOP
        EXECUTE format('CREATE TRIGGER set_updated_at BEFORE UPDATE ON nw.%I FOR EACH ROW EXECUTE FUNCTION nw.set_updated_at()', t);
    END LOOP;
END $$;

-- ------------------------------------------------------------------ membership helpers for policies
-- SECURITY DEFINER so policies can consult membership without recursing into the policies themselves.
CREATE FUNCTION nw.my_household_ids_any() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = nw, pg_temp AS $$
    SELECT hm.household_id FROM nw.household_members hm
    WHERE hm.user_id = nw.current_user_id() AND hm.status = 'active'
$$;

CREATE FUNCTION nw.my_household_ids() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = nw, pg_temp AS $$
    SELECT hm.household_id
    FROM nw.household_members hm
    JOIN nw.households h ON h.id = hm.household_id
    JOIN nw.neighborhoods n ON n.id = h.neighborhood_id
    WHERE hm.user_id = nw.current_user_id() AND hm.status = 'active'
      AND h.membership_status = 'active' AND n.status = 'active'
$$;

CREATE FUNCTION nw.my_neighborhood_ids() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = nw, pg_temp AS $$
    SELECT h.neighborhood_id
    FROM nw.household_members hm
    JOIN nw.households h ON h.id = hm.household_id
    JOIN nw.neighborhoods n ON n.id = h.neighborhood_id
    WHERE hm.user_id = nw.current_user_id() AND hm.status = 'active'
      AND h.membership_status = 'active' AND n.status = 'active'
$$;

REVOKE ALL ON FUNCTION nw.my_household_ids_any(), nw.my_household_ids(), nw.my_neighborhood_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION nw.my_household_ids_any(), nw.my_household_ids(), nw.my_neighborhood_ids() TO nw_api;

-- ------------------------------------------------------------------ row-level security
-- Enabled on every table. With no policy a role sees nothing, so Supabase's "anon" and
-- "authenticated" roles (and any other role) have no access even if the schema were exposed.
DO $$
DECLARE t text;
BEGIN
    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'nw'
    LOOP
        EXECUTE format('ALTER TABLE nw.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY service_all ON nw.%I FOR ALL TO nw_service USING (true) WITH CHECK (true)', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON nw.%I TO nw_service', t);
    END LOOP;
END $$;

-- ---- nw_api: one signed-in resident
GRANT SELECT ON nw.users, nw.household_members, nw.households, nw.neighborhoods, nw.device_sessions,
                nw.connectivity_events, nw.incidents, nw.incident_households TO nw_api;
GRANT UPDATE (display_label, isp) ON nw.households TO nw_api;
GRANT SELECT, INSERT ON nw.devices TO nw_api;
GRANT UPDATE (os_version, app_version, capabilities, status, monitoring_enabled, last_state, last_event_at,
              last_seen_at, last_sequence) ON nw.devices TO nw_api;
GRANT INSERT, UPDATE ON nw.device_sessions TO nw_api;
GRANT INSERT ON nw.connectivity_events TO nw_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON nw.notification_tokens, nw.notification_preferences TO nw_api;

CREATE POLICY own_user ON nw.users FOR SELECT TO nw_api USING (id = nw.current_user_id());

CREATE POLICY own_membership ON nw.household_members FOR SELECT TO nw_api USING (user_id = nw.current_user_id());

CREATE POLICY own_household_read ON nw.households FOR SELECT TO nw_api
    USING (id IN (SELECT nw.my_household_ids_any()));
CREATE POLICY own_household_update ON nw.households FOR UPDATE TO nw_api
    USING (id IN (SELECT nw.my_household_ids())) WITH CHECK (id IN (SELECT nw.my_household_ids()));

CREATE POLICY own_neighborhood ON nw.neighborhoods FOR SELECT TO nw_api
    USING (id IN (SELECT nw.my_neighborhood_ids()));

CREATE POLICY own_devices_read ON nw.devices FOR SELECT TO nw_api USING (user_id = nw.current_user_id());
CREATE POLICY own_devices_insert ON nw.devices FOR INSERT TO nw_api
    WITH CHECK (user_id = nw.current_user_id() AND household_id IN (SELECT nw.my_household_ids()));
CREATE POLICY own_devices_update ON nw.devices FOR UPDATE TO nw_api
    USING (user_id = nw.current_user_id())
    WITH CHECK (user_id = nw.current_user_id() AND household_id IN (SELECT nw.my_household_ids()));

CREATE POLICY own_sessions ON nw.device_sessions FOR ALL TO nw_api
    USING (device_id IN (SELECT d.id FROM nw.devices d WHERE d.user_id = nw.current_user_id()))
    WITH CHECK (device_id IN (SELECT d.id FROM nw.devices d WHERE d.user_id = nw.current_user_id()));

-- A resident reads only their own household's raw telemetry, and can insert events only for an
-- active device they own, attributed to their own household and neighborhood.
CREATE POLICY own_events_read ON nw.connectivity_events FOR SELECT TO nw_api
    USING (household_id IN (SELECT nw.my_household_ids()));
CREATE POLICY own_events_insert ON nw.connectivity_events FOR INSERT TO nw_api
    WITH CHECK (
        household_id IN (SELECT nw.my_household_ids())
        AND neighborhood_id IN (SELECT nw.my_neighborhood_ids())
        AND device_id IN (SELECT d.id FROM nw.devices d
                          WHERE d.user_id = nw.current_user_id() AND d.status = 'active'
                            AND d.household_id = connectivity_events.household_id)
    );

-- Neighborhood incidents are visible to members of that neighborhood; household-scope incidents
-- only to the household concerned. incident_households rows are visible only for one's own household,
-- so residents can never learn which other households were affected.
CREATE POLICY own_incident_households ON nw.incident_households FOR SELECT TO nw_api
    USING (household_id IN (SELECT nw.my_household_ids()));
CREATE POLICY visible_incidents ON nw.incidents FOR SELECT TO nw_api
    USING (
        (scope = 'neighborhood' AND neighborhood_id IN (SELECT nw.my_neighborhood_ids()))
        OR (scope = 'household' AND id IN (SELECT ih.incident_id FROM nw.incident_households ih))
    );

CREATE POLICY own_tokens ON nw.notification_tokens FOR ALL TO nw_api
    USING (user_id = nw.current_user_id())
    WITH CHECK (user_id = nw.current_user_id()
                AND device_id IN (SELECT d.id FROM nw.devices d WHERE d.user_id = nw.current_user_id()));
CREATE POLICY own_preferences ON nw.notification_preferences FOR ALL TO nw_api
    USING (user_id = nw.current_user_id()) WITH CHECK (user_id = nw.current_user_id());

-- ---- nw_correlation: only what correlation needs. No addresses, e-mail, labels or push tokens.
GRANT SELECT (id, status) ON nw.neighborhoods TO nw_correlation;
GRANT SELECT (id, neighborhood_id, isp, latitude, longitude, membership_status) ON nw.households TO nw_correlation;
GRANT SELECT (id, household_id, platform, status, monitoring_enabled, last_state, last_event_at, last_seen_at)
    ON nw.devices TO nw_correlation;
GRANT SELECT ON nw.connectivity_events TO nw_correlation;
GRANT UPDATE (correlated_at, excluded_reason) ON nw.connectivity_events TO nw_correlation;
GRANT SELECT, INSERT, UPDATE ON nw.incident_candidates, nw.incidents, nw.incident_households TO nw_correlation;
GRANT SELECT ON nw.system_configuration TO nw_correlation;

CREATE POLICY correlation_read ON nw.neighborhoods FOR SELECT TO nw_correlation USING (true);
CREATE POLICY correlation_read ON nw.households FOR SELECT TO nw_correlation USING (true);
CREATE POLICY correlation_read ON nw.devices FOR SELECT TO nw_correlation USING (true);
CREATE POLICY correlation_read ON nw.connectivity_events FOR SELECT TO nw_correlation USING (true);
CREATE POLICY correlation_mark ON nw.connectivity_events FOR UPDATE TO nw_correlation USING (true) WITH CHECK (true);
CREATE POLICY correlation_all ON nw.incident_candidates FOR ALL TO nw_correlation USING (true) WITH CHECK (true);
CREATE POLICY correlation_all ON nw.incidents FOR ALL TO nw_correlation USING (true) WITH CHECK (true);
CREATE POLICY correlation_all ON nw.incident_households FOR ALL TO nw_correlation USING (true) WITH CHECK (true);
CREATE POLICY correlation_read ON nw.system_configuration FOR SELECT TO nw_correlation USING (true);
