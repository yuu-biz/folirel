# Data model, OPML and reading state

How Folirel stores subscriptions and articles, how entries are identified across fetches, and how OPML import and
export behave. Fetching and network rules are in [fetch-and-security.md](fetch-and-security.md); module layout is in
[architecture.md](architecture.md). Product overview: [../README.md](../README.md).

Section numbers are stable and are cited from source code comments (for example "data-and-import.md section 3" for entry
identity).

## 1. Room model

Database `folirel.db`, schema version 3, exported to `app/schemas/`. All timestamps are UTC epoch milliseconds;
display uses the device time zone. UI code never touches entities directly; it goes through repositories (see
[architecture.md](architecture.md) section 3).

| Entity (table) | Content and constraints |
|---|---|
| Account (`accounts`) | `id` (UUID), `backendType` (`LOCAL`), `displayName`, `createdAt`. One local account is created on first use. |
| Feed (`feeds`) | `id` (UUID), `accountId`, `subscriptionUrl`, `fetchUrl`, `siteUrl?`, `feedTitle?`, `userTitle?`, `isSubscribed`, `isPaused`, `createdAt`. UNIQUE(`accountId`, `subscriptionUrl`). |
| FeedFetchState (`feed_fetch_state`) | `feedId` (PK), `etag?`, `lastModified?`, `validatorUrl?`, `lastAttemptAt?`, `lastSuccessAt?`, `lastErrorCode?`, `failureCount`, `nextEligibleAt?`, `initialized`, `retryAfterUntil?`. |
| Category (`categories`) | `id`, `accountId`, `name` (last path segment, for display), `pathSegmentsJson`. UNIQUE(`accountId`, `pathSegmentsJson`). |
| FeedCategory (`feed_categories`) | `feedId`, `categoryId`; composite PK. Deleting a category cascades to its rows. |
| Article (`articles`) | `id` (UUID), `accountId`, `feedId`, `entryKey`, `externalEntryId?` (reserved, currently always null), `linkUrl?`, `title`, `summaryText`, `author?`, `publishedAt?`, `firstSeenAt`, `sortAt`, `imageUrl?`, `lastSeenAt`. |
| ArticleState (`article_states`) | `articleId` (PK), `isRead`, `isSaved`, `updatedAt`. Independent of article updates. Deleted with its article (cascade). |

Supporting tables (fetch diagnostics, retention record, address-change records, icon cache) are described in
section 8.

Notes:

- `subscriptionUrl` is the identity key used to match OPML entries; `fetchUrl` is where the feed is actually
  fetched (section 2).
- Every feed and category belongs to an account. Replacing a feed's categories rejects a category of another
  account; this is a repository check, not a database constraint.
- `externalEntryId` is nullable so that a future remote backend can store its own identifiers without overloading
  the local UUID or the URL-derived `entryKey`.
- Category paths are stored as a JSON array of NFC-normalized segments. A segment may itself contain `/`; because the
  array items are separate, such names cannot collide with a deeper path. The UI joins segments with ` / `.

Indexes:

| Table | Index |
|---|---|
| `feeds` | UNIQUE(`accountId`, `subscriptionUrl`) |
| `articles` | UNIQUE(`accountId`, `feedId`, `entryKey`); (`accountId`, `sortAt`, `id`); (`feedId`, `sortAt`) |
| `article_states` | (`isSaved`, `articleId`) |
| `feed_categories` | (`categoryId`, `feedId`) |

List queries select articles in a category with `EXISTS`, never a join, so a feed placed in several selected
categories yields each article once.

Preferences (display, update and selection settings) live in a separate DataStore file, not in Room. Subscriptions
and reading state are never stored in preferences. The keys are listed in section 8. Full-text search tables are not
used (see [architecture.md](architecture.md) section 5).

## 2. URL identity

