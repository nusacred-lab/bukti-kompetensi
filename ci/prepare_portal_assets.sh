#!/bin/sh
# Fingerprint public JavaScript and update HTML during image packaging.
set -eu
cd "$1"
for name in portal-boot web-portals; do
    version=$(sha256sum "$name.js" | cut -c1-20)
    mv "$name.js" "$name-$version.js"
    sed -i "s|src=\"$name.js\"|src=\"$name-$version.js\"|g" index.html
done
