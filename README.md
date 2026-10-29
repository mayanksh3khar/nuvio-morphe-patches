# Nuvio Morphe patches (personal, experimental)

Morphe patch bundle for the **official Nuvio Android app** (`com.nuvio.app`).
Written against **0.6.5-beta (versionCode 148, arm64-v8a)**. Marked **experimental** and targets **any version**.

## Patches

### P2P external player fix
Without it, "open in external player" sends P2P (torrent) streams to the external player as the
placeholder `torrent://<hash>?index=N`, which no external player can play.
With it, that placeholder is replaced by the local stream URL of Nuvio's own P2P engine.
Every other URL is left untouched, and on any problem the original URL is used (it fails safe).

How it finds its targets (so it can survive updates): it locates the obfuscated request class by the
text of its `toString()` and rewrites every place that builds it. The P2P engine classes keep their
real names in Nuvio releases, so the helper reaches them by reflection.

Not included yet: automatically launching the external player when a P2P stream starts.

## Build without installing anything (GitHub Actions)

1. Push this folder to a new GitHub repository (private is fine).
2. Open the repository's **Actions** tab. The "Build patches" workflow runs on every push
   (or choose it and press **Run workflow**).
3. When it finishes, open the run and download the `nuvio-patches-mpp` artifact.
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

Send the full log and the Nuvio version.
