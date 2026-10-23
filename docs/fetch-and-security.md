# Fetching, refresh and security

How Folirel downloads and parses untrusted feeds, schedules refreshes, and restricts network access. Stored data and
entry identity are in [data-and-import.md](data-and-import.md); module layout is in
[architecture.md](architecture.md). Product overview: [../README.md](../README.md).

Section numbers are stable and are cited from source code comments (for example "fetch-and-security.md section 6" for the network
policy).

## 1. Supported formats

RSS 2.0, Atom 1.0 and RSS 1.0 (RDF) are supported. Elements are matched by namespace URI and local name, never by
prefix. Not supported: Atom 0.3 (`UNSUPPORTED_FORMAT`), JSON Feed, vendor-specific JSON APIs. An HTML page in place of
a feed is reported as `NOT_A_FEED`; feed URLs are not discovered from arbitrary HTML when adding a feed. (The only
HTML reading is the user-triggered search for a moved feed and the check of an address shared from another app, both section 8.)

| Field | RSS 2.0 | Atom 1.0 | RSS 1.0 |
|---|---|---|---|
| ID | `guid` | `id` | `rdf:about` |
| Title | `title` (character entities decoded, tags not interpreted) | `title`, interpreted by `type` (text, html, xhtml) | `title` (as RSS 2.0) |
| Link | `link`; a `guid` is also a link candidate unless `isPermaLink="false"` | `link rel="alternate"` (a missing `rel` counts as alternate); entries without `type` or with `text/html` are preferred | `link` |
| Published | `pubDate`, then `dc:date` | `published`, then `updated` | `dc:date` |
| Author | `author`, then `dc:creator` | entry `author/name`, then `source/author/name`, then feed `author/name` | `dc:creator` |
| Summary | `description`, then `content:encoded` | `summary`, then `content` | `description`, then `content:encoded` |

Feed-level data used by the app: title, site URL (RSS `link`, Atom alternate link, RSS 1.0 channel `link`) and an
icon hint (RSS `image/url`, Atom `icon` then `logo`, RSS 1.0 `image/url`).

Links and image URLs are resolved against `xml:base` (inherited down the tree) and otherwise against the final URL of
the response. An `xml:base` that is not a valid http(s) URL is ignored. A `guid` with `isPermaLink="false"` is an ID
only and is never used as the article URL.

Dates: RFC 822/1123 variants and ISO 8601/RFC 3339. Zone names understood are GMT, UT, UTC, Z, the US abbreviations
EST/EDT/CST/CDT/MST/MDT/PST/PDT and JST, plus numeric offsets. A value without a usable time zone, and a date without a
time, yields `publishedAt = null`; the device time zone is never assumed. A bad date does not drop the entry.

Encoding: the response is decoded to text before XML parsing, so the parser cannot re-detect the encoding. Precedence
is BOM, then XML declaration, then HTTP `Content-Type` charset, then UTF-8. (This deliberately differs from
RFC 7303.) UTF-16 without a BOM is recognized by byte pattern. Unknown charset names fall through to the next source.
Shift_JIS labels (`shift_jis`, `shift-jis`, `sjis`, `x-sjis`, `ms_kanji`, `csshiftjis`) are decoded as `windows-31j`
(CP932), because Japanese feeds labelled Shift_JIS commonly contain CP932 extension characters.

Failure scope: one malformed item (bad date, URL or image) does not discard the other items. XML syntax errors,
security violations and limit violations fail the whole response, and the previously stored data is kept.

Fixed limits: 5 MiB per response (after decompression), 2,000 entries, XML depth 64. See section 6 for the other XML
limits.

## 2. HTML is never rendered

Summaries are converted to plain text with jsoup (`parseBodyFragment`). `script`, `style`, `iframe`, `frame`,
`frameset`, `object`, `embed`, `noscript`, `template`, `svg`, `math`, `canvas`, `audio`, `video`, `form`, `button`,
`select`, `textarea`, `head` and `title` elements are removed, entities are decoded and whitespace is normalized.
Stored limits (Unicode code points, never splitting a surrogate pair): summary 2,000, title 500, author 256; URLs up
to 8 KiB. List rows read only the first 300 characters of the summary.

