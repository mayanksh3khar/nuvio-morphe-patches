# Nuvio Morphe patches (experimental)

Morphe patch bundle for the **official Nuvio Android app** (`com.nuvio.app`).
Written against **0.6.5-beta (versionCode 148, arm64-v8a)**. Marked **experimental** and targets **any version**.
Both patches are enabled by default.

## Patches

### P2P external player fix
Without it, "open in external player" sends P2P (torrent) streams to the external player as the
placeholder `torrent://<hash>?index=N`, which no external player can play.
With it, that placeholder is replaced by the local stream URL of Nuvio's own P2P engine.
Every other URL is left untouched, and on any problem the original URL is used (it fails safe).

How it finds its targets (so it can survive updates): it locates the obfuscated request class by the
text of its `toString()` and rewrites every place that builds it. The P2P engine classes keep their
real names in Nuvio releases, so the helper reaches them by reflection.

Inpired by @neon16bit commit - https://github.com/NuvioMedia/NuvioMobile/commit/70a27a1268c0a86921270ffa0abf2d118705b622

### Material You theme
Adds a **Material** theme to Settings > Layout > Theme.

On Android 12+ the accent and background colors follow your wallpaper (Material You). On older Android
it uses a fixed purple Material palette. It is saved and restored like every other theme.
The chip appears at the end of the theme list (the app builds that list from its theme enum order).

How it works: it appends a `MATERIAL` value to the theme enum (found by its constant names), returns a
Material You palette from the palette function for that value, and handles the theme picker's switch so
the new value cannot crash it. All targets are found by strings and structure, not by obfuscated names.

Inspired by my commit - https://github.com/NuvioMedia/NuvioMobile/commit/d5179255b686a1c4c765488631552ba0310c74c4

## Build without installing anything (GitHub Actions)


1. Open the repository's **Actions** tab. The "Build patches" workflow runs on every push
   (or choose it and press **Run workflow**).
2. When it finishes, open the run and download the `nuvio-patches-mpp` artifact.
   Unzip it to get `patches-1.0.0.mpp`.

## Build locally

You need: JDK 21, the Android SDK (Android Studio installs it), and a GitHub personal access
token with `read:packages` (Morphe's Gradle plugin is hosted on GitHub Packages).

1. Put the token in `~/.gradle/gradle.properties` (Windows: `%USERPROFILE%\.gradle\gradle.properties`):
   ```
   gpr.user=YOUR_GITHUB_USERNAME
   gpr.key=YOUR_TOKEN
   ```
2. Build:
   ```
   ./gradlew buildAndroid          (Windows: gradlew.bat buildAndroid)
   ```
3. The bundle is `patches/build/libs/patches-*.mpp`.

## Use

1. In Morphe Manager (or Morphe Desktop) add the `.mpp` as a local patch source.
2. Pick the official Nuvio APK (arm64-v8a) and patch it. Enable experimental/any-version support if asked.
3. Android will not update the official app in place because Morphe re-signs it.
   Uninstall the official Nuvio first (sign in to your Nuvio account afterwards to restore synced data).

## If patching fails

The patch throws a clear message instead of guessing:
- `Expected exactly 1 class containing "ExternalPlayerPlaybackRequest(sourceUrl="`: the request class changed.
- `Found the request class (...) but no place that creates it`: Nuvio changed how requests are built.
- `Expected exactly 1 theme enum containing ...`: the theme enum changed.
- `Expected to patch 1 palette function and 1 theme chip ...`: Nuvio changed how themes are switched.

You can turn a patch off in Morphe Manager if only one of them fails.

Report the issue along with the log
