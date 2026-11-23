-- No sign-in. A phone is enrolled by an invitation link and then identifies itself with its own
-- random secret. Nobody has an account, an e-mail address or a password.
-- See ai/docs/decisions/0014-no-sign-in-device-enrolment.md.

-- One row per enrolled phone. Only a SHA-256 hash of the secret is stored.
CREATE TABLE nw.device_credentials (
    device_id    uuid PRIMARY KEY REFERENCES nw.devices (id) ON DELETE CASCADE,
    secret_hash  text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER set_updated_at BEFORE UPDATE ON nw.device_credentials
    FOR EACH ROW EXECUTE FUNCTION nw.set_updated_at();
ALTER TABLE nw.device_credentials ENABLE ROW LEVEL SECURITY;
CREATE POLICY service_all ON nw.device_credentials FOR ALL TO nw_service USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON nw.device_credentials TO nw_service;

-- An invitation can enrol several phones (one link per home). It is finished when use_count reaches max_uses.
ALTER TABLE nw.invitations
    ADD COLUMN max_uses   integer NOT NULL DEFAULT 1 CHECK (max_uses BETWEEN 1 AND 20),
    ADD COLUMN use_count  integer NOT NULL DEFAULT 0 CHECK (use_count >= 0);

-- Which invitation enrolled a phone: lets a retried enrolment be recognised without using the link twice.
ALTER TABLE nw.devices ADD COLUMN invitation_id uuid REFERENCES nw.invitations (id) ON DELETE SET NULL;

-- The house number is optional. Phones enrolled without one are grouped into a household by the
-- invitation link they used, so a home that shares one link still counts as one household.
ALTER TABLE nw.households ALTER COLUMN normalized_house_number DROP NOT NULL;
ALTER TABLE nw.households ADD COLUMN invitation_id uuid REFERENCES nw.invitations (id) ON DELETE SET NULL;
CREATE UNIQUE INDEX households_unnumbered_per_invitation
    ON nw.households (invitation_id, normalized_street_name) WHERE normalized_house_number IS NULL;

-- No accounts: the identity columns go. A row in nw.users now stands for one enrolled phone.
ALTER TABLE nw.users DROP COLUMN email, DROP COLUMN auth_subject;