Nothing is executed or displayed through a WebView. Article HTML is not stored, no article page is fetched, and no
content extraction service is used.

## 3. Thumbnails

An entry's image is the first of the following that yields a usable URL:

1. `media:thumbnail` (also inside `media:group`);
2. `media:content` that is an image (`medium="image"` or an `image/*` type);
3. an `image/*` enclosure (RSS `enclosure`, Atom `link rel="enclosure"`);
4. the first `img` with a `src` in the summary or content HTML (the first `img` inside an XHTML text construct is
   also considered).

`image/svg+xml` types are skipped in 2 and 3. Relative URLs are resolved against the base, and only http(s) URLs
without user info are accepted (`data:`, `file:`, `content:`, `javascript:` and `intent:` URLs are rejected as
unsupported schemes). Images whose `width` or `height` attribute is explicitly 1 px or less are treated as tracking
pixels and skipped. `srcset`, Open Graph metadata and lazy-load attributes are not used.

Image requests use Coil 3 with the shared network policy (section 6). The image client has its own dispatcher (4
requests, 2 per host) and rejects any body over 8 MiB, whether declared or counted while reading; the count is taken after gzip is removed, so a small compressed body cannot expand past the limit (0.9.4). Coil's service-loader
discovery is disabled, so no other fetchers exist, and no SVG decoder is installed. The disk cache is a setting (64,
128, 256 or 512 MiB, default 128, applied at the next app start). Coil trims the cache only after it writes an entry, so the
first screen of each process also asks it to trim once when the cache holds more than its size (the least recently used images
go first); within the size nothing happens. Thumbnails are loaded only for rows that are
composed; there is no bulk prefetch, and the app does not pre-warm the browser for article links.

With "show images" off, no thumbnail is requested and no source-icon lookup runs (stored icons are hidden, not
deleted). An image failure never affects the article or the feed.

## 4. HTTP fetching

Client configuration (OkHttp 5), shared by every request the app makes:

| Setting | Value |
|---|---|
| Methods | GET only |
| Timeouts | connect 10 s, read and write inactivity 10 s, whole call 25 s |
| Redirects | handled manually, at most 5 hops, loops fail; every hop is validated before it is contacted |
| Cookies | none (`CookieJar.NO_COOKIES`), no authentication headers |
| HTTP cache | none |
| Proxy | direct connections only (`Proxy.NO_PROXY`) |
| User-Agent | `Folirel/<version> (Android; local RSS reader)` |

Because inactivity timeouts are shorter than the call budget, a stalled TLS handshake fails after 10 s and OkHttp's
connection retry can still try again within the 25 s budget; certificates are verified in every attempt.

**Size limits.** A response over 5 MiB (declared length, or counted after transparent gzip decompression) or with more
than 2,000 entries fails as `LIMIT_EXCEEDED` with a detail such as `BODY_SIZE` or `ENTRY_COUNT`. The response is not
stored and entries are never silently truncated. Limits are constants in `Limits`.

**Conditional requests.** `ETag` and `Last-Modified` are stored with the URL that issued them (`validatorUrl`) and
are sent only to the request hop with that exact URL. They are replaced only after a 200 response has been parsed and
applied; failures keep the previous validators. A 304 counts as success only when the feed was already `initialized`
and validators were actually sent. Otherwise (a first fetch, or no validators) the request is repeated once
unconditionally, and a second 304 is reported as `UNEXPECTED_NOT_MODIFIED`.

**Forced refresh.** A forced request sends no stored validators and adds `Cache-Control: no-cache`. The app has no
local HTTP cache, so the header only asks caches on the path to revalidate; it cannot force a server or CDN to return
fresh content.

