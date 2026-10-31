# X Files

Kotlin Android file manager with an iOS-inspired clean interface.

## Features
- Browse a folder selected through Android's Storage Access Framework
- Navigate folders and go to parent
- Search current folder
- Create folders
- Rename and delete files/folders
- Open files with installed apps
- Share files

## Build locally
Requires JDK 17, Android SDK (platform 35 + build-tools), and Gradle 8.9.

```bash
gradle --no-daemon assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub build
Push to `main` or `master`; GitHub Actions builds the APK and uploads it as an artifact.
To publish a Release, create and push a version tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Then download `app-debug.apk` from the GitHub Release.
