#!/usr/bin/env bash
#
# Bake a CA trust store into the Ubuntu guest rootfs asset.
#
# The Canonical ubuntu-base tarball ships without ca-certificates, so the
# guest starts with no trust store: every TLS verification fails and
# `apt-get update` over HTTPS dies. The app also disables the stock
# sources.list in favour of mirrors, which makes the gap harder to notice
# until the user tries to install something.
#
# Certificates are platform-independent PEM data, so the build host's store is
# valid inside the arm64 guest — no qemu/chroot needed.
#
# Usage: ./scripts/bake_guest_ca.sh <path-to-ubuntu-base.tar.gz>
#
set -euo pipefail

ARCHIVE="${1:?usage: bake_guest_ca.sh <ubuntu-base.tar.gz>}"
[ -f "$ARCHIVE" ] || { echo "ERROR: $ARCHIVE not found" >&2; exit 1; }

HOST_BUNDLE=/etc/ssl/certs/ca-certificates.crt
[ -f "$HOST_BUNDLE" ] || { echo "ERROR: host has no $HOST_BUNDLE" >&2; exit 1; }

BAKE="$(mktemp -d)"
trap 'rm -rf "$BAKE"' EXIT

echo "==> unpacking $(basename "$ARCHIVE")"
tar xzf "$ARCHIVE" -C "$BAKE"

echo "==> installing trust store into the guest"
mkdir -p "$BAKE/etc/ssl/certs" "$BAKE/usr/share/ca-certificates/mozilla"
cp "$HOST_BUNDLE" "$BAKE/etc/ssl/certs/ca-certificates.crt"
cp -L /etc/ssl/certs/*.pem "$BAKE/etc/ssl/certs/" 2>/dev/null || true
for f in /etc/ssl/certs/*.0; do
    [ -f "$f" ] || continue
    cp -L "$f" "$BAKE/etc/ssl/certs/$(basename "$f")" 2>/dev/null || true
done
cp -L /usr/share/ca-certificates/mozilla/*.crt \
      "$BAKE/usr/share/ca-certificates/mozilla/" 2>/dev/null || true
printf 'mozilla/*.crt\n' > "$BAKE/etc/ca-certificates.conf"

echo "==> baked $(find "$BAKE/etc/ssl/certs" -type f | wc -l) cert files"

# Repack without a ./ prefix: the app's tar reader and the upstream tarball
# both use bare relative names.
( cd "$BAKE" && tar --numeric-owner -p -czf "$ARCHIVE.new" $(ls -A) )
mv "$ARCHIVE.new" "$ARCHIVE"
echo "==> $ARCHIVE repacked ($(du -h "$ARCHIVE" | cut -f1))"