| Result | Handling |
|---|---|
| 200 with a valid feed | Applied in one database transaction per feed; reading state is preserved; validators, `initialized`, `lastSuccessAt` are updated and failure counters reset. |
| 304 | Content unchanged; success time updated; previous body statistics kept (see the 304 rule above). |
| 301 / 308 | Followed after the safety checks. If the fetch succeeds, `fetchUrl` is updated to the target of the leading run of 301/308 hops. The run ends before a hop from `https://` to `http://`: with HTTP allowed such a redirect is followed for that fetch, but it never becomes the feed's address (0.9.4). The registered URL is not changed. |
| 300 / 302 / 303 / 307 | Followed for this request only; no stored URL changes. |
| 401 / 403 | `ACCESS_DENIED`. No credentials or browser cookies are used. |
| 404 / 410 | `NOT_FOUND` / `GONE`. The feed, its articles and saved state are never removed automatically. |
| 429 | `RATE_LIMITED`; `Retry-After` honored. |
| 5xx | `SERVER`; `Retry-After` honored when present. |
| Other status | `HTTP_STATUS`. |
| HTML page | `NOT_A_FEED`; previous data kept. |
| Malformed XML | `FORMAT`; previous data kept. |
| Other XML root, Atom 0.3 | `UNSUPPORTED_FORMAT`. |
| Limit exceeded | `LIMIT_EXCEEDED`. |
| Blocked address, user info, DOCTYPE and similar | `SECURITY`. |
| Certificate or host name failure | `TLS`. Verification is never relaxed. |
| `http://` while HTTP is off | `HTTP_BLOCKED`; nothing is sent. |
| Timeout / other I/O error | `TIMEOUT` / `NETWORK`. |
| Redirect problems | `REDIRECT_LOOP` / `TOO_MANY_REDIRECTS`. |
| Database write failure | `STORAGE`. |

**Retry timing.**

- A valid `Retry-After` (delta-seconds or HTTP date) on a 429 or 5xx response is honored within 1 minute to 30 days.
  A missing, invalid or past value on a 429 is replaced by 15 minutes. The server's time is not shortened to the
  app's own backoff cap. It is stored as `retryAfterUntil` and binds every kind of request: automatic, manual and
  forced.
- All other failures (and 5xx without `Retry-After`) back off 15 minutes times 2^(failures - 1), capped at 24 hours.
  This ordinary backoff (`nextEligibleAt`) delays automatic refreshes only; a manual refresh may retry earlier.
- A success or a valid 304 clears the waiting times. Repeated taps on refresh are merged into one request (section 5).

## 5. Refresh jobs

Manual refresh, post-import fetch, startup refresh and periodic refresh all go through one `RefreshCoordinator`.
The app runs in a single process.

**Requests.** A request has a scope (all feeds, uncategorized, a category with its descendants, an explicit list of
feeds, or *pending*), a reason (`MANUAL`, `IMPORT`, `STARTUP`, `PERIODIC`) and a force flag. *Pending* means feeds
whose last request did not finish (not started, cancelled, interrupted) or whose last attempt failed; it backs the
"retry unfinished" action. Only subscribed, non-paused feeds are targeted.

**Coordinator.** Requests with an identical key (scope, automatic or not, force, and the count of feed-address
changes seen when the request was made) join the one running request. A narrower request is never absorbed by a
running "all" request. After a feed's address changes, newer requests do not join older ones, so a refresh started
after an approved move really fetches the new address. Manual, import and startup requests run in the application
scope, so leaving a screen does not cancel them. Periodic requests run in the worker's own coroutine.

**Per-request checks (before any I/O).** For each target feed:

- a valid server wait (`retryAfterUntil`), or ordinary backoff for an automatic request, defers the feed (`BACKOFF`);
- an `http://` feed while HTTP is disabled is counted as held back (`HTTP_DISABLED`), not as failed;
- if the device has no connectivity, every remaining feed is deferred at once (`OFFLINE`) instead of timing out one
  by one.

**Per-feed jobs.** Eligible feeds are submitted to a shared registry that holds at most one queued or running job per
feed:

- A request asking for a feed that already has a job joins that job. A forced request never joins a running
  conditional fetch: it gets exactly one follow-up fetch, which repeated forced requests share. A queued conditional
  job simply becomes forced.
- If the feed's fetch address differs from the one the running job is fetching, the running job is cancelled and
  marked superseded, and a new job for the new address is created.
- Jobs start by priority (targeted manual or forced, then manual "all" and import, then automatic), then by oldest
  last attempt, then by submission order, with at most 4 jobs running overall and 2 per host. A narrow manual request
  therefore starts at the next free slot instead of waiting behind a large automatic one.
