# Changelog

Folirel follows [semantic versioning](https://semver.org/) from 1.0.0 on. Versions before 1.0.0 were debug-signed test
builds; their history is in the git log and the old GitHub Releases.

## 1.0.0

The first release signed with the Folirel release key. **It cannot be installed over a 0.x build.** Before you uninstall
0.x, create a Folirel backup (Settings > Storage and data), open it in Restore to check that the counts appear, then cancel.
Uninstall 0.x, install 1.0.0 and restore the backup. See [docs/backup-format.md](docs/backup-format.md) §7.

### Added
- Add feed URL also takes a site address and lists the feeds the site declares.
- JSON Feed 1.0 and 1.1.
- Swipe actions in List and Compact list (off by default; Settings > Layout and navigation).
- Undo for mark all as read, for a row that leaves a filtered list, and for unsubscribing one feed.
- Screen transitions, a preview of the back gesture, and rows that slide instead of jumping.
- A splash screen in the app's colours until the settings are read.
- Settings > About: source code, privacy policy and a licences screen with the full licence texts.

### Changed
- Every screen's text was rewritten in English and Japanese with one set of terms ([docs/terminology.md](docs/terminology.md)).
- Feeds that write HTML names such as `&nbsp;` straight into the XML are read instead of being refused.
- A feed that gives its articles new ids keeps their read and saved state instead of showing them again as new.
- The article list reads large databases about three times faster.
- Empty lists offer the way out (clear the search, turn off filters, show all articles).
- The feed details screen puts rename, categories, pause and unsubscribe first.
- Periodic refresh waits while the battery is low.
- The release build is shrunk with R8 (about 5 MB instead of about 40 MB).

### Fixed
- A device without a file picker no longer crashes when you import or export.
- The first-run choice is saved even if the screen rotates.
- Folirel no longer offers itself when you share an article.