HTTP(S) URLs are parsed with OkHttp's `HttpUrl` and normalized: scheme and host lower-cased, IDN handled by the
library, default port omitted, empty path becomes `/`, fragment removed. Path case, trailing slash, and the query
(values, order, duplicates) are preserved. Input over 8 KiB, non-HTTP(S) schemes and URLs with user info are rejected.

The following are deliberately **not** treated as the same URL: `http` versus `https`, different hosts, different
queries. There is no `www` stripping, no tracking-parameter removal and no query reordering.

Two URLs are kept apart:

- `subscriptionUrl` is the registered URL and the OPML re-import identity. It is never changed automatically.
- `fetchUrl` is the current fetch target. Initially equal to `subscriptionUrl`. After a successful fetch that
  followed a leading run of 301/308 redirects, `fetchUrl` is updated to the redirect target. It is also changed by an
  approved HTTPS migration or an approved address move (section 8). In those cases `subscriptionUrl` stays as it was.

Two different registered URLs that resolve to the same target are not merged automatically.

When an OPML file or the add-feed dialog supplies a URL, the existing feed is looked up in this order: registered
`subscriptionUrl`, then current `fetchUrl`, then the https URL of a recorded HTTPS migration, then any address
recorded in an approved address move (old or new). When several feeds match the same fetch address the earliest
created one wins. Preview and commit use the same lookup, so the counts shown equal what the import will do.

## 3. Entry identity and updates

`entryKey` is unique per feed and chosen in this order:

1. `id:` followed by the trimmed RSS `guid`, Atom `id` or RSS 1.0 `rdf:about`, if non-empty. The value is opaque and
   is not URL-normalized.