- Each request waits for its own feeds for at most 6 minutes. When it stops waiting (budget, cancellation, worker
  stop), it releases its feeds: a feed still wanted by another request becomes `CONTINUING`, a queued job is dropped
  (`NOT_STARTED`), a running job is cancelled (`CANCELLED`, nothing applied). A job is cancelled only when no request
  still wants it.
- Results already applied stay applied (one transaction per feed). Each request's report covers only its own feeds.

**Applying results.** Before writing, `FeedStore` re-checks inside the transaction that the feed is still subscribed
and not paused, and that its fetch URL is still the one the request started with. A feed unsubscribed or paused
meanwhile ends as `INACTIVE` and is never re-subscribed by a finished fetch; a feed whose address changed meanwhile
ends as `REPLACED`: the old answer, whether success, failure, 304 or an announced permanent redirect, is dropped as a
whole. `REPLACED` is history of that request, not unfinished work.

**Report.** `FeedResult` values are `UPDATED`, `NOT_MODIFIED`, `FAILED`, `BACKOFF`, `HTTP_DISABLED`, `OFFLINE`,
`NOT_STARTED`, `CANCELLED`, `CONTINUING`, `REPLACED` and `INACTIVE`. `RefreshReport` separates updated, not modified,
failed (with the error per feed), deferred by backoff, deferred by constraints, not started, cancelled, continuing,
replaced, inactive and held back by the HTTP setting. A request is reported complete only if nothing was left
unattempted (not started, cancelled or continuing), nothing was deferred by constraints and nothing failed. The
per-feed `feed_diagnostics` row records the same facts durably. The "last success" time of one feed is never used to
hide another feed's failure.

**Automatic refresh.**

- Nothing automatic happens before onboarding is complete. Periodic refresh requires an interval greater than zero;
  startup refresh requires its own switch.
- Both need connectivity, and, when "unmetered only" is on (the default), a network that is not metered. Opening the
  app does not bypass this.
- Startup refresh runs at most once per 30 minutes, measured from the last refresh attempt of any kind (manual, import,
  startup, periodic). An automatic request blocked by these rules is not counted as an attempt.
- Periodic refresh is a unique WorkManager periodic job with the configured interval (minimum 15 minutes) and a
  network constraint of unmetered or connected. Timing is inexact and the OS may delay it.
- If a periodic run leaves feeds unstarted or cancelled, the worker schedules up to two follow-up runs for the
  *pending* scope, each 3 minutes after the previous one, under the same network constraint. Otherwise the next run starts with the feeds
  attempted longest ago, so feeds are not starved.
- The foreground-service permission and WorkManager's foreground service are removed from the manifest; no worker runs
  in the foreground.

After each finished request, the coordinator runs the retention cleanup (if the user set a limit; see
[data-and-import.md](data-and-import.md) section 6) and nudges the icon lookup. Failures in either never fail the
refresh. Articles inserted by a feed's very first fetch are the initial content and are not reported as new arrivals.

## 6. URL and network boundary

The app fetches public Internet http(s) feeds and images only. Every request, including each redirect hop, passes
`NetworkGuard` before any byte is sent:

- the scheme must be http or https; URLs containing user info are refused;
- `http://` is refused unless the user opted in (see "HTTP compatibility" below);
- DNS answers are validated by the client's `Dns` implementation: if **any** returned address is not public, the
  request fails. OkHttp then connects to exactly the addresses that were validated, so there is no separate
  pre-check that DNS rebinding could bypass;
- IP-literal hosts never reach DNS, so the guard validates them separately with the same classification;
- a blocked request surfaces as `SECURITY` (or `HTTP_BLOCKED`) and is never reported as an empty success.

