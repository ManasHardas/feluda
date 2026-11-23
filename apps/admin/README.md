# Administrator tooling

There is no administrator web interface. Administration is done through the documented
administrator API (`docs/API.md`), either directly or with the small command-line client in this
folder. Reasons: the pilot has one or two organizers, a web console would be one more thing to
secure, and every administrator action is audited by the API regardless of the client.

`nwadmin.py` uses only the Python standard library (3.10 or later).

```bash
# Local development (backend from `make run`; the code is printed in the backend log)
python apps/admin/nwadmin.py --api http://localhost:8000 login

# A deployed environment
python apps/admin/nwadmin.py --api https://api.example.org \
  --auth https://<project-ref>.supabase.co/auth/v1 --key <publishable key> login

python apps/admin/nwadmin.py summary
python apps/admin/nwadmin.py household-add --number 14 --street "Oak Ridge Road" --isp att --lat 33.0501 --lon -97.0502
python apps/admin/nwadmin.py households
python apps/admin/nwadmin.py invite --household <household id>
python apps/admin/nwadmin.py invitations
python apps/admin/nwadmin.py suspend <household id> --reason "moved away"
python apps/admin/nwadmin.py incidents
python apps/admin/nwadmin.py config
python apps/admin/nwadmin.py config-set min_affected_households=4 correlation_radius_m=400
python apps/admin/nwadmin.py audit
python apps/admin/nwadmin.py logout
```

The session (access and refresh token) is stored in `~/.config/nwadmin/session.json` with
owner-only permissions. Sign out on shared computers. The tool refuses to send credentials over
plain HTTP except to localhost.

What an administrator can and cannot see is described in `docs/PRIVACY.md`. Creating or disabling
a neighborhood is an operator task (`docs/OPERATIONS.md`).

Status: IMPLEMENTED. It was exercised only as far as the backend's automated tests cover the same
API endpoints; the script itself has no automated tests and has not been run against a deployment.
