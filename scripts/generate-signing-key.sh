#!/usr/bin/env bash
# Create a persistent APK signing key (PKCS#8 DER private key + X.509 PEM cert).
# Run this ONCE, then add the two files to GitHub Secrets as described in README.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIGNING="$ROOT/signing"
mkdir -p "$SIGNING"

if [[ -s "$SIGNING/key.pk8" && -s "$SIGNING/cert.x509.pem" ]] && [[ "${1:-}" != "--force" ]]; then
  echo "Signing key already exists in $SIGNING (use --force to replace it)."
  exit 0
fi

openssl req -x509 -newkey rsa:2048 -sha256 -days 10000 -nodes \
  -keyout "$SIGNING/key.pem" -out "$SIGNING/cert.x509.pem" \
  -subj "/CN=Shelf Android/O=Shelf/C=US" 2>/dev/null
openssl pkcs8 -topk8 -nocrypt -in "$SIGNING/key.pem" -outform DER -out "$SIGNING/key.pk8"
rm -f "$SIGNING/key.pem"
chmod 600 "$SIGNING/key.pk8"

echo "Created:"
echo "  $SIGNING/key.pk8"
echo "  $SIGNING/cert.x509.pem"
echo
echo "Add them to GitHub Secrets (base64, single line):"
echo "  SHELF_SIGNING_KEY_B64  = \$(base64 -w0 signing/key.pk8)"
echo "  SHELF_SIGNING_CERT_B64 = \$(base64 -w0 signing/cert.x509.pem)"
echo
echo "NEVER commit the signing/ directory - it is in .gitignore."
