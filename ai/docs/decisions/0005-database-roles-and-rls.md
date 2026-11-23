# 0005 Three database roles and row-level security as a second line of defence

**Note (2026-11-23).** See 0014: there is no sign-in any more. "Signed-in resident" below now
means one enrolled phone (a row in `nw.users`), "account" its enrolment record, and the
"administrator" is the holder of the operator secret, who may name any neighborhood of the
deployment. The roles and policies themselves are unchanged.

**Decision.** The backend's login role has no privileges of its own. Every transaction starts with
`SET LOCAL ROLE` to one of:

- `nw_api`: acts for one signed-in resident. Row-level security policies use the user id passed in
  the `app.user_id` setting. Reads own account, household, devices, events and visible incidents;
  inserts events only for an own active device in the own household and neighborhood; cannot touch
  membership, roles, invitations, configuration or audit data.
- `nw_correlation`: column-limited. Reads coordinates, provider and status of households and all
  events; writes incidents. Cannot read addresses, labels, e-mail addresses, tokens or invitations.
- `nw_service`: trusted operations (registration, administration, notifications, retention).

Row-level security is enabled on every table. Tables live in schema `nw`, which Supabase's REST
API does not expose, and no policy exists for Supabase's `anon` or `authenticated` roles.

**Why.** Authorization is checked in the API code, but a bug there should not be enough to leak
another household's data. Tests connect as each role directly and try the forbidden operations.

**Trade-off.** Administrator endpoints check the admin role in code and then run as `nw_service`,
scoped by the administrator's own neighborhood id. Writing administrator policies as well would
have doubled the policy surface for little gain; cross-neighborhood admin access is covered by tests.

**Revisit when.** A second backend or direct client-to-database access is introduced.