2. Otherwise `url:` followed by the normalized article URL.
3. Otherwise `fallback:` followed by the lower-case hex SHA-256 of a compact JSON array
   `[normalizedTitle, publishedAtOrNull, normalizedAuthor, normalizedSummary]`. Text is NFC with whitespace runs
   collapsed to one space and trimmed; a missing author is the empty string; the date is the epoch-millisecond
   integer or JSON `null`. The JSON has no whitespace between elements, non-ASCII characters are written as UTF-8,
   only `"`, `\` and control characters are escaped, and there is no BOM. Receive time and list position are not part
   of the digest.

`fallback:` is best effort for incomplete feeds: items with identical content cannot be told apart, and an item
whose text changes later becomes a different article. Full de-duplication is not guaranteed.

**Re-fetch.** A known `entryKey` updates the article's metadata (link, title, summary, author, publishedAt, image,
`lastSeenAt`) and preserves `id`, `firstSeenAt`, `sortAt` and the article's `article_states` row. Articles are
never written with an "insert or replace" conflict strategy, because that would delete the row and cascade to its
state. `ArticleState` is created only when an article is first inserted (`isRead = false`, `isSaved = false`).
Within one response, if a key occurs twice, the first item wins and later items only fill missing fields.

**Matching an existing article** (in `FeedStore.applySuccess`), tried in this order:

1. exact `entryKey`;
2. the `http`/`https` twin of an `id:`/`url:` key (always applied, so a feed that moves to HTTPS does not turn old
   articles into new unread ones);
3. only for feeds with a recorded address change (HTTPS migration or approved move): another spelling of the same
   address, where `id:`/`url:`, `http`/`https` and a leading `www.` may differ (`EntryKeyAliases`). Only URL-shaped
   keys are related; opaque GUIDs, fallback digests and titles never match, nothing is compared across feeds, and
   case, trailing slash and query order still make keys different. Stored keys and GUIDs are never rewritten.

If no article matches, the key is checked against the retention record (section 6); an entry removed earlier by the
retention cleanup is not inserted again. Otherwise the article is inserted.

**`sortAt`** is set once at first insert: `publishedAt` if it is not more than 24 hours in the future of the receive
time, otherwise the receive time. It is never changed when `publishedAt` is later corrected. Very old dates are kept.
Unparseable dates are stored as null. List order is (`sortAt`, `id`) in either direction, which is stable.

## 4. OPML import

Accepted input is OPML 1.x/2.0 with `body/outline`. An outline with an `xmlUrl` attribute is a feed candidate
whether or not it has a `type`. Attribute names are case-sensitive: `xmlUrl` is the feed attribute, `htmlUrl` is the
site URL and is never used as a feed URL. The label is `title`, else `text` (NFC, trimmed); it is stored as the
feed's `userTitle` and kept separate from the title the feed itself reports. Display name order is `userTitle`,
`feedTitle`, host.

Outline nesting becomes the category path. A folder with an empty name is skipped and its children are promoted to
the parent. Segment text is trimmed and NFC-normalized; case is preserved. Identical paths merge; the same name under
different parents stays separate. An empty named folder is kept as an empty category and counted in the preview.

A feed URL that appears under several folders produces one feed with several memberships. The first label seen in
the file is the initial name; later labels do not overwrite it. If the same URL also appears at top level, no
artificial "uncategorized" category is created: *Uncategorized* is a computed scope containing subscribed feeds with
no memberships.

Unsupported or unusual outlines:

- Targets of `type=include`/`link` are never fetched.
- A leaf without `xmlUrl` and without children is reported as unsupported if its `type` is not empty, `folder` or
  `category`, or if it carries `xmlurl`, `htmlurl` or `url` attributes in any letter case (a mis-cased `xmlurl` is
  therefore reported, not accepted). Otherwise a named leaf is an empty category.
- An outline with `xmlUrl` that also has children is registered as a feed, and a warning is counted; its children
  are scanned at the same parent level.
- A candidate whose URL is invalid or unsafe (empty, too long, unsupported scheme, user info) is excluded and counted
  as invalid.

Limits: file 5 MiB, XML element depth 32, 10,000 outlines, 5,000 feed candidates. The file is decoded with the same
BOM/XML-declaration rules as feeds (see [fetch-and-security.md](fetch-and-security.md) section 1) and parsed by the
hardened XML reader described in section 6 of that document.

### Processing order

1. The user picks a file through the Storage Access Framework. Reading stops once the size limit is exceeded.
2. The whole file is parsed safely. A DOCTYPE or entity declaration, a limit violation or malformed XML rejects the
   whole file; nothing is registered.
3. Structurally valid entries with unusable URLs are excluded individually. Valid `http://` URLs are kept as
   candidates; they are counted as "held back" only when the feed would really be fetched over `http://` while the
   HTTP setting is off (a feed already migrated to HTTPS is not held back whatever scheme the OPML spells).
4. A preview diffs the file against the database: new feeds, existing feeds, unsubscribed feeds that will be
   subscribed again (the count and up to ten names are shown), added category memberships, multi-category feeds,
   HTTP feeds, invalid URLs, unsupported items, new and empty categories, warnings. Several OPML URLs that resolve to
   the same existing feed count once.
5. Only on confirmation, one Room transaction registers feeds and adds memberships. A failure rolls everything back.
6. Existing feeds keep their user-given name, memberships, and reading and saved state. Re-importing only adds
   memberships.
7. A feed that was unsubscribed earlier is re-enabled with its original id instead of creating a new feed.
8. Database registration and network fetching are separate outcomes. After a successful commit, new and
   re-subscribed feeds are fetched as an import-triggered refresh; if every fetch fails, the registration is not
   rolled back.

Category order is a stable sort by name. A custom ordering from another service is not preserved.

## 5. OPML export

The file is created through the Storage Access Framework (`CreateDocument`) as UTF-8 OPML 2.0 with a
`dateCreated` header. Subscribed feeds (`isSubscribed = true`) are exported, including paused feeds and feeds held
back because they use HTTP. No network access takes place.

