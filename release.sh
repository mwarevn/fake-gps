#!/bin/bash

if [ -z "$1" ]; then
  echo "Usage: $0 \"v[0-9].[0-9].[0-9]\""
  exit 1
fi

VERSION_STRING="$1"
if [[ ! $VERSION_STRING =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid version string format. Must be in the format 'v*.*.*' (e.g., v1.2.3)"
  exit 1
fi

VERSION_NAME="${VERSION_STRING#v}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION_NAME"
# Sử dụng hệ số nhân lớn hơn (10000 và 100) để tránh trùng lặp Version Code khi Minor/Patch vượt quá 9
VERSION_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

echo "Releasing in 4s..."
echo "- versionName: $VERSION_NAME"
echo "- versionCode: $VERSION_CODE"
sleep 4

# Update version in build.gradle (Sửa cú pháp sed cho phù hợp với Linux thay vì macOS)
sed -i "s/def tagName = '.*'/def tagName = '$VERSION_NAME'/" app/build.gradle
sed -i "s/versionCode [0-9]*/versionCode $VERSION_CODE/" app/build.gradle

# Build release APK (No flavor, just release)
echo "Building release APK..."
./gradlew assembleRelease

# Check if build was successful
# Path changes from outputs/apk/full/release to outputs/apk/release
APK_PATH="app/build/outputs/apk/release/app-arm64-v8a-release.apk"
if [ ! -f "$APK_PATH" ]; then
  echo "Error: APK build failed!"
  exit 1
fi

echo "Build successful!"
echo "APK location: $APK_PATH"

# Commit and tag
git add app/build.gradle
git commit -m "bump version to $VERSION_NAME"
git tag $VERSION_STRING
git push
git push origin $VERSION_STRING

echo ""
echo "✅ Release completed!"
echo "📦 APK: $APK_PATH"
echo "🏷️  Tag: $VERSION_STRING"
echo ""
echo "Next steps:"
echo "1. Go to: https://github.com/mwarevn/fake-gps/releases/new"
echo "2. Select tag: $VERSION_STRING"
echo "3. Upload APK: $APK_PATH"
echo "4. Publish release"
