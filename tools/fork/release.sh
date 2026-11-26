#!/usr/bin/env bash
# Build, sign and publish a fork release to GitHub (JustYannicc/HayaiTTS).
#
#   tools/fork/release.sh 2.5.1.1 [notes-file]
#
# The keystore lives outside the repo (~/.config/hayaitts-fork/release.jks);
# its password comes from the macOS Keychain item `hayaitts-fork-keystore`.
# Assets match what the in-app updater looks for: hayaitts-v<ver>.apk
# (universal) plus per-ABI APKs.
set -euo pipefail

version=${1:?usage: release.sh <version> [notes-file]}
notes=${2:-}
repo=$(git -C "$(dirname "$0")/../.." rev-parse --show-toplevel)
cd "$repo"
keystore="$HOME/.config/hayaitts-fork/release.jks"
[[ -f "$keystore" ]] || { echo "missing $keystore" >&2; exit 1; }
pw=$(security find-generic-password -a hayaitts-fork -s hayaitts-fork-keystore -w)

[[ -z "$(git status --porcelain)" ]] || { echo "working tree not clean" >&2; exit 1; }
branch=$(git rev-parse --abbrev-ref HEAD)
[[ "$branch" == main ]] || { echo "release from main (on $branch)" >&2; exit 1; }

trap 'rm -f signing.properties' EXIT
umask 077
cat > signing.properties <<PROPS
storeFile=$keystore
storePassword=$pw
keyAlias=hayaitts-fork
keyPassword=$pw
PROPS

export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
HAYAITTS_VERSION_NAME="v$version" ./gradlew :app:testDebugUnitTest :app:assembleRelease -q

out=$(mktemp -d)
for apk in app/build/outputs/apk/release/*.apk; do
  name=$(basename "$apk" .apk)            # app-arm64-v8a-release / app-universal-release
  abi=${name#app-}; abi=${abi%-release}
  if [[ "$abi" == universal ]]; then dest="hayaitts-v$version.apk"; else dest="hayaitts-$abi-v$version.apk"; fi
  cp "$apk" "$out/$dest"
done
apksigner=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner | tail -1)
"$apksigner" verify --print-certs "$out/hayaitts-v$version.apk" | grep -m1 'SHA-256'

git tag -a "v$version" -m "HayaiTTS fork v$version"
git push origin "v$version"
if [[ -n "$notes" ]]; then note_args=(--notes-file "$notes"); else note_args=(--generate-notes); fi
gh release create "v$version" -R JustYannicc/HayaiTTS --title "HayaiTTS fork v$version" "${note_args[@]}" "$out"/*.apk
echo "released v$version"
