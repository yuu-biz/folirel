# Full backup and restore

Folirel 0.10.0 adds a **full local backup**: one file that holds everything the app keeps, and a **restore** that puts an
install back into exactly that state. It is Folirel's own format. OPML import and export stay as they are and are a
different thing:

| | OPML export / import | Full backup / restore |
|---|---|---|
| Purpose | Move subscriptions between readers | Keep or move *this* Folirel: a new phone, a reinstall, a change of signing key |
| Contents | Subscriptions and categories | Subscriptions with their history, categories with order, pins and layouts, the articles kept on the device, read and saved state, the cleanup's memory of removed articles, every setting |
| Readable by | Any feed reader | Folirel 0.10.0 and later |
| Applying it | Adds and merges; never removes or overwrites | **Replaces** all Folirel data on the device |

Both are reached from **Settings > Storage & data**: "Export subscription list (OPML)", "Create Folirel backup",
"Restore from Folirel backup". Files are chosen through the system file picker (Storage Access Framework); Folirel
asks for no storage permission and writes to no fixed path.

## 1. Privacy

The backup is not encrypted (format version 1 has no encryption). It contains the feed addresses (which may carry private
tokens), the titles and summaries of the articles kept on the device and what was read and saved. The screen says so
when it offers the backup. The file goes only where the reader saves it; Folirel sends nothing anywhere. Android's
automatic backup stays disabled for the app.

## 2. What is in a backup and what is not

**In it**

- Feeds: id, registered (subscription) URL, fetch URL, site URL, title from the feed, the reader's own title,
  subscribed, paused, created time, and whether the feed had been fetched before (`fetchedBefore`, see section 6).
- Identity history of each feed: its HTTP → HTTPS migration (http URL, https URL, time) and the address moves the
  reader approved (from, to, time). After a restore an old OPML file still matches these feeds, a migrated feed is
  never moved back to HTTP, and an older address still means the same feed.
- Categories: id, path (the names from the top category down; a name may itself contain a slash), the feeds directly
  in it, tab order, pin, and the category's own list layout, Magazine arrangement and columns (automatic or
  1 to 5; none = the global setting).
- Articles: every article on the device with its stored metadata (entry key, link, title, summary text, author,
  dates, image address), also saved articles of feeds that are no longer subscribed.
- Article state: read, saved and the time of the last change.
- Pruned entries: the record of read articles the cleanup ("Articles to keep on the device") removed, so that a feed
  that still lists them does not bring them back as new unread articles after the restore.
- Settings: theme, colour set, the Custom base colour and the Custom light and dark colours set by hand (five each),
  list layout, text size, columns, Magazine arrangement, thumbnails, category navigation, browser, update interval,
  update on start, unmetered only, HTTP allowed, the two "ask before" switches, whether a start opens the last category,
  last selection (scope, filter, order, period), hidden keywords and their switch, articles to keep, image cache size.

**Not in it**: thumbnails and the image cache, source icons (looked up again), fetch diagnostics, validators (ETag,
Last-Modified), failure counts, back-off and Retry-After waits, pending refresh requests, the time of the last refresh
attempt, the first-run flag, new-article banners, the scroll position, logs, signing information.

## 3. File format (format version 1)

The file is a ZIP named `folirel-backup-YYYYMMDD-HHmmss.zip` (local time). It holds exactly these entries, UTF-8 JSON,
**in this order** (the manifest first; every entry after the ones it refers to):

| Entry | Content |
|---|---|
| `manifest.json` | Object: `format` = `"folirel-backup"`, `formatVersion` = `1`, `createdAt` (UTC epoch ms) and `createdAtUtc` (ISO 8601), `account` = `"local"`, `app` {`versionName`, `versionCode`}, `schemaVersion` (the Room schema of the writing app; informational), `counts` {`feeds`, `subscribedFeeds`, `categories`, `memberships`, `articles`, `readArticles`, `savedArticles`, `prunedEntries`, `hiddenKeywords`} |
| `settings.json` | Object with the settings of section 2. Enums by name (`"DARK"`, `"CUSTOM"`, `"BENTO"`…), colours as `"#RRGGBB"`, `customLight` / `customDark` an object {`accent`, `container`, `secondary`, `background`, `text`} or `null`, text size as `textScalePercent`, `lastScope` as `"all"`, `"uncategorized"`, `"category:<id>"` or `"feed:<id>"`, `restoreLastScope` a boolean (0.10.1; an archive of 0.10.0 has none, which reads as `false`) |
| `subscriptions.json` | Array of feeds: `id`, `subscriptionUrl`, `fetchUrl`, `siteUrl`, `feedTitle`, `userTitle`, `subscribed`, `paused`, `createdAt`, `fetchedBefore`, `httpsMigration` ({`httpUrl`, `httpsUrl`, `migratedAt`} or `null`), `urlMoves` ([{`id`, `fromUrl`, `toUrl`, `movedAt`}]) |
| `categories.json` | Array: `id`, `path` (array of names), `feedIds`, `tabOrder`, `pinned`, `listMode`, `cardStyle`, `cardColumns` (`null` = inherit, `0` = automatic, `1`–`5`) |
| `articles.json` | Array: `id`, `feedId`, `entryKey`, `externalEntryId`, `linkUrl`, `title`, `summaryText`, `author`, `publishedAt`, `firstSeenAt`, `sortAt`, `imageUrl`, `lastSeenAt` |
| `article_states.json` | Array: `articleId`, `read`, `saved`, `updatedAt` |
| `pruned_entries.json` | Array: `feedId`, `entryKey`, `prunedAt`, `lastSeenAt` |

