# Folirel

[English](README.md) | [日本語](README.ja.md)

Folirel is a local-first RSS/Atom reader for Android. It reads your subscriptions straight from the feeds' own
servers, keeps everything on the device, and sends your reading habits nowhere: no account, no sync service, no
analytics, no ads.

Move your subscriptions in from Feedly (or any reader) with an OPML file, scan a dense list of the latest articles, and
open the original page in your browser.

**Folirel** is a coined name inspired by *folio* and *reader*. The word *folio* ultimately traces back to Latin *folium*, "leaf" or "sheet," reflecting the idea of turning the feeds you choose into pages you can browse.

> **Status:** early release (0.4.x). The app is feature-complete for its first scope and is tested on emulators.
> Debug-signed APKs are published on the Releases page; there is no Google Play listing yet.

## Features

- **OPML import and export.** Importing shows what will be added before anything is written, and only adds or merges.
  Existing read/saved state and categories are never overwritten.
- **Direct fetching** of RSS 2.0, Atom 1.0 and RSS 1.0 feeds, with conditional requests, charset detection (including
  Shift_JIS) and per-feed diagnostics when a feed fails.
- **Three list layouts:** List, Compact list and Magazine (mixed card sizes with thumbnails and summaries).
- **Categories** with nesting, a navigation drawer, category tabs that follow your finger while you swipe between them, a category picker with search, pinned and re-orderable tabs, and a layout of their own per category if you want one.
- **Explicit read state.** Articles are marked read, unread or saved only when you say so. Opening an article never
  marks it read.
- **Original site first.** Articles open in Custom Tabs (or your external browser); there is no built-in reader.
- **Local search** over titles, summaries and sources.
- **Refresh** on demand (scoped to what you are looking at) and periodically in the background, with a confirmation on
  metered networks.
- **Feed housekeeping:** find the feed behind a site address, move HTTP feeds to HTTPS after verifying it is the same
  feed, and clean up dead feeds in bulk.
- **English and Japanese** UI (follows the system language; per-app language on Android 13+), light/dark theme, text size setting.

## Install

1. Download the latest APK from the **Releases** page of this repository.
2. Allow installing apps from your browser or file manager when Android asks, then open the APK. Android 9 (API 28) or
   later is required.
3. Open Folirel and either import an OPML file or add a feed URL.

Release APKs are currently signed with a debug key, so they update only over builds signed with the same key.
Uninstalling the app deletes its data; use *Export OPML* to keep your subscription list.
Details: [User guide](docs/user-guide.md).

## Build from source

Requirements: JDK 21 and the Android SDK (platform `android-37.0`, build-tools `36.0.0`). Create a `local.properties`
with `sdk.dir=<path to your Android SDK>`, then:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Windows uses `.\gradlew.bat`.
More in [docs/building.md](docs/building.md).

## Privacy and security

- Subscriptions, articles and read state are stored only in the app's private storage. Backups are disabled.
- The app contacts only the feed servers you subscribe to (and the sites and image hosts those feeds point to).
  There are no third-party SDKs and no telemetry.
- Feed content is treated as untrusted: XML with DTD/DOCTYPE or external entities is rejected, TLS verification is
  always on, plain `http://` feeds are not fetched until you allow them, and each URL is checked against private and
  local addresses before it is requested.

Details: [Fetch and security](docs/fetch-and-security.md).

## Documentation

| Document | Contents |
|---|---|
| [User guide](docs/user-guide.md) / [日本語](docs/user-guide.ja.md) | Installing, importing, daily use, settings |
| [Product and UX](docs/product-and-ux.md) | Scope, requirements, screens and interaction rules |
| [Architecture](docs/architecture.md) | Modules, data flow, extension boundary |
| [Data and import](docs/data-and-import.md) | Database model, OPML, article identity, retention |
| [Fetch and security](docs/fetch-and-security.md) | HTTP, parsing, images, refresh, network policy |
| [Testing](docs/testing.md) | Test layers, fixtures, scenario catalogue |
| [Building](docs/building.md) | Toolchain, build, CI, dependencies and licenses |

Technical documents are in English. Contributor and coding-agent conventions are in [AGENTS.md](AGENTS.md) and
[CONTRIBUTING.md](CONTRIBUTING.md).

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first; it explains the product
scope (some things are deliberately out of scope, for example accounts, a built-in reader and automatic mark-as-read).

## License

Folirel is released under the [MIT License](LICENSE). Third-party libraries keep their own licenses; see
[docs/building.md](docs/building.md).
