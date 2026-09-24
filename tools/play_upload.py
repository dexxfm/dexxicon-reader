#!/usr/bin/env python3
"""
issue #303: upload a release bundle (.aab) to a Google Play track through the Play
Developer API (androidpublisher v3). This replaces uploading by hand in the Play Console.

Auth is a Google Cloud service account that has been granted release permissions for the
app in Play Console (Users and permissions). Its JSON key stays outside the repo. The
script reads the key's path from --key, else $PLAY_SERVICE_ACCOUNT_JSON, else
~/.config/dexxicon/play-service-account.json. It never prints the key.

Uses only `requests` and `cryptography` (a service-account JWT, signed locally), so the
Google client libraries aren't required.

  python tools/play_upload.py --list-tracks
  python tools/play_upload.py --aab app/build/outputs/bundle/release/dexxicon-reader-1.3.0.aab \
      --track alpha --name 1.3.0 --notes-file notes.txt [--draft] [--validate-only]
"""
import argparse
import base64
import json
import os
import sys
import time
from pathlib import Path

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

PACKAGE = "com.dexxfm.dexxicon_reader"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
DEFAULT_KEY = Path.home() / ".config" / "dexxicon" / "play-service-account.json"
# Play's per-language limit for "What's new".
NOTES_LIMIT = 500


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token(key_path: Path) -> str:
    key = json.loads(key_path.read_text(encoding="utf-8"))
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": key["client_email"],
        "scope": SCOPE,
        "aud": key["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(claims).encode())}"
    private_key = serialization.load_pem_private_key(key["private_key"].encode(), password=None)
    signature = private_key.sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
    response = requests.post(
        key["token_uri"],
        data={
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": f"{signing_input}.{b64url(signature)}",
        },
        timeout=60,
    )
    check(response, "sign in with the service account")
    return response.json()["access_token"]


def check(response: requests.Response, what: str) -> None:
    if response.ok:
        return
    try:
        message = response.json().get("error", {}).get("message") or response.text
    except ValueError:
        message = response.text
    sys.exit(f"Couldn't {what}: HTTP {response.status_code}: {message}")


class Edit:
    """One Play Console edit: nothing is visible until commit()."""

    def __init__(self, session: requests.Session, package: str):
        self.session = session
        self.base = f"{API}/{package}/edits"
        self.upload_base = f"{UPLOAD_API}/{package}/edits"
        response = session.post(self.base, timeout=60)
        check(response, "start an edit")
        self.id = response.json()["id"]

    def tracks(self) -> list:
        response = self.session.get(f"{self.base}/{self.id}/tracks", timeout=60)
        check(response, "list tracks")
        return response.json().get("tracks", [])

    def upload_bundle(self, aab: Path) -> int:
        with aab.open("rb") as f:
            response = self.session.post(
                f"{self.upload_base}/{self.id}/bundles",
                params={"uploadType": "media"},
                headers={"Content-Type": "application/octet-stream"},
                data=f,
                timeout=1800,
            )
        check(response, f"upload {aab.name}")
        return int(response.json()["versionCode"])

    def set_track(self, track: str, version_code: int, name: str, notes: str | None, status: str) -> None:
        release = {"name": name, "versionCodes": [str(version_code)], "status": status}
        if notes:
            release["releaseNotes"] = [{"language": "en-US", "text": notes}]
        response = self.session.put(
            f"{self.base}/{self.id}/tracks/{track}",
            json={"track": track, "releases": [release]},
            timeout=60,
        )
        check(response, f"put the release on the '{track}' track")

    def validate(self) -> None:
        check(self.session.post(f"{self.base}/{self.id}:validate", timeout=120), "validate the edit")

    def commit(self, not_sent_for_review: bool) -> None:
        params = {"changesNotSentForReview": "true"} if not_sent_for_review else {}
        check(self.session.post(f"{self.base}/{self.id}:commit", params=params, timeout=120), "commit the edit")

    def discard(self) -> None:
        self.session.delete(f"{self.base}/{self.id}", timeout=60)


def main() -> None:
    parser = argparse.ArgumentParser(description="Upload an AAB to a Google Play track.")
    parser.add_argument("--key", type=Path, default=Path(os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", DEFAULT_KEY)))
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--list-tracks", action="store_true", help="show the app's tracks and exit")
    parser.add_argument("--aab", type=Path)
    parser.add_argument("--track", help="e.g. internal, alpha (the default closed track), production")
    parser.add_argument("--name", help="release name shown in Play Console, e.g. 1.3.0")
    parser.add_argument("--notes-file", type=Path, help="en-US 'What's new' text (max 500 characters)")
    parser.add_argument("--draft", action="store_true", help="save as a draft release instead of rolling it out")
    parser.add_argument("--validate-only", action="store_true", help="upload and validate, then discard")
    parser.add_argument("--not-sent-for-review", action="store_true",
                        help="commit with changesNotSentForReview (when Play asks for it)")
    args = parser.parse_args()

    if not args.key.is_file():
        sys.exit(f"No service-account key at {args.key} (set --key or PLAY_SERVICE_ACCOUNT_JSON).")
    session = requests.Session()
    session.headers["Authorization"] = f"Bearer {access_token(args.key)}"
    edit = Edit(session, args.package)

    if args.list_tracks:
        for t in edit.tracks():
            releases = ", ".join(
                f"{r.get('name', '?')} [{r.get('status')}] codes={r.get('versionCodes', [])}"
                for r in t.get("releases", [])
            )
            print(f"{t['track']}: {releases or '(no releases)'}")
        edit.discard()
        return

    if not (args.aab and args.track and args.name):
        sys.exit("--aab, --track and --name are required to upload.")
    notes = args.notes_file.read_text(encoding="utf-8").strip() if args.notes_file else None
    if notes and len(notes) > NOTES_LIMIT:
        sys.exit(f"Release notes are {len(notes)} characters; Play allows {NOTES_LIMIT}.")

    print(f"Uploading {args.aab.name} ({args.aab.stat().st_size / 1e6:.1f} MB)...")
    version_code = edit.upload_bundle(args.aab)
    print(f"Uploaded versionCode {version_code}.")
    edit.set_track(args.track, version_code, args.name, notes, "draft" if args.draft else "completed")
    edit.validate()
    if args.validate_only:
        edit.discard()
        print("Validated; edit discarded (nothing published).")
        return
    edit.commit(args.not_sent_for_review)
    print(f"Committed: {args.name} (versionCode {version_code}) on '{args.track}'"
          f"{' as a draft' if args.draft else ''}.")


if __name__ == "__main__":
    main()