All times are UTC epoch milliseconds. The arrays are written one record per line and read one record at a time, so a
large backup is never built or parsed as one string in memory.

**Ids.** The ids are the stable ids of the install that wrote the file (Room primary keys, UUIDs) and are kept by a
restore, so article state, category settings and feed history keep pointing at the same things. The id of the local
account is not in the file: a restore keeps the account of the install it is applied to.

**Format version and Room schema are independent.** `formatVersion` changes only when the archive's meaning changes; a
Room migration does not change it, and the restore writes through the current schema whatever `schemaVersion` says.

**Evolution.** A reader of version 1 ignores JSON fields it does not know, so a later version can add optional fields
(for example metadata) without a new version. A new entry, encryption, or anything an older reader must not silently
ignore needs a new `formatVersion`; a reader refuses every version it does not know, before reading anything else of the
archive ("made by a newer version of Folirel").

## 4. Creating a backup

1. The settings are read.
2. One Room transaction reads every table and writes the archive to a file in the app's private storage while it is
   held. The archive is therefore one consistent snapshot: a refresh, a read/saved change or a category change at the
   same time lands either entirely before or entirely after it, never half. (Settings and the database cannot share a
   transaction; a setting changed between step 1 and 2 belongs to the next backup.)
3. The file is copied to the document the reader created and deleted. If the copy fails, the reader is told and the
   half-written document is deleted where the storage provider allows it.

## 5. Restoring

A restore **replaces** the current Folirel data; there is no merge in version 1. Nothing is changed before the reader
has seen what the backup holds and confirmed.

1. **Copy.** The picked file is copied into app-private storage (at most 256 MiB; larger is refused while copying).
2. **Check everything** (nothing is changed):
   - ZIP: only the seven entry names, each once, in order; no directories, no paths (`/`, `\`, `..`, `:`), no unknown
     entries; an entry of the wrong name first means "not a Folirel backup" (an OPML file or another ZIP is refused
     with that message). Sizes are counted on the bytes actually inflated, whatever the entry declares: at most
     1 MiB for the manifest and the settings, 768 MiB per entry, 1 GiB in total, and no more than 200 inflated bytes
     per compressed byte once 1 MiB has been inflated (ZIP bombs).
   - JSON: well formed, required fields present with the right type, `format` and `formatVersion` known.
   - Values: enum names this version knows (an unknown name is refused, never rounded to a default), columns 0–5,
     text size on the 5% grid from 90% to 130%, update interval, article limit and cache size among the offered values,
     complete Custom colours `#RRGGBB`, hidden keywords as the app stores them (normalized, unique, at most 32 of at most
     64 characters), category names not empty, times in range, text within a safety bound of 64 Ki characters. Text that
     an older version stored is not judged by today's input rules (spaces, normalization, the parser's length limits), so
     the reader's own backup is never refused for it.
   - Addresses: feed, site, article and image addresses must be `http`/`https` addresses the URL rules accept (no
     other scheme, no user name or password, at most 8 KiB). They are checked as text only.
   - Consistency: no duplicate ids, subscription URLs, category paths, article keys (feed + entry key), states,
     memberships or pruned entries; every reference points at something in the archive; every category's parent exists;
     every article has exactly one state; the manifest's counts match the content (a cut-off file is caught here).
   - Local account semantics: `account` must be `"local"`.
3. **Preview.** Creation time, app version and format version; feeds (subscribed / unsubscribed), categories, articles
   (unread), saved, hidden keywords, theme and colour set; and what is on the device now and will be replaced.