**Address classification** (`PublicAddresses`) accepts public unicast addresses only. Refused IPv4: `0.0.0.0/8`,
`10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16` (includes cloud metadata), `172.16.0.0/12`,
`192.0.0.0/24`, `192.0.2.0/24`, `192.88.99.0/24`, `192.168.0.0/16`, `198.18.0.0/15`, `198.51.100.0/24`,
`203.0.113.0/24` and everything from `224.0.0.0` up. IPv6 is accepted only inside `2000::/3`, excluding
`2001:db8::/32`; IPv4-mapped, IPv4-compatible, 6to4 (`2002::/16`) and NAT64 (`64:ff9b::/96`) addresses are judged by
the IPv4 address they embed; unique-local, link-local, site-local and multicast ranges are refused. The policy is a
constructor parameter; production code uses only the public-only policy, and the loopback allowance used by tests
exists only in test code.

**Untrusted XML.** Feeds and OPML files are parsed by `SafeXmlReader`, which uses the platform pull parser in
`nextToken()` mode so that a DOCTYPE is visible as a token and is rejected rather than skipped:

- DOCTYPE (and therefore any entity or external DTD declaration) is refused. XInclude elements are refused. An
  entity reference other than the five built-ins and numeric character references is refused. Nothing from the network
  or the file system is ever resolved by the parser.
- Limits: 5 MiB input (feeds and OPML), XML depth 64 for feeds and 32 for OPML, 500,000 elements, 256 attributes per
  element, 64 KiB per attribute value, 2,000 feed entries, 10,000 OPML outlines.
- Decompression is bounded because the 5 MiB limit is counted after decompression.
- Security rests on rejecting these tokens, not on parser feature flags, which are not honored uniformly across
  Android versions. A text search for `<!DOCTYPE` is not the control.

### HTTP compatibility

Existing subscriptions may point to old `http://` feeds, so registering an `http://` URL is allowed. By default no
traffic is sent to it; such feeds are shown as held back. The user can switch on "allow unencrypted HTTP feeds and
images" after a warning dialog; only then does the shared network layer permit cleartext. The app never rewrites a URL
to HTTPS silently and never falls back from HTTPS to HTTP automatically. A separate, user-consented check can move
feeds to HTTPS (section 9).

Android's cleartext permission is static configuration, not a runtime setting. The network security configuration
therefore permits cleartext at the OS level for any host, trusts only the system certificate store (no user-added
CAs, no trust-all), and the **app-level guard** denies every `http://` request unless the user opted in. The broad OS
permission is a documented compatibility trade-off, not a security feature. Tests verify that with HTTP disabled
nothing is sent over HTTP for the first request, for redirects, or for images, and that TLS verification is never
relaxed (there is no custom trust manager or host name verifier).

All clients for feeds, images, site pages, icons, discovery and the HTTPS check are built by the same client factory
(`HttpClients`) over `NetworkGuard`.

**Opening articles.** The browser launcher accepts only valid http(s) URLs without user info, rejects `localhost`,
`*.localhost` and non-public IP literals, and opens the page in a Custom Tab when a provider is installed, otherwise
through `ACTION_VIEW`. It does not resolve DNS itself. Subsequent navigation, redirects and resolution inside the
browser are the browser's responsibility; the app makes no claim about them. Opening an article does not mark it read.
For `http://` articles, a confirmation dialog is shown unless the user turned it off ("don't ask again", or the
setting); this is independent of the HTTP feed/image opt-in. The app contains no WebView and does not read browser
cookies or saved passwords.

## 7. Android permissions and local data

- Permissions: `INTERNET` and `ACCESS_NETWORK_STATE`. `FOREGROUND_SERVICE` and `SystemForegroundService` are removed.
  WorkManager contributes its own normal permissions and components through manifest merging; check the merged
  manifest when upgrading it. No storage, notification, location, contacts or audio permissions are needed. The
  manifest `queries` element lists Custom Tabs provider discovery and https `VIEW` handlers (package visibility).
- Files: OPML import and export and the full backup use the Storage Access Framework, with no storage permission. A
  backup being restored is untrusted input like an OPML file: it is copied into app-private storage, read only through
  bounded ZIP reading (entry names, order, duplicates, sizes counted on inflated bytes, compression ratio) and checked
  completely before anything changes; its addresses are validated as text and never fetched during the restore
  ([backup-format.md](backup-format.md)). A backup file contains feed URLs and article text and is not encrypted.
