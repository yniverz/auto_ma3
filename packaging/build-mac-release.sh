#!/bin/bash
# Builds the AutoMA3 release for the Mac: a folder with its own small Java runtime (no Java needed on the target
# Mac), the app and AutoMA3.command, which starts it in a Terminal window and opens the web UI in the browser.
# Output: target/dist/AutoMA3/ and target/dist/AutoMA3-<version>-mac-<arch>.zip
#
# Built for the CPU of this Mac (Apple Silicon here); an Intel Mac needs a build made with an Intel JDK.
set -euo pipefail

cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
[ -x "$JAVA_HOME/bin/jlink" ] || JAVA_HOME="$JAVA_HOME/libexec/openjdk.jdk/Contents/Home"
export JAVA_HOME
# version: APP_VERSION from CI (1.0.<build number>), otherwise the one in pom.xml
VERSION="${APP_VERSION:-$(sed -n 's:.*<version>\([0-9][0-9.]*\)</version>.*:\1:p' pom.xml | head -1)}"
ARCH=$([ "$(uname -m)" = "arm64" ] && echo arm64 || echo x64)
DIST=target/dist
# build outside the project: folders synced by iCloud (e.g. ~/Documents) add Finder metadata that
# code signing rejects ("resource fork, Finder information, or similar detritus not allowed")
WORK=$(mktemp -d -t automa3-build)
trap 'rm -rf "$WORK"' EXIT
OUT="$WORK/AutoMA3"

echo "Building AutoMA3 $VERSION ($(uname -m)) with $JAVA_HOME"
mvn -q clean package -DskipTests -Dapp.version="$VERSION"

# Java runtime with only the modules AutoMA3 uses (jdeps), plus TLS for the update check
MODULES="$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --multi-release 21 --print-module-deps target/auto-ma3.jar),jdk.crypto.ec,jdk.unsupported"
echo "Java modules: $MODULES"
mkdir -p "$OUT/app"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
  --output "$OUT/runtime"
# jlink copies the JDK's read-only permissions; the launcher must be able to clear the download flag on every file
chmod -R u+w "$OUT/runtime"
cp target/auto-ma3.jar "$OUT/app/"

cat > "$OUT/AutoMA3.command" <<'EOF'
#!/bin/bash
# AutoMA3: double-click to start. Runs in this Terminal window and opens the web UI in the browser.
# Quit with Ctrl+C or by closing the window. Options (see --help) can be added at the end of the last line.
DIR="$(cd "$(dirname "$0")" && pwd)"
# a downloaded zip marks every file as "from the internet"; macOS would block the bundled Java
xattr -dr com.apple.quarantine "$DIR" 2>/dev/null
exec "$DIR/runtime/bin/java" -Xmx1g -jar "$DIR/app/auto-ma3.jar" --open "$@"
EOF
chmod +x "$OUT/AutoMA3.command"

cat > "$OUT/README.txt" <<EOF
AutoMA3 $VERSION

Start: double-click AutoMA3.command. The first time, macOS may refuse to open it because it is not from the
App Store: right-click it, choose Open, then Open again. It starts in a Terminal window and opens the web UI
in the browser (http://127.0.0.1:8081/). Quit with Ctrl+C or by closing the Terminal window.

Settings, setups, recordings and logs are in ~/Library/Application Support/AutoMA3.
macOS asks once for access to the local network: allow it, AutoMA3 talks to the CDJs and the console.
EOF

# every binary must be signed on Apple Silicon: sign the runtime again (ad hoc, no Apple developer account needed)
xattr -cr "$OUT"
find "$OUT/runtime" -type f \( -perm -u+x -o -name "*.dylib" \) -exec codesign --force --sign - {} \;
"$OUT/runtime/bin/java" -version 2>&1 | head -1

ZIP="AutoMA3-$VERSION-mac-$ARCH.zip"
ditto -c -k --keepParent "$OUT" "$WORK/$ZIP"
rm -rf "$DIST"
mkdir -p "$DIST"
ditto "$OUT" "$DIST/AutoMA3"
cp "$WORK/$ZIP" "$DIST/"

echo
echo "Done:"
echo "  $DIST/AutoMA3/AutoMA3.command"
echo "  $DIST/$ZIP"
