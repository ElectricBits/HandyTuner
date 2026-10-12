#!/bin/sh
# Rebuild a release tag from a fresh clone the way F-Droid does (Debian trixie, OpenJDK 21, no signing key)
# and compare it with a release APK. Run before uploading every release APK.
# Usage: tools/verify-reproducible.sh v0.11.2-beta [apk]    (no apk: download the one on the GitHub release)
set -eu
tag=$1; v=${tag#v}
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
work=$(mktemp -d)
git clone -q --branch "$tag" "$(git rev-parse --show-toplevel)" "$work/src" 2>/dev/null
if [ $# -ge 2 ]; then cp "$2" "$work/ref.apk"; else
    curl -sfL -o "$work/ref.apk" "https://github.com/ElectricBits/HandyTuner/releases/download/$tag/HandyTuner-$v.apk"; fi

docker run --rm -v "$work/src:/src" -v "$sdk:/sdk:ro" -v handytuner-gradle:/root/.gradle -w /src \
    -e ANDROID_HOME=/sdk debian:trixie sh -ec "
    apt-get update -qq && apt-get install -qqy --no-install-recommends openjdk-21-jdk-headless >/dev/null 2>&1
    ./gradlew -q --no-daemon clean :app:assembleRelease
    chown -R $(id -u):$(id -g) /src"

built=$work/src/app/build/outputs/apk/release/app-release-unsigned.apk
unzip -q "$work/ref.apk" -d "$work/ref"; unzip -q "$built" -d "$work/built"
# Same entries in the same order (signature files aside), and same bytes.
if [ "$(unzip -Z1 "$work/ref.apk" | grep -v ^META-INF/)" = "$(unzip -Z1 "$built" | grep -v ^META-INF/)" ] &&
    diff -r -x META-INF "$work/ref" "$work/built"; then
    echo "REPRODUCIBLE: $tag matches the release APK"; rm -rf "$work"
else
    echo "DIFFERENT: $tag does not match the release APK - kept in $work"; exit 1
fi
