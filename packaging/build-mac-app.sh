#!/bin/bash
# Builds a self-contained AutoMA3.app (own Java runtime inside, no Java/Maven needed on the target Mac)
# and a .dmg to copy it to another Mac. Output: target/dist/
#
# The app is built for the CPU of this Mac (Apple Silicon here). An Intel Mac needs a build made on an
# Intel Mac (or an Intel JDK + JavaFX).
set -euo pipefail

cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
[ -x "$JAVA_HOME/bin/jpackage" ] || JAVA_HOME="$JAVA_HOME/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME
# version: APP_VERSION from CI (1.0.<build number>), otherwise the one in pom.xml
VERSION="${APP_VERSION:-$(sed -n 's:.*<version>\([0-9][0-9.]*\)</version>.*:\1:p' pom.xml | head -1)}"
ARCH=$([ "$(uname -m)" = "arm64" ] && echo arm64 || echo x64)
DIST=target/dist
# build outside the project: folders synced by iCloud (e.g. ~/Documents) add Finder metadata that
# code signing rejects ("resource fork, Finder information, or similar detritus not allowed")
WORK=$(mktemp -d -t automa3-build)
trap 'rm -rf "$WORK"' EXIT
APP="$WORK/AutoMA3.app"

echo "Building AutoMA3 $VERSION ($(uname -m)) with $JAVA_HOME"
mvn -q clean package -DskipTests -Dapp.version="$VERSION"

rm -rf "$DIST"
mkdir -p "$DIST" "$WORK/input"
cp target/auto-ma3.jar "$WORK/input/"

"$JAVA_HOME/bin/jpackage" \
  --type app-image \
  --name AutoMA3 \
  --app-version "$VERSION" \
  --vendor "AutoMA3" \
  --input "$WORK/input" \
  --main-jar auto-ma3.jar \
  --main-class automa3.desktop.DesktopMain \
  --icon packaging/AutoMA3.icns \
  --mac-package-identifier automa3.app \
  --java-options "-Xmx1g" \
  --dest "$WORK"
[ -d "$APP" ] || { echo "jpackage did not create $APP" >&2; exit 1; }

# macOS asks for these permissions; without the texts newer macOS versions block the access silently
PLIST="$APP/Contents/Info.plist"
plutil -replace NSLocalNetworkUsageDescription -string \
  "AutoMA3 talks to the Pioneer CDJs (Pro DJ Link) and the grandMA3 console on the local network." "$PLIST"
plutil -replace NSMicrophoneUsageDescription -string \
  "AutoMA3 can analyse the mixer's audio feed (optional, Settings > Live audio)." "$PLIST"

# changing Info.plist invalidates the signature: sign again (ad hoc, no Apple developer account needed)
xattr -cr "$APP"
codesign --force --deep --sign - "$APP"
codesign --verify --deep "$APP"

# hdiutil fails now and then on build machines ("Resource busy"): retry a few times
for attempt in 1 2 3; do
  if hdiutil create -quiet -volname "AutoMA3" -srcfolder "$APP" -ov -format UDZO "$WORK/AutoMA3-$VERSION.dmg"; then break; fi
  [ "$attempt" = 3 ] && { echo "hdiutil failed 3 times" >&2; exit 1; }
  echo "hdiutil failed, retrying ($attempt)"; sleep 5
done
# zip of the app for the in-app updater (ditto keeps the bundle and its signature intact)
ditto -c -k --keepParent "$APP" "$WORK/AutoMA3-$VERSION-mac-$ARCH.zip"
ditto "$APP" "$DIST/AutoMA3.app"
cp "$WORK/AutoMA3-$VERSION.dmg" "$WORK/AutoMA3-$VERSION-mac-$ARCH.zip" "$DIST/"

echo
echo "Done:"
echo "  $DIST/AutoMA3.app"
echo "  $DIST/AutoMA3-$VERSION.dmg"
echo "  $DIST/AutoMA3-$VERSION-mac-$ARCH.zip"
