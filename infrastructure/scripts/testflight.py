#!/usr/bin/env python3
"""After an upload to App Store Connect: wait for processing, then hand the build to TestFlight testers.

    ASC_KEY_ID=... ASC_ISSUER_ID=... ASC_PRIVATE_KEY="$(cat AuthKey.p8)" \
      testflight.py --version 1.0.0 --build 123456 [--group Neighbors] [--whats-new "..."] [--submit-review]

Internal groups with access to all builds (such as "Owner") get every processed build without this
script. An external group (residents) gets a build only when it is added to the group and, for the
first build of a version, after Apple's beta review; --submit-review asks for that review.
Needs: pyjwt[crypto]. Uses only the official App Store Connect API.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

import jwt

API = "https://api.appstoreconnect.apple.com"
BUNDLE_ID = os.environ.get("IOS_BUNDLE_ID", "app.feluda")


def token() -> str:
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 1100, "aud": "appstoreconnect-v1"},
        os.environ["ASC_PRIVATE_KEY"],
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


def call(method: str, path: str, body: dict | None = None, ok: tuple[int, ...] = ()) -> dict:
    request = urllib.request.Request(
        API + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response) if response.status != 204 else {}
    except urllib.error.HTTPError as error:
        text = error.read().decode()[:1000]
        if error.code in ok:
            print(f"{method} {path.split('?')[0]}: {error.code} accepted ({text[:200]})")
            return {}
        sys.exit(f"{method} {path.split('?')[0]} failed with {error.code}: {text}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True, help="marketing version, e.g. 1.0.0")
    parser.add_argument("--build", required=True, help="build number (CFBundleVersion)")
    parser.add_argument("--group", help="TestFlight group to add the build to (external testers)")
    parser.add_argument("--whats-new", help="'What to test' text shown in TestFlight")
    parser.add_argument("--submit-review", action="store_true", help="submit for beta app review")
    parser.add_argument("--wait-minutes", type=int, default=45)
    args = parser.parse_args()

    apps = call("GET", f"/v1/apps?filter[bundleId]={BUNDLE_ID}")["data"]
    if not apps:
        sys.exit(f"No App Store Connect app with bundle id {BUNDLE_ID}")
    app_id = apps[0]["id"]

    deadline = time.time() + args.wait_minutes * 60
    build = None
    while time.time() < deadline:
        found = call(
            "GET",
            f"/v1/builds?filter[app]={app_id}&filter[version]={args.build}"
            f"&filter[preReleaseVersion.version]={args.version}&limit=1",
        )["data"]
        state = found[0]["attributes"]["processingState"] if found else "NOT_YET_VISIBLE"
        print(f"build {args.version} ({args.build}): {state}", flush=True)
        if state == "VALID":
            build = found[0]
            break
        if state in ("FAILED", "INVALID"):
            sys.exit("App Store Connect rejected the build during processing")
        time.sleep(30)
    if build is None:
        sys.exit("Timed out waiting for App Store Connect to process the build")
    build_id = build["id"]

    if args.whats_new:
        existing = call("GET", f"/v1/builds/{build_id}/betaBuildLocalizations")["data"]
        if existing:
            call(
                "PATCH",
                f"/v1/betaBuildLocalizations/{existing[0]['id']}",
                {"data": {"type": "betaBuildLocalizations", "id": existing[0]["id"], "attributes": {"whatsNew": args.whats_new}}},
            )
        else:
            call(
                "POST",
                "/v1/betaBuildLocalizations",
                {
                    "data": {
                        "type": "betaBuildLocalizations",
                        "attributes": {"locale": "en-US", "whatsNew": args.whats_new},
                        "relationships": {"build": {"data": {"type": "builds", "id": build_id}}},
                    }
                },
            )

    if args.group:
        groups = call("GET", f"/v1/apps/{app_id}/betaGroups?limit=200")["data"]
        group = next((g for g in groups if g["attributes"]["name"] == args.group), None)
        if group is None:
            sys.exit(f"TestFlight group '{args.group}' does not exist")
        call("POST", f"/v1/betaGroups/{group['id']}/relationships/builds", {"data": [{"type": "builds", "id": build_id}]})
        print(f"added to group {args.group}")

    if args.submit_review:
        # 409/422: already submitted or not needed (a later build of an approved version).
        call(
            "POST",
            "/v1/betaAppReviewSubmissions",
            {"data": {"type": "betaAppReviewSubmissions", "relationships": {"build": {"data": {"type": "builds", "id": build_id}}}}},
            ok=(409, 422),
        )
        print("submitted for beta app review (or already approved)")


if __name__ == "__main__":
    main()
