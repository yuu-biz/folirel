<h1>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/folirel/folirel-logo-dark.svg">
    <img src="artwork/folirel/folirel-logo.svg" alt="Folirel" height="72">
  </picture>
</h1>

[English](README.md) | [日本語](README.ja.md)

Folirel is a local-first RSS/Atom reader for Android. It reads your subscriptions straight from the feeds' own
servers, keeps everything on the device, and sends your reading habits nowhere: no account, no sync service, no
analytics, no ads.

Move your subscriptions in from Feedly (or any reader) with an OPML file, scan a dense list of the latest articles, and
open the original page in your browser.

**Folirel** is a coined name inspired by *folio* and *reader*. The word *folio* ultimately traces back to Latin *folium*, "leaf" or "sheet," reflecting the idea of turning the feeds you choose into pages you can browse. The logo is two leaves, the larger one carrying lines of text like a page.

## Features

- **OPML import and export.** Importing shows what will be added before anything is written, and only adds or merges.
  Existing read/saved state and categories are never overwritten.
- **Full local backup and restore.** One file with everything (subscriptions and their history, categories, the
  articles on the device, read and saved state, settings), for a new phone or a reinstall. Restoring shows what the
  backup holds first and replaces the data only after you confirm; a failed restore changes nothing.
- **Share to Subscribe.** Share a page or link from any app to Folirel: it checks the address for an RSS/Atom feed (the address itself, or the feeds the page declares) and subscribes only after you confirm.
- **Direct fetching** of RSS 2.0, Atom 1.0 and RSS 1.0 feeds, with conditional requests, charset detection (including
  Shift_JIS) and per-feed diagnostics when a feed fails.
- **Five list layouts:** List, Compact list, Magazine (mixed card sizes with thumbnails and summaries), Masonry (lanes of cards of different heights) and Bento (tiles of different shapes).
- **Categories** with nesting, a navigation drawer (or a category pane that stays open on wide windows such as tablets and unfolded foldables), category tabs that follow your finger while you swipe between them, a category picker with search, pinned and re-orderable tabs, and a layout of their own per category if you want one.
- **Filters:** unread or saved articles, a period (24 hours, 3 days, 7 days, 30 days) and hidden keywords that keep matching articles out of the list.
- **Explicit read state.** Articles are marked read, unread or saved only when you say so. Opening an article never
  marks it read.
- **Original site first.** Articles open in Custom Tabs (or your external browser); there is no built-in reader.
- **Local search** over titles, summaries and feed names.
- **Refresh** on demand (scoped to what you are looking at) and periodically in the background, with a confirmation on
  metered networks.
- **Feed housekeeping:** find the feed behind a site address, move HTTP feeds to HTTPS after verifying it is the same
  feed, and clean up dead feeds in bulk.
- **English and Japanese** UI (follows the system language; per-app language on Android 13+), light/dark theme with six color sets (Classic, Leaf, Sand, Violet, Dynamic on Android 12+, and Custom made from one color you pick), text size setting.

## Install

Folirel needs Android 9 (API 28) or later. It is distributed as an APK on the
[Releases](https://github.com/yuu-biz/folirel/releases) page of this repository.

1. On the phone, download the APK of the latest release and open it.
2. If Android says that installing unknown apps is not allowed, tap **Settings**, turn on **Allow from this source**
   for your browser or file manager, and go back.
3. Tap **Install**, open Folirel, and import an OPML file or add a feed URL.

### Check the signing certificate

Release APKs from 1.0 on are signed with one release key. To check a download on a computer with the Android SDK
build tools, run:

```sh
apksigner verify --print-certs folirel.apk
```

The line `Signer #1 certificate SHA-256 digest` must read:

```text
c740ae3b5a57bf46be12edd7894b6a017095b95a3f7217e2cc4b9402cc9b00b1
```

If it does not match, do not install the file.

### Moving from a 0.x debug build

Builds before 1.0 were signed with a debug key. Android cannot install 1.0 over them, and uninstalling deletes the
app's data, so move your data with a Folirel backup:

1. In the old app, open Settings > Storage and data > **Create Folirel backup** and save the file outside the app (for
   example in Downloads).
2. Check the backup: **Restore from Folirel backup**, pick the file, compare the preview with the app, then **Cancel**.
3. Uninstall the old app and install 1.0.
4. In 1.0, open Settings > Storage and data > **Restore from Folirel backup** and confirm.

The full procedure is in [Full backup and restore, section 7](docs/backup-format.md#7-moving-to-a-differently-signed-app-release-signing).
Builds before 0.10.0 have no full backup; use **Export OPML** there to keep the subscription list.

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

- Subscriptions, articles and read state are stored only in the app's private storage. Android's automatic backup is
  disabled; a full backup is made only when you ask for it, to a file you choose (not encrypted).
- The app contacts only the feed servers you subscribe to (and the sites and image hosts those feeds point to).
  There are no third-party SDKs and no telemetry.
- Feed content is treated as untrusted: XML with DTD/DOCTYPE or external entities is rejected, TLS verification is
  always on, plain `http://` feeds are not fetched until you allow them, and each URL is checked against private and
  local addresses before it is requested.

Privacy statement: [docs/privacy.md](docs/privacy.md). Details: [Fetch and security](docs/fetch-and-security.md).

## Documentation

| Document | Contents |
|---|---|
| [User guide](docs/user-guide.md) / [日本語](docs/user-guide.ja.md) | Installing, importing, daily use, settings |
| [Terminology](docs/terminology.md) | The English and Japanese words used in the app |
| [Product and UX](docs/product-and-ux.md) | Scope, requirements, screens and interaction rules |
| [Architecture](docs/architecture.md) | Modules, data flow, extension boundary |
| [Data and import](docs/data-and-import.md) | Database model, OPML, article identity, retention |
| [Full backup and restore](docs/backup-format.md) | Backup contents, archive format, restore safety, moving to another signing key |
| [Fetch and security](docs/fetch-and-security.md) | HTTP, parsing, images, refresh, network policy |
| [Testing](docs/testing.md) | Test layers, fixtures, scenario catalogue |
| [Building](docs/building.md) | Toolchain, build, CI, dependencies and licenses |
| [Releasing](docs/releasing.md) | Release signing, checks, GitHub Release steps, developer verification |
| [Privacy](docs/privacy.md) / [日本語](docs/privacy.ja.md) | What the app sends, keeps and never collects |

Technical documents are in English. Contributor and coding-agent conventions are in [AGENTS.md](AGENTS.md) and
[CONTRIBUTING.md](CONTRIBUTING.md).

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first; it explains the product
scope (some things are deliberately out of scope, for example accounts, a built-in reader and automatic mark-as-read).

## License

Folirel is released under the [MIT License](LICENSE). Third-party libraries keep their own licenses; see
[docs/building.md](docs/building.md).
