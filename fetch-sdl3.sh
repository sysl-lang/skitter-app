#!/bin/sh
# Download SDL3's official Android releases into app/libs/, which is where Gradle's prefab looks.
#
# The AARs are not committed: SDL3 alone is 16 MB of binaries built against an NDK and an API level
# chosen by somebody else, and what belongs in a repository is source somebody can read. picokit
# makes the same call about its pico-sdk clone. Re-run this after changing a version below, or after
# adding a library to `skitter.sdlLibraries` in gradle.properties.
set -e

# **The versions are pinned here and the choice of libraries is not.** Which SDL libraries a project
# wants is the project's business and lives in `gradle.properties`, so that a program adding text or
# images edits one line in the file it already edits; which *version* of each is the template's, so
# that a project is not silently on whatever was released this morning.
SDL3_VERSION=3.4.14
SDL3_TTF_VERSION=3.2.2
SDL3_IMAGE_VERSION=3.2.4
SDL3_MIXER_VERSION=3.0.0

here=$(cd "$(dirname "$0")" && pwd)
libs="$here/app/libs"

# What the project asked for, read from the same file Gradle reads it from, so the two cannot
# disagree. Absent or empty means SDL3 on its own, which is what a new project gets.
wanted=$(sed -n 's/^[[:space:]]*skitter\.sdlLibraries[[:space:]]*=[[:space:]]*//p' \
         "$here/gradle.properties" | tail -1)

mkdir -p "$libs"

# One function, called once per library: the releases all have the same shape, and what differs is
# the repository, the tag, the asset name and the prefix an older copy is matched by.
fetch() {
    repo=$1
    version=$2
    stem=$3
    want="$libs/$stem-$version.aar"

    if [ -f "$want" ]; then
        echo "already have $stem $version"
        return
    fi

    tmp=$(mktemp -d)

    echo "fetching $stem $version for Android"
    curl -fsSL -o "$tmp/a.zip" \
        "https://github.com/libsdl-org/$repo/releases/download/release-$version/$stem-devel-$version-android.zip"

    # The zip holds the AAR plus its README and licence; only the AAR is wanted, and there is one of
    # them, so the name is taken from the archive rather than assumed.
    unzip -q -j "$tmp/a.zip" "*.aar" -d "$libs"
    rm -rf "$tmp"

    # **An older copy beside it is a second prefab module of the same name**, and prefab picks one
    # without saying which. `SDL3-` also prefixes `SDL3_ttf-`, so an exact-stem match is required
    # here or fetching SDL3 would delete the companions.
    for stale in "$libs/$stem"-*.aar; do
        [ -f "$stale" ] || continue

        case "${stale#"$libs/$stem"-}" in
            *_*) continue ;;
        esac

        [ "$stale" = "$want" ] || rm -f "$stale"
    done

    echo "wrote $want"
}

fetch SDL "$SDL3_VERSION" SDL3

for lib in $wanted; do
    case "$lib" in
        ttf)   fetch SDL_ttf   "$SDL3_TTF_VERSION"   SDL3_ttf ;;
        image) fetch SDL_image "$SDL3_IMAGE_VERSION" SDL3_image ;;
        mixer) fetch SDL_mixer "$SDL3_MIXER_VERSION" SDL3_mixer ;;
        *)
            echo "fetch-sdl3.sh: unknown SDL library '$lib' in skitter.sdlLibraries" >&2
            echo "  known: ttf image mixer" >&2
            exit 1
            ;;
    esac
done