Each feed is written with `title`, `text`, `xmlUrl` and `htmlUrl` (when known). The title is the display name
(`userTitle`, `feedTitle`, host). The category hierarchy is written as nested outlines; a feed in several categories
is repeated under each; uncategorized feeds are written directly under `body`. Feeds and categories are sorted by
name. Attribute values are XML-escaped and characters not allowed in XML 1.0 are dropped. Articles, reading state,
settings and images are not exported.

For a feed that had its address changed (HTTPS migration or approved move) the **current fetch URL** is written as
`xmlUrl`, so importing the file again finds the same feed.

Exporting and re-importing in the same installation yields an equivalent set of subscription URLs and direct
category memberships. A cancelled file dialog or a write failure produces no success message. Subscription URLs can
contain tokens or personal data, so the exported file should be handled accordingly.

## 6. State, deletion and capacity

**Reading state.** A read or saved change is confirmed only after the database transaction succeeds; the UI is driven
by Room flows rather than by optimistic local state, and a failure is reported. Fetching never overwrites reading
state. Opening an article never marks it read; the "mark all read" action works on a snapshot of the unread ids at
confirmation time, so later arrivals are not included.

**Unsubscribing** is a soft operation (`isSubscribed = false`). Articles, saved state, memberships, diagnostics and
icons remain; the feed disappears from normal lists, and the *Saved* filter still lists its saved articles.
Subscribing again (including through OPML import) restores the same feed row. Bulk unsubscribe runs in one
transaction on a fixed id snapshot, touches only feeds that are still subscribed, and returns the ids it actually
changed so an undo restores exactly those. Nothing is ever unsubscribed automatically, whatever the failure reason.

**Categories.** Creating a category under a parent fails if the path already exists. Deleting a category deletes it
and its descendants together with their memberships; feeds and articles are not deleted.

**Retention (optional).** By default articles are never removed ("no limit"). The user can set "articles to keep on
this device" to 1,000, 3,000, 5,000, 10,000 or 30,000. The limit applies to the whole account, each article counted
once. When a limit is set (after a confirmation dialog that previews the effect) and again after each refresh, only
the oldest articles that are **read and not saved** are deleted until the count is within the limit; unread and
saved articles are protected, so the count may stay above the limit. Each deleted article is recorded in
`pruned_entries` (feed id and `entryKey`). A feed that keeps serving an entry recorded there does not bring it back
as a new unread article; the check also applies to the `http`/`https` twin of a key and, after an address change, to
other spellings of the same address. A record that no response has mentioned for 90 days is forgotten.

**Capacity.** Only short metadata is stored (summary text is at most 2,000 code points; no full article HTML).
Settings show article counts and database size.

**Schema and backup.** Schema changes use explicit, additive migrations; destructive fallback is not configured.
Uninstalling the app removes local state. Android backup and device transfer are disabled in the manifest and in the
data extraction rules because URLs and subscriptions may be personal. OPML export is the only backup mechanism and is
not a complete backup (it excludes articles and reading state).

## 7. Renaming categories

Renaming a category updates its own path and all descendant paths in one transaction, keeping category ids and
memberships. If any resulting path would equal the normalized path of another category, nothing is changed and the
conflict is reported (categories are never merged automatically). The update is done in two phases to avoid transient
uniqueness collisions. A name containing `/` remains one segment.

## 8. Supporting tables, schema versions and settings

### Schema versions

| Version | Change (all additive) |
|---|---|
| 1 | Accounts, feeds, fetch state, categories, memberships, articles, article states. |
| 2 | Column `feed_fetch_state.retryAfterUntil`; tables `feed_diagnostics`, `pruned_entries`, `feed_https_migrations`. |
| 3 | Tables `feed_icons`, `feed_url_moves`. |

Migrations are `CREATE TABLE`/`CREATE INDEX`/`ADD COLUMN` only and never rewrite existing rows. Instrumented
tests (`MigrationTest`) check 1 to 2, 2 to 3 and the chained 1 to 3 upgrade against the exported schemas.

### Supporting tables

