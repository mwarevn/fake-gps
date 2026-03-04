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
VERSION_CODE=$((MAJOR * 100 + MINOR * 10 + PATCH))

echo "Releasing in 4s..."
echo "- versionName: $VERSION_NAME"
echo "- versionCode: $VERSION_CODE"
sleep 4

# Update version in build.gradle
sed -i '' "s/def tagName = '.*'/def tagName = '$VERSION_NAME'/" app/build.gradle
sed -i '' "s/versionCode [0-9]*/versionCode $VERSION_CODE/" app/build.gradle

# Build release APK for full flavor
echo "Building release APK for full flavor..."
./gradlew assembleRelease

# Check if build was successful
if [ ! -f "app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk" ]; then
  echo "Error: APK build failed!"
  exit 1
fi

echo "Build successful!"
echo "APK location: app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk"

# Commit and tag
git add app/build.gradle
git commit -m "bump version to $VERSION_NAME"
git tag $VERSION_STRING
git push
git push origin $VERSION_STRING

echo ""
echo "✅ Release completed!"
echo "📦 APK: app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk"
echo "🏷️  Tag: $VERSION_STRING"
echo ""
echo "Next steps:"
echo "1. Go to: https://github.com/minhdevs/android-gps-moving-simulation/releases/new"
echo "2. Select tag: $VERSION_STRING"
echo "3. Upload APK: app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk"
echo "4. Publish release"