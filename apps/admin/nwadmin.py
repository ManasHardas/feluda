#!/usr/bin/env python3
"""Command-line client for the Neighborhood Watchdog administrator API.

Standard library only, so it runs anywhere Python 3.10+ is installed.

    python nwadmin.py --api https://api.example.org --auth https://<ref>.supabase.co/auth/v1 --key <publishable key> login
    python nwadmin.py households
    python nwadmin.py invite --household <id>

`login` asks for your e-mail address and the one-time code sent to it, then stores the session in
~/.config/nwadmin/session.json (readable only by you). Run `nwadmin.py logout` to remove it.
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

SESSION = Path(os.environ.get("NWADMIN_SESSION", Path.home() / ".config" / "nwadmin" / "session.json"))


def call(method: str, url: str, body: dict | None = None, headers: dict | None = None) -> tuple[int, dict | list]:
    if not url.startswith(("https://", "http://localhost", "http://127.0.0.1")):
        raise SystemExit("Refusing to send credentials over plain HTTP to a non-local address.")
    request = urllib.request.Request(  # noqa: S310 - scheme checked above
        url, method=method, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **(headers or {})},
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:  # noqa: S310
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, {"error": {"code": "http_error", "message": raw.decode(errors="replace")[:200]}}
    except urllib.error.URLError as error:
        raise SystemExit(f"Could not reach {url}: {error.reason}") from error


def load() -> dict:
    if not SESSION.exists():
        raise SystemExit("Not signed in. Run: nwadmin.py --api <url> --auth <url> --key <key> login")
    return json.loads(SESSION.read_text())


def save(session: dict) -> None:
    SESSION.parent.mkdir(parents=True, exist_ok=True)
    SESSION.touch(mode=0o600, exist_ok=True)
    os.chmod(SESSION, 0o600)
    SESSION.write_text(json.dumps(session))


def token(session: dict) -> str:
    if session["expires_at"] - time.time() < 60:
        status, data = call("POST", f"{session['auth']}/token?grant_type=refresh_token",
                            {"refresh_token": session["refresh_token"]}, {"apikey": session["key"]})
        if status != 200:
            raise SystemExit("Your session expired. Run `nwadmin.py login` again.")
        session.update(access_token=data["access_token"], refresh_token=data["refresh_token"],
                       expires_at=time.time() + data["expires_in"])
        save(session)
    return session["access_token"]


def api(method: str, path: str, body: dict | None = None):
    session = load()
    status, data = call(method, f"{session['api']}/api/v1{path}", body, {"Authorization": f"Bearer {token(session)}"})
    if status >= 400:
        error = data.get("error", {}) if isinstance(data, dict) else {}
        raise SystemExit(f"Error {status} {error.get('code', '')}: {error.get('message', data)}")
    return data


def table(rows: list[dict], columns: list[str]) -> None:
    if not rows:
        print("(none)")
        return
    widths = [max(len(c), *(len(str(r.get(c, ""))) for r in rows)) for c in columns]
    print("  ".join(c.ljust(w) for c, w in zip(columns, widths, strict=True)))
    for row in rows:
        print("  ".join(str(row.get(c, "")).ljust(w) for c, w in zip(columns, widths, strict=True)))


def main() -> int:
    parser = argparse.ArgumentParser(description="Neighborhood Watchdog administrator tool")
    parser.add_argument("--api", help="backend origin, e.g. https://api.example.org")
    parser.add_argument("--auth", help="auth origin ending in /auth/v1 (defaults to <api>/auth/v1 for local development)")
    parser.add_argument("--key", default="", help="identity provider publishable key (not needed locally)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("login")
    sub.add_parser("logout")
    sub.add_parser("summary")
    sub.add_parser("households")
    add = sub.add_parser("household-add")
    add.add_argument("--number", required=True)
    add.add_argument("--street", required=True)
    add.add_argument("--isp", default="unknown")
    add.add_argument("--lat", type=float)
    add.add_argument("--lon", type=float)
    for name in ("suspend", "reinstate", "remove"):
        action = sub.add_parser(name)
        action.add_argument("household_id")
        action.add_argument("--reason")
    invite = sub.add_parser("invite")
    invite.add_argument("--household")
    invite.add_argument("--hours", type=int, default=72)
    invite.add_argument("--admin", action="store_true", help="the invited person becomes an administrator")
    sub.add_parser("invitations")
    revoke = sub.add_parser("invite-revoke")
    revoke.add_argument("invitation_id")
    device = sub.add_parser("device-revoke")
    device.add_argument("device_id")
    device.add_argument("--reason")
    sub.add_parser("incidents")
    sub.add_parser("export")
    sub.add_parser("failures")
    sub.add_parser("audit")
    sub.add_parser("config")
    config_set = sub.add_parser("config-set")
    config_set.add_argument("pairs", nargs="+", metavar="key=value")
    args = parser.parse_args()

    if args.command == "login":
        if not args.api:
            raise SystemExit("--api is required for login")
        api_base = args.api.rstrip("/")
        auth = (args.auth or f"{api_base}/auth/v1").rstrip("/")
        email = input("E-mail address: ").strip().lower()
        status, data = call("POST", f"{auth}/otp", {"email": email, "create_user": False}, {"apikey": args.key})
        if status >= 400:
            raise SystemExit(f"Could not request a code: {data}")
        code = getpass.getpass("One-time code from the e-mail (hidden): ").strip()
        status, data = call("POST", f"{auth}/verify", {"type": "email", "email": email, "token": code}, {"apikey": args.key})
        if status != 200:
            raise SystemExit("The code was not accepted.")
        save({"api": api_base, "auth": auth, "key": args.key, "access_token": data["access_token"],
              "refresh_token": data["refresh_token"], "expires_at": time.time() + data["expires_in"]})
        me = api("GET", "/households/me")
        print(f"Signed in. Role: {me['role']}.")
        if me["role"] != "admin":
            print("This account is not an administrator; admin commands will be refused.")
        return 0
    if args.command == "logout":
        SESSION.unlink(missing_ok=True)
        print("Signed out.")
        return 0

    if args.command == "summary":
        print(json.dumps(api("GET", "/admin/neighborhood/summary"), indent=2))
    elif args.command == "households":
        table(api("GET", "/admin/households"),
              ["household_id", "house_number", "street_name", "isp", "membership_status", "member_count", "device_count", "has_location"])
    elif args.command == "household-add":
        body = {"house_number": args.number, "street_name": args.street, "isp": args.isp}
        if args.lat is not None and args.lon is not None:
            body.update(latitude=args.lat, longitude=args.lon)
        created = api("POST", "/admin/households", body)
        print(f"Approved {created['house_number']} {created['street_name']}  id {created['household_id']}")
    elif args.command in ("suspend", "reinstate", "remove"):
        if args.command == "remove" and input("This deletes the household's devices and events. Type 'remove' to continue: ") != "remove":
            return 1
        api("POST", f"/admin/households/{args.command}", {"household_id": args.household_id, **({"reason": args.reason} if args.reason else {})})
        print("Done.")
    elif args.command == "invite":
        body = {"expires_in_hours": args.hours, "role": "admin" if args.admin else "resident"}
        if args.household:
            body["household_id"] = args.household
        created = api("POST", "/admin/invitations", body)
        print("Invitation link (shown only now; send it privately to one resident):\n")
        print(f"  {created['invitation_url']}\n")
        print(f"Expires {created['expires_at']}. Id {created['invitation_id']}.")
    elif args.command == "invitations":
        table(api("GET", "/admin/invitations"), ["invitation_id", "status", "household_bound", "role", "expires_at", "used_at"])
    elif args.command == "invite-revoke":
        api("POST", "/admin/invitations/revoke", {"invitation_id": args.invitation_id})
        print("Revoked.")
    elif args.command == "device-revoke":
        api("POST", "/admin/devices/revoke", {"device_id": args.device_id, **({"reason": args.reason} if args.reason else {})})
        print("Revoked.")
    elif args.command == "incidents":
        table(api("GET", "/admin/incidents")["incidents"],
              ["started_at", "scope", "classification", "alert_level", "status", "affected_household_count"])
    elif args.command == "export":
        json.dump(api("GET", "/admin/incidents/export"), sys.stdout, indent=2)
        print()
    elif args.command == "failures":
        table(api("GET", "/admin/notifications/failures"), ["created_at", "provider", "status", "attempts", "last_error"])
    elif args.command == "audit":
        table(api("GET", "/admin/audit-logs"), ["created_at", "actor_reference", "action", "target_type", "target_id"])
    elif args.command == "config":
        data = api("GET", "/admin/configuration")
        for key in sorted(data["values"]):
            marker = "" if data["values"][key] == data["defaults"][key] else f"   (default {data['defaults'][key]})"
            print(f"{key} = {data['values'][key]}{marker}")
    elif args.command == "config-set":
        values = {}
        for pair in args.pairs:
            key, _, raw = pair.partition("=")
            values[key] = float(raw) if "." in raw else int(raw)
        session = load()
        status, data = call("PUT", f"{session['api']}/api/v1/admin/configuration", {"values": values},
                            {"Authorization": f"Bearer {token(session)}"})
        if status >= 400:
            raise SystemExit(f"Refused: {data.get('error', {}).get('message', data)}")
        print("Updated:", ", ".join(f"{k}={data['values'][k]}" for k in values))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
