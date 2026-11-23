#!/usr/bin/env python3
"""Command-line client for the Neighborhood Watchdog administrator API.

Standard library only, so it runs anywhere Python 3.10+ is installed.

    export NWADMIN_API=https://api.example.org
    export NWADMIN_TOKEN=...        # the ADMIN_API_TOKEN secret of that deployment
    python nwadmin.py invite        # prints a private link to send to one home
    python nwadmin.py households

There is no sign-in. The administrator secret is read from the environment (never from the command
line, where it would end up in shell history). With more than one neighborhood on a deployment,
name it with --neighborhood or NWADMIN_NEIGHBORHOOD.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

_OPTIONS: dict[str, str] = {}


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


def api(method: str, path: str, body: dict | None = None):
    base = _OPTIONS.get("api") or os.environ.get("NWADMIN_API", "")
    secret = os.environ.get("NWADMIN_TOKEN", "")
    if not base:
        raise SystemExit("Set NWADMIN_API (or pass --api) to the backend origin, e.g. https://api.example.org")
    if not secret:
        raise SystemExit("Set NWADMIN_TOKEN to the deployment's administrator secret (ADMIN_API_TOKEN).")
    headers = {"Authorization": f"Bearer {secret}"}
    neighborhood = _OPTIONS.get("neighborhood") or os.environ.get("NWADMIN_NEIGHBORHOOD", "")
    if neighborhood:
        headers["X-Neighborhood-Id"] = neighborhood
    status, data = call(method, f"{base.rstrip('/')}/api/v1{path}", body, headers)
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
    parser.add_argument("--api", help="backend origin, e.g. https://api.example.org (or set NWADMIN_API)")
    parser.add_argument("--neighborhood", help="neighborhood id, needed only when the deployment has several")
    sub = parser.add_subparsers(dest="command", required=True)
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
    invite.add_argument("--household", help="add phones to this existing household")
    invite.add_argument("--hours", type=int, default=72)
    invite.add_argument("--phones", type=int, default=4, help="how many phones this link can enrol (default 4)")
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

    _OPTIONS.update({k: v for k, v in (("api", args.api), ("neighborhood", args.neighborhood)) if v})

    if args.command == "summary":
        print(json.dumps(api("GET", "/admin/neighborhood/summary"), indent=2))
    elif args.command == "households":
        table(api("GET", "/admin/households"),
              ["household_id", "house_number", "street_name", "isp", "membership_status", "device_count", "has_location"])
    elif args.command == "household-add":
        body = {"house_number": args.number, "street_name": args.street, "isp": args.isp}
        if args.lat is not None and args.lon is not None:
            body.update(latitude=args.lat, longitude=args.lon)
        created = api("POST", "/admin/households", body)
        print(f"Added {created['house_number']} {created['street_name']}  id {created['household_id']}")
    elif args.command in ("suspend", "reinstate", "remove"):
        if args.command == "remove" and input("This deletes the household's devices and events. Type 'remove' to continue: ") != "remove":
            return 1
        api("POST", f"/admin/households/{args.command}", {"household_id": args.household_id, **({"reason": args.reason} if args.reason else {})})
        print("Done.")
    elif args.command == "invite":
        body = {"expires_in_hours": args.hours, "max_uses": args.phones}
        if args.household:
            body["household_id"] = args.household
        created = api("POST", "/admin/invitations", body)
        print("Invitation link (shown only now; send it privately to one home):\n")
        print(f"  {created['invitation_url']}\n")
        print(f"Enrols up to {created['max_uses']} phone(s). Expires {created['expires_at']}. Id {created['invitation_id']}.")
    elif args.command == "invitations":
        table(api("GET", "/admin/invitations"), ["invitation_id", "status", "household_bound", "use_count", "max_uses", "expires_at", "used_at"])
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
        data = api("PUT", "/admin/configuration", {"values": values})
        print("Updated:", ", ".join(f"{k}={data['values'][k]}" for k in values))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