4. **Confirm.** "Replace with this backup", then a second confirmation. Cancel deletes the private copy; nothing else
   happens.
5. **Apply.**
   - Background work stops first: no refresh, icon lookup or HTTPS check starts, running refreshes are cancelled (what
     they finished stays applied) and the restore waits up to 20 seconds for them to end. If they do not (or an HTTPS
     check is running), the restore stops with "still running, try again" and nothing is changed.
   - A note with the current settings is written to app-private storage.
   - The settings are replaced in one DataStore write through the settings repository (never by copying the settings
     file; a damaged settings file is still handled as before). The device's own bookkeeping (time of the last refresh
     attempt) is kept; first-run setup counts as done.
   - The database is replaced in one Room transaction: all local data is deleted and the archive is read a second time,
     checked again record by record and inserted. The transaction also stores a mark of this restore in the database
     header (`PRAGMA application_id`) in the same commit.
   - The note is deleted.
6. **After.** Screens show the new data at once. The article list opens as a start would with the restored settings: on the
   restored last selection's scope when "open the last category at start" is on (All when it is off, or when the archive's
   selection names a category or feed it does not have), with the restored filter, order, period and hidden
   keywords, no search and from the top. The theme, colour set and Custom colours apply at once.

### Failure safety

| What fails | Result |
|---|---|
| The archive is refused at any check | Nothing changed (data and settings) |
| Background work does not stop | Nothing changed; the reader can try again |
| Writing the note or the settings | Nothing changed (one DataStore write is all or nothing) |
| The database replace (any row, any point before commit) | The transaction is rolled back and the old settings are written back: nothing changed |
| …and writing the old settings back fails too | The data is unchanged; the note stays and the next start writes the old settings back |
| The app is killed during the restore | At the next start, before anything reads the settings: if the database carries this restore's mark, the restore completed (the new settings are already in place); otherwise the old settings are written back. Either way data and settings belong together |

These cases are tested with injected failures (`RestoreRollbackTest`).

### No network during a restore, no refresh after it

Checking and restoring never touch the network: addresses in an archive are only read as text, so a hostile archive
cannot make the app contact anything. A restore starts no refresh by itself. The next manual, start-up or periodic
update follows the usual rules and the usual network policy (address policy, HTTPS, the HTTP setting). If the restored
settings switch periodic updates on where they were off, their first run waits one interval instead of starting at
once. Feed icons are looked up again by the normal icon lookup when it next runs.

## 6. Decisions

- **Pruned entries are included** (first choice of the specification). Without them, articles the cleanup removed
  would come back as new unread articles at the first refresh after a restore. The cleanup's own rule applies at
  restore time: entries not seen for 90 days are not restored.
- **`fetchedBefore` is kept** although the rest of the fetch bookkeeping is not: without it every feed would show "not
  fetched yet" and the first refresh would treat its articles as initial content (no new-arrival notice). No validator
  or failure state comes with it, so the first refresh is an ordinary unconditional request.
- **The last refresh attempt is not restored** and not reset either: it is bookkeeping of the install (the start-up
  update rule), so the install keeps its own value.
- **Stable ids are kept**, with full duplicate and reference checks, so that the logical state after a restore is
  exactly the backed-up one.
- **Only Replace.** A merge would have to decide between two read states, two settings and two category trees; version
  1 does not try.

## 7. Moving to a differently signed app (release signing)

Android does not install an APK over an app signed with another key; the old app must be uninstalled, which deletes its
data. The full backup is the way across:

1. In the installed (debug-signed) Folirel 0.10.0 or later: Settings > Storage & data > **Create Folirel backup**, and
   save the file outside the app (for example Downloads or a cloud folder).
2. **Check the file before uninstalling.** In the same app: **Restore from Folirel backup**, pick the file just saved,
   wait for the preview, compare its counts with the app (feeds, articles, saved), then **Cancel**. The preview runs
   every check a restore runs and changes nothing, so a backup that shows its preview can be restored; one that is
   refused must not be relied on (keep the old app and report the message).
3. Optionally take an OPML export as a second copy of the subscription list.
4. Uninstall the old app.
5. Install the differently signed (release) APK and open it. Complete or skip the first-run screen.
6. Settings > Storage & data > **Restore from Folirel backup**, pick the file, compare the preview with step 2, confirm.
7. Check: subscriptions and categories (order, pins, layouts), unread and saved counts, a saved article of an
   unsubscribed feed, the theme and Custom colours, hidden keywords and period, then a manual update.

The release key and the switch to it are not part of 0.10.0; this procedure is carried out at the release candidate.
