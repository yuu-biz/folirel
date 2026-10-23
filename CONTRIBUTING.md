# Contributing to Folirel

Thanks for your interest. Bug reports, feature ideas and pull requests are welcome.

## Before you start

Folirel is intentionally small. Please open an issue before starting a large change, especially anything touching the
following, which are **out of scope** by design:

- Accounts, a sync service or any server component, and provider integrations that need sign-in.
- A built-in article reader, a custom WebView, full-text extraction or offline article storage.
- Automatic mark-as-read (on open, on scroll, or over time).
- Notifications, text-to-speech, AI features, ads, analytics or other SDKs that send data off the device.

The product rules are listed in [docs/product-and-ux.md](docs/product-and-ux.md).

## Development setup

See [docs/building.md](docs/building.md). In short: JDK 21, the Android SDK (`android-37.0`, build-tools `36.0.0`), a
`local.properties` with `sdk.dir`, then

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Instrumented tests (`./gradlew :app:connectedDebugAndroidTest`) need an emulator or device. If you could not run them,
say so in the pull request.

## Guidelines

- Follow the conventions in [AGENTS.md](AGENTS.md): architecture boundaries, data safety, security rules and test discipline.
  They apply to people as much as to coding agents.
- Keep changes focused, add tests for behaviour changes, and make lint pass.
- UI text goes into both `res/values/strings.xml` (English) and `res/values-ja/strings.xml` (Japanese).
- Database changes need an explicit migration, an exported schema in `app/schemas/` and a migration test.
- Do not commit personal data (OPML files, private feed URLs, cookies, keys, keystores). Use synthetic data, as in `fixtures/`.

## Pull requests

- One topic per pull request, with a short description of what changes and why.
- Run the JVM tests, lint and the debug build locally (see [AGENTS.md](AGENTS.md)); CI (`.github/workflows/android.yml`) runs
  only for releases, and a maintainer can start it by hand for a pull request.
- By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).

## Reporting security issues

Please do not open a public issue for a vulnerability. Contact the maintainer privately (for example through GitHub's
private vulnerability reporting, if enabled for the repository) and allow time for a fix.
