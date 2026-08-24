#!/bin/sh
# Download SDL3's official Android release into app/libs/, which is where Gradle's prefab looks.
#
# The AAR is not committed: it is 16 MB of binaries built against an NDK and an API level chosen by
# somebody else, and what belongs in a repository is source somebody can read. picokit makes the
# same call about its pico-sdk clone. Re-run this after changing SDL3_VERSION.
set -e

SDL3_VERSION=3.4.14

here=$(cd "$(dirname "$0")" && pwd)
libs="$here/app/libs"
aar="$libs/SDL3-$SDL3_VERSION.aar"

if [ -f "$aar" ]; then
    echo "already have $aar"
    exit 0
fi

mkdir -p "$libs"
tmp=$(mktemp -d)
zip="$tmp/SDL3-devel-$SDL3_VERSION-android.zip"

echo "fetching SDL3 $SDL3_VERSION for Android"
curl -fsSL -o "$zip" \
    "https://github.com/libsdl-org/SDL/releases/download/release-$SDL3_VERSION/SDL3-devel-$SDL3_VERSION-android.zip"

# The zip holds the AAR plus its README and licence; only the AAR is wanted, and only one of them
# is in there, so the name is taken from the archive rather than assumed.
unzip -q -j "$zip" "*.aar" -d "$libs"
rm -rf "$tmp"

# Any older AAR beside it would be a second `SDL3::SDL3` for prefab to choose between.
for old in "$libs"/SDL3-*.aar; do
    [ "$old" = "$aar" ] || rm -f "$old"
done

echo "wrote $aar"
