## RedWave TV — AI created Android TV SMB music player. 

As much as I generally don't like AI, using it to free devices from ewaste is something I feel like it should be used for. I could NOT find a app for this specific use case that works with the hardware constraints I have, and yes I made this for personal use. It's a bit buggy but works well enough for me not to care. It took FOREVER of pushing ChatGPT and Perplexity in the correct direction to produce. If you want to contribute or make a fork feel free to do so.


A Kotlin Android TV music-player starter targeting Android 7.1 (API 25) and 32-bit ARM (`armeabi-v7a`). The UI uses a dark, red-accented, album-library layout inspired by modern music services; it does not use NetEase branding or assets.


## Current app features

- Android TV / launcher entry points; landscape, full-screen presentation.
- Reworked dark TV-first interface with high-contrast, D-pad-focusable navigation and visible clickable controls.
- SMB2/SM3 connection dialog with server, share, optional subfolder, username, password, and domain.
- SMB share access check followed by recursive scanning (up to 32 folder levels) for common audio extensions, with connection/scan status and actionable error messages.
- Folder-based grouping and track list.
- SMB-backed ExoPlayer progressive stream data source for scanned tracks.
- Media remote key handling (play/pause, next, previous).
- ARM32-only APK ABI filter (`armeabi-v7a`), minSdk 25.

## Not yet implemented

This is an actively evolving project and still requires testing on real Android TV hardware. Embedded ID3/FLAC metadata and cover-art extraction, true tag-based album/artist indexing, local cache management, persistent library database, playlists/favorites/history, lyrics, robust background playback/media notification, SMB share persistence, and polished artwork-heavy home-screen cards still need implementation and device testing.

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
- The library scanner limits recursion to 32 folder levels.
- SMB credentials are used for the in-memory session and are not persisted.
- SMB1 is not enabled; the client is configured for SMB 2.1 through SMB 3.1.1.
- Network playback depends on server support and the codec/container support in ExoPlayer 2.19.1.

## Compatibility and security

This project targets API 25+ and ARMv7. Test on the actual TV box: some vendor Android 7.1 builds have nonstandard TLS/network stacks or incomplete media-key handling. Do not expose SMB shares to the public internet. Use a trusted LAN/VPN and a dedicated read-only SMB account where possible.


## GitHub Actions release APK

The workflow at `.github/workflows/release-apk.yml` builds an ARM32 release APK.

- Run it manually from **Actions → Build and publish ARM32 release APK → Run workflow** to create a downloadable workflow artifact.
- Push a version tag such as `v0.1.0` to build the APK and publish it as a GitHub Release.
- The workflow produces an **unsigned** APK artifact. Android will not install an unsigned release APK; it must be signed separately before installation.

Example tag command:

```bash
git tag v0.1.0
git push origin v0.1.0
```
