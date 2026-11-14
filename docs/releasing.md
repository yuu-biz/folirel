# Releasing Folirel

This page is for the maintainer who builds and publishes a release. Folirel is distributed as an APK on GitHub
Releases only; there is no Play Store build, no AAB and no Play-specific flavour. For building in general, see
[building.md](building.md).

Every release from 1.0 on is signed with the one release key. Android updates an installed app only with an APK signed
by the same key, so the key must never change and must never be lost (keep a copy outside this computer).

| Item | Value |
|---|---|
| Package name (applicationId) | `io.github.yuubiz.folirel` |
| Release certificate SHA-256 (as `keytool` prints it) | `C7:40:AE:3B:5A:57:BF:46:BE:12:ED:D7:89:4B:6A:01:70:95:B9:5A:3F:72:17:E2:CC:4B:94:02:CC:9B:00:B1` |
| The same, as `apksigner` prints it | `c740ae3b5a57bf46be12edd7894b6a017095b95a3f7217e2cc4b9402cc9b00b1` |
| Signature schemes | v2 and v3 (no v1: the minimum Android version, 9, does not need it) |
| Version | `versionName` `X.Y.Z` (release candidates `X.Y.Z-rc.N`) and a `versionCode` that keeps counting up, in `app/build.gradle.kts` |

## 1. Set up release signing

Create `keystore.properties` in the repository root. It is listed in `.gitignore`; never commit it, and keep the
keystore itself outside the repository. The format is in `keystore.properties.example`:

```properties
storeFile=<path to the .jks file, absolute or relative to the repository root>
storePassword=<keystore password>
keyAlias=<key alias>
keyPassword=<key password>
```

Without this file, tests, lint and debug builds still work, but `assembleRelease` stops with
`Release signing is not set up` instead of producing an unsigned or debug-signed APK. A file with a missing key
stops the build and names the missing key (never its value).

## 2. Build

Windows (PowerShell), from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

Linux / macOS: the same tasks with `./gradlew`. The signed APK is `app/build/outputs/apk/release/app-release.apk`. The
release build is shrunk with R8 (`app/build/outputs/mapping/release/mapping.txt` maps stack traces back to the source; keep
it with the release). Build from a clean, committed tree: the version line in Settings > About shows the Git commit, with
`-dirty` when the tree had uncommitted changes.

Before publishing, install the release APK on a device or emulator and check the main paths: import, refresh, open an
article, a backup and a restore, Settings > About > Licenses.

## 3. Check the signature and the alignment

The tools are in the Android SDK build tools (`%ANDROID_HOME%\build-tools\<version>\` on Windows):

```text
apksigner verify -v --print-certs app/build/outputs/apk/release/app-release.apk
```

The output must contain:

```text
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate SHA-256 digest: c740ae3b5a57bf46be12edd7894b6a017095b95a3f7217e2cc4b9402cc9b00b1
```

With the APK's own minimum Android version (9), `apksigner` checks only the v3 signature, which supersedes v2 there, and
reports v2 as `false`. To check that the v2 signature is present too, add `--min-sdk-version 24`; both lines then read
`true`.

Check the 16 KB alignment of the native libraries (needed on devices with 16 KB memory pages):

```text
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
```

It must end with `Verification successful`.

## 4. Name the file and compute its checksum

Copy the APK to `Folirel-X.Y.Z.apk` (for a release candidate `Folirel-X.Y.Z-rc.N.apk`) and compute its SHA-256:

```powershell
Get-FileHash -Algorithm SHA256 Folirel-X.Y.Z.apk
```

```bash
sha256sum Folirel-X.Y.Z.apk
```

## 5. Publish on GitHub

1. The version is set and committed, and `main` is pushed.
2. Tag the commit `vX.Y.Z` (or `vX.Y.Z-rc.N`) and push the tag. The tag starts the CI workflow (see
   [building.md](building.md#continuous-integration)); create the release only after it is green.
3. Create the GitHub Release from the tag. Mark a release candidate as a pre-release.
4. Attach `Folirel-X.Y.Z.apk`. Attach nothing else that a reader could install by mistake.
5. Write the release notes in English, then `---`, then `## 日本語` with the same facts:
   - Start with the changes, one bullet per change, written for a person who uses the app: what they see or can now
     do, with where a setting lives and its default. Name settings the way the app names them in each language
     (Settings > Storage and data, 設定 > 保存とデータ). Do not open with the app's name or a "What's new" heading; the
     release title names the version.
   - Use `### Added`, `### Changed` and `### Fixed` only when there are more than four changes of more than one kind.
     Otherwise write a flat list and start a bug fix with "Fixed".
   - When readers must do something before they update (a new signing key, a new package name, data that would be
     lost), put that first, as numbered steps.
   - End the English part with two lines, without explanation:
     `- APK SHA-256: <hash of Folirel-X.Y.Z.apk from step 4>` and
     `- Signing certificate SHA-256: c740ae3b5a57bf46be12edd7894b6a017095b95a3f7217e2cc4b9402cc9b00b1`.
     End the Japanese part with 「APK と署名証明書の SHA-256 は上の英語の節にあります。」 How to check them, and how
     to move from a 0.x debug build, are in the README.
   - Add "Package `io.github.yuubiz.folirel`, version X.Y.Z (versionCode), built from `<commit>`" only when the package
     or the signing key changes.
   - Never put a personal name or e-mail address in the notes; the project signs as `yuu-biz`.
6. Open the release page in a private browser window and check the file name, the checksum and the notes.

## 6. Android developer verification

Android's developer verification links a package name to the developers who sign it. It is separate from Google Play
and does not review the app. The maintainer registers in the Android Developer Console; this needs:

- the package name: `io.github.yuubiz.folirel`;
- the release certificate SHA-256: the `keytool` form in the table above. To read it from the keystore:
  `keytool -list -v -keystore <keystore> -alias <alias>` (line `SHA256:`), or from a signed APK with step 3;
- a release APK signed with that key, as proof that you hold the key. Submit the signed APK, never the keystore or its passwords.

Check the current official requirements when you register: https://developer.android.com/developer-verification/guides