- Components: the only app-defined exported component is `MainActivity` (`singleTask`) with the launcher intent filter and one
  `ACTION_SEND` filter for `text/plain` (Share to Subscribe, section 8). Receiving a share starts no fetch before the
  first-run explanation is done and subscribes to nothing by itself. No other intent
  filter imports OPML or feed URLs from other apps; import happens only through the in-app file picker. The intent text is
  read once and removed from the intent, is never logged, and above 64 Ki characters is not read at all.
- No advertising, analytics or automatic crash-reporting SDK. Background task failures are logged with the exception
  class name only, never URLs or content.
- Sharing sends only what the user triggers: the system share sheet with one article's title and URL, and, from the
  "search the web" hand-off for a moved feed, a web-search intent containing the feed name and "RSS", handled by the
  user's own search app.
- OPML files, the database and URL query strings may contain personal data. Android backup and device transfer are
  disabled for all domains. The app stores no cookies, and the repository must not contain real OPML files or
  subscription URLs.

## 8. Source icons and feed discovery fetches

Both use the same client, through `SmallResourceFetcher`. Everything in sections 4 and 6 therefore applies: `http://`
only after the user's opt-in (otherwise nothing is sent and the lookup is retried later), every address and redirect
hop checked, TLS verified, no cookies, no JavaScript. Site HTML is parsed with jsoup only to read `link` elements; it
is never rendered. No third-party favicon or search service is contacted.

| Fetch | Limit | Notes |
|---|---|---|
| Site page (icon links, feed alternates) | 512 KiB, longer pages are cut | The beginning is enough; non-HTML content is not read as a page. |
| One icon file | 256 KiB; larger candidates are skipped | If it cannot be decoded, the next candidate is tried. |
| Candidate feed | The normal feed limits (5 MiB, 2,000 entries) | Counted as fetchable only if it parses; HTML is never a candidate. |

### Source icons

A lookup runs once per feed and its result is cached (`feed_icons`, see [data-and-import.md](data-and-import.md)
section 8). Order of attempts:

1. the icon the feed document declares;
2. the site page's `link rel="icon"` and `apple-touch-icon` entries (SVG excluded), the three closest to 48 px first;
3. `/favicon.ico` at the origin of the site page;
4. the kept-aside banner, otherwise nothing, and the UI draws an initial-letter placeholder.

An icon from any step whose aspect ratio is over 2:1 or under 1:2 (a wide logo or tall strip) is not used directly:
the first such image is kept aside as the banner of step 4.

The site is the feed's declared site URL, or the most common origin among its 20 newest article links. The feed URL's
host is never assumed to be the site, because it may be a CDN. Icons are decoded with the platform bitmap decoder
(PNG, JPEG, GIF first frame, WebP, BMP, ICO; sources over 4096 px are refused), sub-sampled while decoding, scaled to
at most 96 px and stored as PNG. SVG and unknown formats are skipped. Up to four lookups run in parallel.

Re-check schedule: a stored icon after 30 days, "no icon" after 14 days, temporary failure after 1 hour doubling up to
7 days. A lookup also runs again immediately when the feed's declared icon URL changes, when the feed first reveals its
site or its first articles and no icon is stored, and when images or HTTP are switched on. Lookups run only while
images are enabled, onboarding is complete, the device is online and the same unmetered rule as automatic refresh is
satisfied. A failed lookup is never a feed failure.

### Finding a moved feed

Runs only when the user taps it for one chosen feed; there is no crawling. The page read is the feed's site page, or
an address the user typed (a site page or a feed URL). If the fetched document is an RSS, Atom or RDF feed (decided by
its root element, not by a leading `<?xml`), it is treated as the entered feed. Otherwise the `link rel="alternate"`
entries of type RSS, Atom or RDF are the candidates, at most 8, excluding the current address. Each candidate is
fetched with the normal feed fetcher and parsed; the facts shown are fetchability and failure reason, feed title, site,
entry count, date range, number of entries shared with the subscribed feed (URL-like keys compared regardless of
`http`/`https` and `www.`) and whether the title is equal. Candidates are ordered by those facts but never chosen
automatically.

