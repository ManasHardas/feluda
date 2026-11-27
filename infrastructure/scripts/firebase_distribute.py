#!/usr/bin/env python3
"""Upload an APK to Firebase App Distribution and send it to tester groups.

    FIREBASE_SERVICE_ACCOUNT_JSON="$(cat sa.json)" FIREBASE_ANDROID_APP_ID=1:...:android:... \
      firebase_distribute.py app-release.apk --groups owner --notes "..."

The project number is taken from the app id. Needs: pyjwt[crypto]. Uses only the official
Firebase App Distribution REST API, with a service account that has App Distribution admin rights.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

BASE = "https://firebaseappdistribution.googleapis.com"


def access_token() -> str:
    account = json.loads(os.environ["FIREBASE_SERVICE_ACCOUNT_JSON"])
    now = int(time.time())
    assertion = jwt.encode(
        {
            "iss": account["client_email"],
            "scope": "https://www.googleapis.com/auth/cloud-platform",
            "aud": account["token_uri"],
            "iat": now,
            "exp": now + 3600,
        },
        account["private_key"],
        algorithm="RS256",
    )
    body = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion}).encode()
    with urllib.request.urlopen(account["token_uri"], body, timeout=60) as response:
        return json.load(response)["access_token"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--groups", required=True, help="comma-separated group aliases, e.g. owner or neighbors")
    parser.add_argument("--notes", default="", help="release notes shown to testers")
    args = parser.parse_args()

    app_id = os.environ["FIREBASE_ANDROID_APP_ID"]
    project_number = app_id.split(":")[1]
    token = access_token()

    def request(method: str, url: str, data: bytes | None = None, headers: dict | None = None) -> dict:
        req = urllib.request.Request(url, data=data, method=method, headers={"Authorization": "Bearer " + token, **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=300) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            sys.exit(f"{method} {url.split('?')[0]} failed with {error.code}: {error.read().decode()[:800]}")

    with open(args.apk, "rb") as apk:
        operation = request(
            "POST",
            f"{BASE}/upload/v1/projects/{project_number}/apps/{app_id}/releases:upload",
            apk.read(),
            {"X-Goog-Upload-Protocol": "raw", "X-Goog-Upload-File-Name": os.path.basename(args.apk), "Content-Type": "application/octet-stream"},
        )
    for _ in range(100):
        if operation.get("done"):
            break
        time.sleep(3)
        operation = request("GET", f"{BASE}/v1/{operation['name']}")
    if not operation.get("done") or operation.get("error"):
        sys.exit(f"upload did not finish: {operation.get('error')}")
    release = operation["response"]["release"]
    print(f"{operation['response'].get('result')}: {release['displayVersion']} ({release['buildVersion']})")

    if args.notes:
        request(
            "PATCH",
            f"{BASE}/v1/{release['name']}?updateMask=release_notes.text",
            json.dumps({"releaseNotes": {"text": args.notes}}).encode(),
            {"Content-Type": "application/json"},
        )
    groups = [g.strip() for g in args.groups.split(",") if g.strip()]
    request("POST", f"{BASE}/v1/{release['name']}:distribute", json.dumps({"groupAliases": groups}).encode(), {"Content-Type": "application/json"})
    print(f"distributed to {', '.join(groups)}")


if __name__ == "__main__":
    main()
