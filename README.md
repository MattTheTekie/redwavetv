# RedWave TV — Android TV ARM32 starter project

A Kotlin Android TV music-player starter targeting Android 7.1 (API 25) and 32-bit ARM (`armeabi-v7a`). The UI uses a dark, red-accented, album-library layout inspired by modern music services; it does not use NetEase branding or assets.

## Included in this starter

- Android TV / launcher entry points; landscape, full-screen presentation.
- D-pad-focusable navigation and clickable controls.
- SMB2/SMB3 connection dialog (server, share, username, password, optional subfolder).
- Recursive SMB folder scan for common audio extensions.
- Folder-based grouping and track list.
- SMB-backed ExoPlayer progressive stream data source for scanned tracks.
- Media remote key handling (play/pause, next, previous).
- ARM32-only APK ABI filter (`armeabi-v7a`), minSdk 25.

## Not yet implemented

This is a starter project, not a finished release. Embedded ID3/FLAC metadata and cover-art extraction, true tag-based album/artist indexing, local cache management, persistent library database, playlists/favorites/history, lyrics, robust background playback/media notification, SMB share persistence, and polished artwork-heavy home-screen cards still need implementation and device testing.

## Build

Requirements: Android Studio with JDK 17, Android SDK Platform 34, Android Build Tools, and Gradle 8.x compatible with Android Gradle Plugin 8.2.2.

1. Open this folder in Android Studio.
2. Allow Gradle sync and dependency download.
3. Build > Build Bundle(s) / APK(s) > Build APK(s).
4. Output should be under `app/build/outputs/apk/debug/`.

Command line (after installing a compatible Gradle distribution):

```bash
gradle assembleDebug
```

The app's `minSdk` is 25 and `abiFilters` is set to `armeabi-v7a`. The resulting APK is for 32-bit ARM devices. To sign a release APK, configure a signing config in `app/build.gradle` before `assembleRelease`.

## SMB notes

- Use a reachable SMB server hostname/IP and share name, not a Windows drive letter.
- The library scanner limits recursion to 20 folder levels.
- SMB credentials are used for the in-memory session and are not persisted.
- SMB1 is not enabled; the client is configured for SMB 2.1 through SMB 3.1.1.
- Network playback depends on server support and the codec/container support in ExoPlayer 2.19.1.

## Compatibility and security

This project targets API 25+ and ARMv7. Test on the actual TV box: some vendor Android 7.1 builds have nonstandard TLS/network stacks or incomplete media-key handling. Do not expose SMB shares to the public internet. Use a trusted LAN/VPN and a dedicated read-only SMB account where possible.


## GitHub Actions release APK

The workflow at `.github/workflows/release-apk.yml` builds an ARM32 release APK.

- Run it manually from **Actions → Build and publish ARM32 release APK → Run workflow** to create a downloadable workflow artifact.
- Push a version tag such as `v0.1.0` to build the APK and publish it as a GitHub Release.
- Without signing secrets, the workflow produces an **unsigned** APK artifact and clearly labels it as unsigned. Android will not install an unsigned release APK.
- For installable, consistently signed releases, add these repository Actions secrets: `RELEASE_KEYSTORE_BASE64` (base64-encoded JKS/keystore file), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. Keep the same private keystore for future updates; losing it prevents signing updates with the same app identity.

Example tag command:

```bash
git tag v0.1.0
git push origin v0.1.0
```
