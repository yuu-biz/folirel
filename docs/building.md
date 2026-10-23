# Building Folirel

This page is for contributors and anyone who wants to build the app from source. For using the app, see the [user guide](user-guide.md) (日本語: [user-guide.ja.md](user-guide.ja.md)). For the code layout, see [architecture.md](architecture.md); for the test strategy, see [testing.md](testing.md); for the project overview, see [../README.md](../README.md).

## Prerequisites

The toolchain is pinned. Use these versions to get a reproducible build.

| Component | Version / requirement | Where it is pinned |
|---|---|---|
| JDK | 21 (Eclipse Temurin is what CI uses). The code compiles to Java 17 bytecode; AGP needs JDK 17 or newer to run | `compileOptions` in `app/build.gradle.kts` |
| Gradle | 9.8.0 (use the wrapper; the distribution checksum is verified) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.4.1 (built-in Kotlin) | `gradle/libs.versions.toml` |
| Kotlin | 2.4.20 | `gradle/libs.versions.toml` |
| KSP | 2.3.12 | `gradle/libs.versions.toml` |
| Compose BOM | 2026.09.00 | `gradle/libs.versions.toml` |
| Android SDK platform | `platforms;android-37.0` (compileSdk 37, targetSdk 37) | `app/build.gradle.kts` |
| Android build-tools | 36.0.0 (the AGP default) | AGP |
| Android SDK platform-tools | Any recent version (provides `adb`) | |
| minSdk | 28 (Android 9) | `app/build.gradle.kts` |

The build does not download missing SDK components (`android.builder.sdkDownload=false` in `gradle.properties`); a missing component fails with an explicit error. Install what you need with Android Studio's SDK Manager or the command-line tools, for example:

```text
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

The Android SDK licences must be accepted once (`sdkmanager --licenses`), as with any Android build. The build also records the Git commit it was built from (shown in Settings, About); this needs `git` on the `PATH` and falls back to `unknown` otherwise.

### Tell Gradle where the SDK is

Set `JAVA_HOME` to a JDK 21 installation, and point Gradle at the Android SDK in one of two ways:

- Set the `ANDROID_HOME` environment variable, or
- create `local.properties` in the repository root (it is Git-ignored and must never be committed):

```properties
# Windows (note the escaped colon and forward slashes)
sdk.dir=C\:/path/to/Android/Sdk

# Linux / macOS
sdk.dir=/path/to/Android/Sdk
```

Check the setup:

```text
./gradlew --version        # Linux / macOS
.\gradlew.bat --version    # Windows (PowerShell)
```

## Build, test and lint

All commands run from the repository root.

Linux / macOS:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug   # JVM tests, lint, debug APK
./gradlew :app:connectedDebugAndroidTest                             # instrumented tests (device or emulator required)

# Shortcut for the first command (and optionally the device tests):
tools/verify.sh
tools/verify.sh --device-tests
```

Windows (PowerShell):

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest

.\tools\verify.ps1
.\tools\verify.ps1 -DeviceTests
```

Outputs:

| What | Location |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| JVM test reports | `app/build/reports/tests/testDebugUnitTest/` and `app/build/test-results/` |
| Lint report | `app/build/reports/lint-results-debug.html` |
| Instrumented test reports | `app/build/reports/androidTests/connected/` |

Notes:

- Lint is configured to fail the build on errors (`abortOnError = true`) and checks the debug variant.
- The JVM tests use Robolectric. On JDK 17 and newer it needs access to some JDK internals; the required `--add-opens` / `--add-exports` flags are already set for the test JVM in `app/build.gradle.kts`.
- The JVM and instrumented tests read their input files (feeds, OPML, icons) from the repository's top-level `fixtures/` directory; these are synthetic test data.
- The Room schema is exported to `app/schemas/` and used by the migration tests. Database migrations must never be destructive.
- The helper scripts in `tools/` have their own tests, which need Python 3:

  ```bash
  python3 -m unittest tools.test_compare_app_state
  ```

## Install the debug APK with adb

1. Enable USB debugging on the device (Developer options) and connect it, or start an emulator.
2. Check that the device is listed: `adb devices`.
3. Install:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

`-r` replaces an existing installation and keeps its data, but only if the new APK is signed with the same key (see [Signing](#signing)). If Android reports an inconsistent signature, you must uninstall first, which deletes the app's data; export the subscriptions as OPML beforehand if you want to keep them. The application ID is `io.github.yuubiz.folirel`.

On Git Bash for Windows, pass device paths such as `/sdcard/...` to `adb` with `MSYS_NO_PATHCONV=1` set, otherwise the shell rewrites them into Windows paths.

## Emulator notes

- Suitable emulator images (Android 17 / API 37 and Android 9 / API 28, the newest target and the minimum supported version) are `system-images;android-37.0;google_apis_playstore;x86_64` and `system-images;android-28;google_apis_playstore;x86_64`. Create AVDs with Android Studio or `avdmanager`.
- A hardware hypervisor (WHPX or Hyper-V on Windows, KVM on Linux, Hypervisor.framework on macOS) is needed for usable x86_64 emulators. `emulator -accel-check` tells you whether it is available.
- API 28 emulators with automatic GPU selection can crash the render thread with GL errors. Start them with software rendering:

  ```bash
  emulator -avd <your_api28_avd> -gpu swiftshader_indirect
  ```

- Keep the screen awake and unlocked during instrumented (UI) tests, otherwise tests that need a visible window fail or time out:

  ```bash
  adb shell svc power stayon true     # undo later with: adb shell svc power stayon false
  adb shell input keyevent 82         # dismiss the lock screen if shown
  ```

- Compose UI tests pull in an old Espresso that fails on API 37, so `espresso-core` 3.7.0 is declared explicitly for `androidTest`. Keep it when updating dependencies.

## Continuous integration

`.github/workflows/android.yml` runs only for a release, to keep within the free plan's Actions minutes and artifact storage: when a
version tag (`v*`) is pushed, or when it is started by hand (Actions > android > Run workflow, or
`gh workflow run android.yml --ref main`). Pushes to `main` and pull requests do not start it; run the same commands locally
before pushing. A GitHub Release is created from a tag only after this run is green.

What it does:

- Runner: `ubuntu-24.04`, 45-minute timeout, read-only `contents` permission, no secrets.
- Java: Temurin 21. Android SDK packages: `platform-tools`, `platforms;android-37.0`, `build-tools;36.0.0`. Gradle is set up with `gradle/actions/setup-gradle`.
- All third-party actions are pinned to full commit SHAs (the tag is noted in a comment).
- Steps: the unit tests of the helper scripts, then `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- Test and lint reports are uploaded as a `reports` artifact (kept for 7 days) even when a step fails.
- Instrumented (emulator) tests are not run in CI; run them locally before changes that touch the UI or the database.

