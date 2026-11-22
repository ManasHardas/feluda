"""Push providers: Firebase Cloud Messaging (HTTP v1) for Android and APNs (HTTP/2) for iOS.

"Sent" means the provider accepted the message. Neither provider confirms arrival on the phone, and
a phone without connectivity will not receive anything until it reconnects (or the message expires).
A provider without credentials reports itself as not configured; nothing is ever pretended.
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, field
from typing import Protocol

import httpx
import jwt

from ..enums import ApnsEnvironment

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
APNS_HOSTS = {
    ApnsEnvironment.PRODUCTION.value: "https://api.push.apple.com",
    ApnsEnvironment.SANDBOX.value: "https://api.sandbox.push.apple.com",
}


@dataclass(frozen=True)
class PushMessage:
    title: str
    body: str
    data: dict[str, str]
    alert_level: int
    expires_at_epoch: int
    collapse_key: str


@dataclass(frozen=True)
class SendResult:
    ok: bool
    message_id: str | None = None
    error: str | None = None
    invalid_token: bool = False
    retryable: bool = False


class PushSender(Protocol):
    @property
    def configured(self) -> bool: ...

    def send(self, token: str, message: PushMessage, apns_environment: str | None = None) -> SendResult: ...


def _short(text: str) -> str:
    return text[:300]


@dataclass
class FcmSender:
    project_id: str
    service_account_json: str
    client: httpx.Client = field(default_factory=lambda: httpx.Client(timeout=10))
    _access_token: str = ""
    _access_token_expiry: float = 0.0

    @property
    def configured(self) -> bool:
        return bool(self.project_id and self.service_account_json)

    def _token(self) -> str:
        if self._access_token and time.time() < self._access_token_expiry - 60:
            return self._access_token
        account = json.loads(self.service_account_json)
        now = int(time.time())
        assertion = jwt.encode(
            {"iss": account["client_email"], "scope": FCM_SCOPE, "aud": account["token_uri"], "iat": now, "exp": now + 3600},
            account["private_key"],
            algorithm="RS256",
        )
        response = self.client.post(
            account["token_uri"],
            data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion},
        )
        response.raise_for_status()
        payload = response.json()
        self._access_token = payload["access_token"]
        self._access_token_expiry = time.time() + int(payload.get("expires_in", 3600))
        return self._access_token

    def send(self, token: str, message: PushMessage, apns_environment: str | None = None) -> SendResult:
        ttl = max(0, message.expires_at_epoch - int(time.time()))
        high = message.alert_level >= 2
        body = {
            "message": {
                "token": token,
                "notification": {"title": message.title, "body": message.body},
                "data": message.data,
                "android": {
                    "priority": "HIGH" if high else "NORMAL",
                    "ttl": f"{ttl}s",
                    "collapse_key": message.collapse_key,
                    "notification": {"channel_id": "neighborhood_alerts" if high else "household_warnings"},
                },
            }
        }
        try:
            response = self.client.post(
                f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send",
                headers={"Authorization": f"Bearer {self._token()}"},
                json=body,
            )
        except (httpx.HTTPError, KeyError, ValueError) as exc:
            return SendResult(ok=False, error=_short(f"transport: {type(exc).__name__}"), retryable=True)
        if response.status_code == 200:
            return SendResult(ok=True, message_id=response.json().get("name"))
        status = ""
        try:
            status = response.json().get("error", {}).get("status", "")
        except ValueError:
            pass
        invalid = (
            response.status_code == 404
            or status in ("UNREGISTERED", "NOT_FOUND")
            or (response.status_code == 400 and status == "INVALID_ARGUMENT")
        )
        if response.status_code == 401:
            self._access_token = ""
        return SendResult(
            ok=False,
            error=_short(f"fcm {response.status_code} {status}"),
            invalid_token=invalid,
            retryable=response.status_code in (401, 429) or response.status_code >= 500,
        )


@dataclass
class ApnsSender:
    key_id: str
    team_id: str
    private_key: str
    bundle_id: str
    client: httpx.Client = field(default_factory=lambda: httpx.Client(http2=True, timeout=10))
    _jwt: str = ""
    _jwt_issued: float = 0.0

    @property
    def configured(self) -> bool:
        return bool(self.key_id and self.team_id and self.private_key and self.bundle_id)

    def _provider_token(self) -> str:
        if self._jwt and time.time() - self._jwt_issued < 45 * 60:  # APNs accepts a token for up to one hour
            return self._jwt
        self._jwt_issued = time.time()
        self._jwt = jwt.encode(
            {"iss": self.team_id, "iat": int(self._jwt_issued)}, self.private_key, algorithm="ES256", headers={"kid": self.key_id}
        )
        return self._jwt

    def send(self, token: str, message: PushMessage, apns_environment: str | None = None) -> SendResult:
        host = APNS_HOSTS.get(apns_environment or ApnsEnvironment.PRODUCTION.value, APNS_HOSTS["production"])
        high = message.alert_level >= 2
        payload = {
            "aps": {
                "alert": {"title": message.title, "body": message.body},
                "sound": "default",
                "thread-id": message.collapse_key,
                # "time-sensitive" may break through Focus if the resident allows it. Critical alerts need a
                # special entitlement from Apple and are not used.
                "interruption-level": "time-sensitive" if high else "active",
            },
            **message.data,
        }
        try:
            response = self.client.post(
                f"{host}/3/device/{token}",
                headers={
                    "authorization": f"bearer {self._provider_token()}",
                    "apns-topic": self.bundle_id,
                    "apns-push-type": "alert",
                    "apns-priority": "10" if high else "5",
                    "apns-expiration": str(message.expires_at_epoch),
                    "apns-collapse-id": message.collapse_key[:64],
                },
                json=payload,
            )
        except (httpx.HTTPError, ValueError) as exc:
            return SendResult(ok=False, error=_short(f"transport: {type(exc).__name__}"), retryable=True)
        if response.status_code == 200:
            return SendResult(ok=True, message_id=response.headers.get("apns-id"))
        reason = ""
        try:
            reason = response.json().get("reason", "")
        except ValueError:
            pass
        invalid = response.status_code == 410 or reason in ("BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic")
        if reason == "ExpiredProviderToken":
            self._jwt = ""
        return SendResult(
            ok=False,
            error=_short(f"apns {response.status_code} {reason}"),
            invalid_token=invalid,
            retryable=response.status_code == 429 or response.status_code >= 500 or reason == "ExpiredProviderToken",
        )


def build_senders(settings) -> dict[str, PushSender]:
    return {
        "fcm": FcmSender(settings.fcm_project_id, settings.fcm_service_account_json),
        "apns": ApnsSender(settings.apns_key_id, settings.apns_team_id, settings.apns_private_key, settings.apns_bundle_id),
    }
