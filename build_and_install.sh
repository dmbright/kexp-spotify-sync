#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "=== KEXP -> Spotify Sync: Build & Install Helper ==="

# 1. Check Java 17
if ! java -version >/dev/null 2>&1; then
  echo "[!] Java runtime not found. Installing OpenJDK 17 via Homebrew..."
  brew install openjdk@17
  export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

# 2. Check Android SDK
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [ -d "/opt/homebrew/share/android-commandlinetools" ]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
  else
    echo "[!] Android SDK not found. Installing android-commandlinetools via Homebrew..."
    brew install --cask android-commandlinetools
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
  fi
fi

# Ensure platform-tools and build-tools are installed
if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
  echo "[*] Accepting Android SDK licenses and installing android-34 & build-tools..."
  yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
  sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
fi

echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3. Build APK using Gradle
if [ ! -f "./gradlew" ]; then
  if ! command -v gradle >/dev/null 2>&1; then
    echo "[!] Installing gradle via Homebrew to generate wrapper..."
    brew install gradle
  fi
  gradle wrapper --gradle-version 8.7
fi

echo "[*] Building Debug APK..."
./gradlew assembleDebug

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
echo "[✓] Built APK at: $APK_PATH"

# 4. Install onto connected Android phone if present
if adb get-state >/dev/null 2>&1; then
  echo "[*] Installing onto connected Android phone via ADB..."
  adb install -r "$APK_PATH"
  echo "[✓] Installed! Launching KEXP -> Spotify Sync..."
  adb shell am start -n com.kexpspotifysync/.MainActivity
else
  echo "[i] Connect your Android phone with USB Debugging enabled and re-run './build_and_install.sh' (or run 'adb install -r $APK_PATH')."
fi