Adopting a candidate needs explicit confirmation and changes only `fetchUrl` (data-and-import.md section 8). The
refresh layer is notified so the next refresh targets the new address. A separate "search the web" action hands
"<feed name> RSS" to the device's web search; the app holds no search endpoint, API key or HTML scraping for it.

### Share to Subscribe (an address shared from another app)

Triggered by the user sharing text to Folirel. A single address is looked at at once; with several, nothing is fetched until the user picks one.
The text is reduced to at most 8 normalized `http`/`https` addresses (`SharedUrlExtractor`: other schemes, user info,
over-long tokens and text above 64 Ki characters are dropped or refused before any network use). The chosen address is
read by the same core as the moved-feed search (`FeedProbe`), with the same clients, so every rule above applies
unchanged and no sharing-specific exception exists: public addresses only, cleartext HTTP only after the user's opt-in
(without it an `http://` address is refused with nothing sent, and a redirect is never upgraded or downgraded), normal
certificate verification, every redirect hop checked, the page limit of 512 KiB (cut, not refused) and the feed limits.
A candidate is a document that parses as RSS/Atom/RDF; its address is the final address after redirects. The shared
address is not sent anywhere except the host it names (and the feed hosts that page declares), never to a search or
feed-finding service. Results are facts only; the feed is registered by the add-feed path after the user's explicit
confirmation (one refresh follows), and an address that already has a feed row (subscribed, or unsubscribed with its data)
is merged into that row, never added twice. An unsubscribed feed is taken again only on an explicit "Subscribe again".

**One read per address (0.9.3).** The first request to the chosen address is the only one: its limit depends on what the document turns out to be (decided from the root element once the first 64 KiB are in): an RSS/Atom/RDF document may be up to the feed limit (5 MiB, above that it fails as too large), anything else is cut at the page limit (512 KiB). When it is a feed, the bytes of that response are parsed and become the candidate, so a direct feed is one request and a redirect to a feed is one redirect chain; the result is reused within the run, not through ETag or any cache. A page costs one request plus one per declared feed (at most 8).

**Same-origin root fallback (0.9.3).** Only when the shared address is an HTML page that is not the root of its site (path other than `/`, or a query) and none of the feeds it declares could be used (it declared none, or every one failed to parse), the root page `scheme://host[:port]/` of the final page's origin (after redirects) is read once, with the same client and the same rules, and the feeds it declares are checked like those of any page (at most 8, skipping addresses already tried). If the root ends on another origin, is not an HTML page, cannot be read or declares nothing usable, the search ends. A direct feed never reads the root, a page with a usable feed never reads the root, and a shared root is not read twice. No deeper page, canonical/breadcrumb/category link, sitemap, guessed address (`/feed`, `/rss`, `/atom.xml`) or external service is used. Candidates are labelled by where they were found (the address itself, the shared page, the site root); two ways to the same final address are one candidate.

## 9. HTTP to HTTPS migration

For subscribed feeds still fetched over `http://`, the user can run a batch check after a single consent. For each feed
the candidate is the same URL with the `https` scheme (port 80 becomes 443). The check uses a dedicated client whose
guard never allows cleartext, whatever the HTTP setting is, so a redirect back to `http://` fails. A feed is switched
only if all of the following hold:

- a GET to the candidate succeeds with normal certificate verification and every redirect hop stays on HTTPS;
- the body parses as RSS/Atom (an HTML page, a 200 alone or a HEAD request never counts);
- it is the same feed: with the same host (ignoring `www.`), path and query it is accepted unless the stored history
  clearly disagrees (known entries exist, none overlaps, and the titles differ); if HTTPS redirected elsewhere there
  must be positive evidence (shared entries or the same title), and when HTTP is allowed the HTTP document may be
  fetched for the comparison (same final URL, a shared entry or the same title). Without evidence the feed stays
  unconfirmed.

Timeouts and network errors are retried once and then reported as "not checked", never as "HTTPS not supported".
Each verified feed is switched immediately (only `fetchUrl` changes; the migration is recorded in
`feed_https_migrations`), so cancelling keeps what was already verified; switched feeds are then refreshed once. The
batch runs 4 checks in parallel and 2 per host. It never changes the HTTP opt-in setting, and unverified feeds keep
their `http://` address.
