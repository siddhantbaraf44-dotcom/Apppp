#!/usr/bin/env bash
# Build a signed, installable Shelf APK using only the Android SDK build-tools
# (aapt2 + d8 + zipalign + apksigner). No Gradle, no AndroidX, no Node.
#
# The exact same script runs locally and in .github/workflows/build-apk.yml.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_NAME="${APP_NAME:-Shelf}"
MIN_SDK="${MIN_SDK:-23}"
TARGET_SDK="${TARGET_SDK:-34}"
COMPILE_SDK="${COMPILE_SDK:-android-35}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
APK_BASENAME="${APK_BASENAME:-${APP_NAME}-${VERSION_NAME}}"

# ---------------------------------------------------------------- locate SDK
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  for candidate in "$HOME/Android/Sdk" /opt/android-sdk /usr/local/android-sdk; do
    [[ -d "$candidate" ]] && SDK_ROOT="$candidate" && break
  done
fi
if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  cat >&2 <<'EOF'
ERROR: Android SDK not found. Set ANDROID_SDK_ROOT (or ANDROID_HOME), then:

  sdkmanager --install "platforms;android-35" "build-tools;35.0.0"

On a fresh machine, install the command-line tools first:
  https://developer.android.com/studio#command-line-tools-only
EOF
  exit 1
fi

BT="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_ROOT/platforms/$COMPILE_SDK/android.jar"
for tool in aapt2 d8 zipalign apksigner; do
  [[ -x "$BT/$tool" ]] || { echo "ERROR: missing $BT/$tool (install build-tools;$BUILD_TOOLS_VERSION)" >&2; exit 1; }
done
[[ -f "$ANDROID_JAR" ]] || { echo "ERROR: missing $ANDROID_JAR (install platforms;$COMPILE_SDK)" >&2; exit 1; }
[[ -f "$ROOT/www/index.html" ]] || { echo "ERROR: www/index.html is missing" >&2; exit 1; }

JAVAC="${JAVAC:-}"
if [[ -z "$JAVAC" ]]; then
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
    JAVAC="$JAVA_HOME/bin/javac"
  else
    JAVAC="$(command -v javac || true)"
  fi
fi
[[ -n "$JAVAC" ]] || { echo "ERROR: javac not found (install a JDK 11+)" >&2; exit 1; }

echo "==> Building $APP_NAME $VERSION_NAME (versionCode $VERSION_CODE)"
echo "    SDK:    $SDK_ROOT"
echo "    Tools:  $BT"

# ---------------------------------------------------------------- workspace
BUILD="$ROOT/build"
DIST="$ROOT/dist"
SIGNING="$ROOT/signing"
rm -rf "$BUILD"
mkdir -p "$BUILD"/{classes,dex,flat,gen,assets/www} "$DIST" "$SIGNING"
cp -R "$ROOT/www/." "$BUILD/assets/www/"

# ---------------------------------------------------------------- Java -> dex
mapfile -t SOURCES < <(find "$ROOT/app/java" -name '*.java' | sort)
echo "==> Compiling ${#SOURCES[@]} Java source(s)"
"$JAVAC" --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$BUILD/classes" "${SOURCES[@]}"

mapfile -t CLASSES < <(find "$BUILD/classes" -name '*.class' | sort)
echo "==> Dexing ${#CLASSES[@]} class file(s)"
"$BT/d8" --lib "$ANDROID_JAR" --min-api "$MIN_SDK" --output "$BUILD/dex" "${CLASSES[@]}"
[[ -f "$BUILD/dex/classes.dex" ]] || { echo "ERROR: d8 produced no classes.dex" >&2; exit 1; }

# ---------------------------------------------------------------- resources
echo "==> Compiling resources"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$BUILD/flat/"
mapfile -t FLATS < <(find "$BUILD/flat" -name '*.flat' | sort)
[[ ${#FLATS[@]} -gt 0 ]] || { echo "ERROR: aapt2 produced no .flat files" >&2; exit 1; }

LINK_ARGS=()
for f in "${FLATS[@]}"; do LINK_ARGS+=(-R "$f"); done

echo "==> Linking APK"
"$BT/aapt2" link \
  -o "$BUILD/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  "${LINK_ARGS[@]}" \
  -A "$BUILD/assets" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" \
  --auto-add-overlay

# aapt2 doesn't put dex in the APK; add it at the archive root.
cp "$BUILD/unsigned.apk" "$BUILD/with-dex.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/with-dex.apk" classes.dex)

echo "==> Aligning"
"$BT/zipalign" -p -f 4 "$BUILD/with-dex.apk" "$BUILD/aligned.apk"

# ---------------------------------------------------------------- signing
# For stable updates, provide signing/key.pk8 + signing/cert.x509.pem.
# In CI these are restored from GitHub Secrets; locally you can generate them
# once with:  bash scripts/generate-signing-key.sh
if [[ ! -s "$SIGNING/key.pk8" || ! -s "$SIGNING/cert.x509.pem" ]]; then
  echo "    ! No persistent key found - generating an EPHEMERAL key."
  echo "    ! APKs from different builds will NOT install over each other."
  bash "$ROOT/scripts/generate-signing-key.sh"
fi

OUT="$DIST/${APK_BASENAME}.apk"
echo "==> Signing"
"$BT/apksigner" sign \
  --key "$SIGNING/key.pk8" \
  --cert "$SIGNING/cert.x509.pem" \
  --min-sdk-version "$MIN_SDK" \
  --max-sdk-version "$TARGET_SDK" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled false \
  --out "$OUT" \
  "$BUILD/aligned.apk"

echo "==> Verifying"
"$BT/apksigner" verify --verbose --min-sdk-version "$MIN_SDK" "$OUT" | sed -n '1,8p'
unzip -l "$OUT" | grep -E 'classes.dex|resources.arsc|AndroidManifest.xml|assets/www/index.html' || true

echo
echo "==> APK ready: $OUT ($(du -h "$OUT" | cut -f1))"
