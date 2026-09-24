#!/bin/zsh
# Unlocks the dedicated signing keychain made by setup_signing_keychain.sh, for an unattended
# iOS release (run through mac_run.py). Only that keychain is unlocked: the login keychain
# stays as it is. The password is read from its file and never printed.
set -euo pipefail

CI=$HOME/.dexxicon-ci
KC=$CI/signing.keychain-db
IDENTITY_SHA1=A27705A164006B8657753E96F4AAB1BBF43F5F45

if [[ ! -f $KC || ! -s $CI/keychain-pass ]]; then
  echo "No signing keychain yet: run tools/ios/setup_signing_keychain.sh once in Terminal on the Mac." >&2
  exit 1
fi
security unlock-keychain -p "$(<"$CI/keychain-pass")" "$KC"

# Prove it can sign, so a release fails here rather than 10 minutes into an archive.
probe=$(mktemp)
trap 'rm -f "$probe"' EXIT
cp /usr/bin/true "$probe"
codesign -f --keychain "$KC" -s "$IDENTITY_SHA1" "$probe" 2>/dev/null
echo "Signing keychain unlocked; signing works."