| Table | Purpose |
|---|---|
| `feed_diagnostics` | Per-feed record of the last request (id, reason, scope, forced), its result, a `pendingSince` marker that survives process death, the last HTTP attempt (status, whether validators were sent, error code and detail), and the last applied body (bytes, raw/unique/undated entry counts, oldest/newest date, inserted/updated/skipped-by-retention counts). A 304 or a failure never clears the last body statistics. Diagnostics are explanatory; scheduling depends on them only through `pendingSince`, which selects feeds for "retry unfinished". |
| `pruned_entries` | Retention record described in section 6. PK (`feedId`, `entryKey`); `prunedAt`, `lastSeenAt`. |
| `feed_https_migrations` | One row per feed moved from `http://` to `https://` after consent and verification: `httpUrl`, `httpsUrl`, `migratedAt`. |
| `feed_url_moves` | History of approved fetch-URL changes: `id`, `feedId`, `fromUrl`, `toUrl`, `movedAt`. |
| `feed_icons` | Persistent cache of source icons, see below. |

**Address changes.** An HTTPS migration or an approved move (see
[fetch-and-security.md](fetch-and-security.md) sections 8 and 9) changes only `feeds.fetchUrl`. The feed id,
categories, articles, reading state and `subscriptionUrl` stay. The old and new addresses are recorded, so an OPML
file or add-feed input naming either address resolves to the same feed (lookup order in section 2). A move to an
address that already belongs to another subscription is refused rather than merged, and the old address's errors and
waiting times are cleared when a move is applied. A fetch result is applied only if the feed is still fetched from
the URL the request started with; otherwise it is dropped as a whole (`ApplyResult.Superseded`).

**Icon cache (`feed_icons`).** `feedId` (PK), `declaredUrl` (icon URL the feed document declares), `status`
(`PENDING`, `OK`, `NONE`, `FAILED`), `sourceKind` (`FEED`, `HTML`, `FAVICON`, `BANNER`), `sourceUrl`, `png`
(normalized to at most 96 px on the long side), `width`, `height`, `resolvedAt`, `nextAttemptAt`, `failureCount`.
`declaredUrl` is recorded when a fetch is applied; a change makes the lookup run again at once. An icon lookup or its
failure never changes the feed's fetch state. The lookup procedure and schedule are in
[fetch-and-security.md](fetch-and-security.md) section 8.

### Preferences (DataStore)

| Key | Meaning | Default |
|---|---|---|
| `theme` | `SYSTEM`, `LIGHT`, `DARK` | `SYSTEM` |
| `list_layout` | `LIST`, `COMPACT_LIST`, `MAGAZINE` | `LIST` |
| `card_columns` | Maximum Magazine columns: 0 = automatic, 1 to 3 | 0 |
| `card_style` | Magazine arrangement: `MIXED` or `EVEN` | `MIXED` |
| `text_scale` | 0.9, 1.0, 1.15, 1.3 | 1.0 |
| `show_images` | Thumbnails and source icons | on |
| `browser_mode` | `CUSTOM_TABS` or `EXTERNAL` | `CUSTOM_TABS` |
| `refresh_interval_minutes` | 0 (off), 15, 30, 60, 120, 360, 720, 1440 | 120 |
| `startup_refresh` | Refresh when the app is opened | on |
| `unmetered_only` | Automatic refresh and icon lookups only on unmetered networks | on |
| `allow_http` | Fetch `http://` feeds and images | off |
| `confirm_http_open` | Ask before opening an `http://` article | on |
| `retention_limit` | 0 (no limit), 1,000 to 30,000 | 0 |
| `image_cache_mb` | Thumbnail disk cache: 64, 128, 256, 512 (applied at next start) | 128 |
| `onboarding_done` | First-run screen completed | off |
| `last_scope`, `last_filter`, `sort_order` | Last selection | all / all / newest |
| `last_refresh_attempt` | Start time of the last refresh of any kind | 0 |

A missing `startup_refresh` follows whether the interval is non-zero.