To reproduce CI locally, use the same JDK and SDK packages and run the Gradle command above.

## Main dependencies and licences

Versions are the pins in `gradle/libs.versions.toml`.

Libraries included in the app:

| Library | Version | Licence | Used for |
|---|---|---|---|
| AndroidX Core KTX | 1.19.1 | Apache-2.0 | Platform extensions |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0 | Compose host activity |
| AndroidX Lifecycle (runtime-compose, viewmodel-compose, process) | 2.11.0 | Apache-2.0 | State holders, process lifecycle |
| Jetpack Compose (BOM) with Material 3 and Material Icons Core | BOM 2026.09.00 | Apache-2.0 | UI and icons |
| AndroidX Room | 2.8.5 | Apache-2.0 | Local database |
| AndroidX WorkManager | 2.12.0 | Apache-2.0 | Periodic background updates |
| AndroidX DataStore (Preferences) | 1.2.1 | Apache-2.0 | Settings |
| AndroidX Browser | 1.10.0 | Apache-2.0 | Custom Tabs |
| OkHttp (with Okio) | 5.5.0 | Apache-2.0 | HTTP client |
| Coil 3 (compose, network-okhttp) | 3.6.3 | Apache-2.0 | Thumbnail loading |
| jsoup | 1.23.2 | MIT | HTML text extraction, link discovery |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | Concurrency |
| kotlinx.serialization (JSON) | 1.11.0 | Apache-2.0 | JSON handling |
| Kotlin standard library | 2.4.20 | Apache-2.0 | Language runtime |

Build and test only (not shipped in the APK):

| Tool / library | Version | Licence |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | Apache-2.0 |
| Kotlin Gradle plugin / Compose compiler plugin | 2.4.20 | Apache-2.0 |
| KSP | 2.3.12 | Apache-2.0 |
| JUnit 4 | 4.13.2 | EPL-1.0 |
| Robolectric | 4.17 | MIT |
| MockWebServer3, okhttp-tls | 5.5.0 | Apache-2.0 |
| AndroidX Test (core, ext-junit, runner, rules), Espresso, UI Automator, Compose UI test | see the version catalogue | Apache-2.0 |
| kotlinx-coroutines-test, Room testing, WorkManager testing | as above | Apache-2.0 |

Folirel itself is released under the MIT License; see the `LICENSE` file in the repository root. The app's Settings, About section lists the main open-source components and their licences.

## Signing

- Debug builds are signed automatically by the Android toolchain with the standard debug keystore, which the SDK generates on first use at `~/.android/debug.keystore` (on Windows, `.android\debug.keystore` in your user profile). This key is for development only and is different on every machine, so a debug APK built on one computer cannot update a copy built on another.
- The repository contains no distribution keystore and defines no release signing configuration. `assembleRelease` therefore produces an unsigned APK, which Android will not install, and the release variant is not minified. A debug APK is not a production release.
- Never commit a keystore, a key password or a `keystore.properties` file. `.gitignore` already excludes `*.jks`, `*.keystore`, `keystore.properties`, `.env` files and `local.properties`; keep it that way. If you publish your own signed build, create your own long-lived key, store it outside the repository, and back it up: it is the only way users can update the app without losing their data.
- CI uses no secrets and builds only the debug variant.
