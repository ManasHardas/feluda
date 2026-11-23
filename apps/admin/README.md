# Administrator tooling

There is no administrator web interface. Administration is done through the documented
administrator API (`docs/API.md`), either directly or with the small command-line client in this
folder. Reasons: the pilot has one or two organizers, a web console would be one more thing to
secure, and every administrator action is audited by the API regardless of the client.

`nwadmin.py` uses only the Python standard library (3.10 or later).

There is no sign-in. The tool sends the deployment's operator secret (the backend's
`ADMIN_API_TOKEN`) as a bearer token. It reads it from the environment, never from the command
line, where it would end up in shell history, and it stores nothing on disk.

```bash
# Local development (backend from `make run`; the Makefile sets this placeholder secret)
export NWADMIN_API=http://localhost:8000
export NWADMIN_TOKEN=local-development-admin-token-not-for-production

# A deployed environment
export NWADMIN_API=https://api.example.org
read -rs NWADMIN_TOKEN && export NWADMIN_TOKEN        # paste the operator secret

python apps/admin/nwadmin.py summary
python apps/admin/nwadmin.py invite                             # a private link for one home, up to 4 phones, 72 hours
python apps/admin/nwadmin.py invite --phones 2 --hours 24
python apps/admin/nwadmin.py invite --household <household id>  # more phones for a home that already takes part
python apps/admin/nwadmin.py invitations
python apps/admin/nwadmin.py invite-revoke <invitation id>
python apps/admin/nwadmin.py households
python apps/admin/nwadmin.py household-add --number 14 --street "Oak Ridge Road" --isp att --lat 33.0501 --lon -97.0502
python apps/admin/nwadmin.py suspend <household id> --reason "moved away"
python apps/admin/nwadmin.py device-revoke <device id> --reason "lost phone"
python apps/admin/nwadmin.py incidents
python apps/admin/nwadmin.py config
python apps/admin/nwadmin.py config-set min_affected_households=4 correlation_radius_m=400
python apps/admin/nwadmin.py audit
```

`household-add` is optional. Enrolment creates households by itself; adding one by hand is only
needed to give it coordinates for the distance check (`docs/ONBOARDING.md`).

When a deployment has more than one neighborhood, name the one you mean with
`--neighborhood <uuid>` or `NWADMIN_NEIGHBORHOOD`. With exactly one it is not needed.

Things to know about the operator secret:

- It is one shared secret for the whole deployment, every neighborhood included. There are no
  per-person administrator accounts and no second factor, and the audit log shows what was done
  but not by whom.
- Keep it in a password manager. Do not put it in a file in this repository or in a chat.
- If it may have leaked, rotate it (`docs/OPERATIONS.md`, "Rotate the operator secret").
- If the backend's `ADMIN_API_TOKEN` is empty, the administrator API is switched off and every
  command here answers 401.

The tool refuses to send the secret over plain HTTP except to localhost.

What the organizer can and cannot see is described in `docs/PRIVACY.md`. Creating or disabling
a neighborhood is an operator task (`docs/OPERATIONS.md`).

Status: IMPLEMENTED. It was exercised only as far as the backend's automated tests cover the same
API endpoints; the script itself has no automated tests and has not been run against a deployment.
