# Synthetic fixtures

Test data for Folirel's parser, importer, network and icon tests. **Everything here is fictional**: the feeds, articles, subscriptions and images were made up for the tests, and URLs use reserved example hosts (`example.com`, `example.net`, `example.org`, `example.invalid`). The only other hosts that appear are XML namespace identifiers (for example `purl.org`, `w3.org`, `search.yahoo.com/mrss/` in `feeds/rss2-basic.xml`), which are never fetched.

The tests never fetch these paths from a real site. They are served from a local MockWebServer or fed straight to the parsers, using a test-only URL mapping. That mapping must never be added to release code.

`app/build.gradle.kts` mounts this directory as unit-test resources and as device-test assets. Files are read as raw bytes and are not copied or re-encoded. See [docs/testing.md](../docs/testing.md) for how the tests are run and which scenarios (`T..`, `N..`, `U..`) they belong to.

## Layout

| Path | Purpose | Used for |
|---|---|---|
| `opml/subscriptions.opml` | 4 distinct feeds, 4 categories (`Tech`, `Tech / AI`, `Tech / Tools`, `News`), 4 direct category memberships and 1 uncategorized feed. Six `outline` occurrences collapse to 4 feeds (the same URL appears three times). `Tech` groups its subcategories; alpha and beta are not also listed directly under `Tech`. | Import preview and commit, duplicates, category hierarchy, export round trip (`T02`, `T03`, `T07`) |
| `opml/warnings.opml` | A mix of valid, non-standard and unsafe entries: two HTTPS feeds, one `http://` feed, one `javascript:` URL, one website-only entry (no feed URL), and a folder with slashes in its name. It does not model items that were never in an exported file. | Candidates versus exclusions, HTTP pending (`T05`) |
| `opml/malformed.opml` | Not well-formed XML, after a valid entry. | Atomic rejection (`T04`) |
| `opml/doctype.opml` | Contains a DOCTYPE with an external entity. **Rejection test only.** | `T04`, `T40` |
| `feeds/rss2-basic.xml` | RSS 2.0 with Japanese text, `guid`, media and content namespaces, an entry without a link. | `T11`, `T16`, `T19` |
| `feeds/rss2-updated.xml` | The same feed, later: `alpha-001` with changed title, date and summary. | ID stability across updates (`T16`) |
| `feeds/rss2-idless.xml` | Entries with no `guid` and no link. | Fallback ID digest (`T17`) |
| `feeds/rss2-shiftjis.xml` | A feed encoded as **Shift_JIS bytes**. | Character encoding (`T15`) |
| `feeds/rss2-unsafe-html.xml` | `script`, `style` and `iframe` in a description, tracking pixel, `data:` image, a safe image, and a `javascript:` link. | Text extraction and image candidate rules (`T19`) |
| `feeds/atom-basic.xml` | Atom 1.0: namespaces, `published`/`updated`, author inheritance, link relations, `type="html"`. | `T12`, `T14` |
| `feeds/rss1-basic.xml` | RSS 1.0 (RDF) with Dublin Core fields. | `T13` |
| `feeds/malformed.xml` | A broken RSS document. | Failure keeps previously stored articles (`T23`) |
| `feeds/rss2-xxe.xml` | Contains a DOCTYPE with an external entity. **Rejection test only.** | `T40` |
| `feeds/rss2-html-entities.xml` | RSS 2.0 that writes HTML named character references (`&nbsp;`, `&copy;`, `&mdash;`, `&rsquo;`, `&hellip;` and others) straight into the XML, with no DTD. | Entity decoding (`T40`) |
| `feeds/rss091-netscape-doctype.xml` | RSS 0.91 with the Netscape `DOCTYPE` (its system id points at `example.invalid`). **Rejection test only.** | Refused, with an old-format detail (`T40`) |
| `expected/*.json` | Expected normalised results for the matching input, as subset assertions (the `assertions` object). `expected/rss2-idless.json` also has `contractInput`, the inputs of the fallback-ID digest (title, date, author, summary). `expected/subscriptions.json` and `expected/warnings.json` describe the OPML results. | Parser and importer tests |
| `url-identity.json` | URL pairs that must compare equal, pairs that must stay distinct, and inputs that must be rejected (`file:`, `content:`, `javascript:`, user-info). | URL normalisation (`T06`) |
| `icons/` | Synthetic images for source-icon decoding: `icon-32.png`, `icon-png-entry.ico` (ICO with a PNG entry), `icon-bmp.ico` (ICO with a BMP entry), `banner-300x50.png` (wide logo), `big-512.png` (large image), `not-an-image.html` and `vector.svg` (things that are not decodable bitmaps). No real site's images. | `IconDecoderTest` on a device (`U08`) |

The expected files assert the fields a test needs, not the whole parser output. Database UUIDs and receive times are injected by the tests rather than stored here.

## Rules for maintaining the fixtures

- Keep every value fictional. Do not add real feeds, real OPML exports, real subscription URLs (or their query strings), cookies or tokens. Personal data belongs in `local-data/`, which is not committed.
- `feeds/rss2-shiftjis.xml` must stay Shift_JIS bytes. Do not re-save it as UTF-8 or let an editor or line-ending conversion touch it; `.gitattributes` marks it as `-text` so Git leaves the bytes alone.
- The DOCTYPE and external-entity files (`opml/doctype.opml`, `feeds/rss2-xxe.xml`, `feeds/rss091-netscape-doctype.xml`) exist so that tests can check they are **rejected**. They point at `example.invalid`, which must never be resolved. Do not feed them to a parser that resolves entities, and do not open or process them with tools that do.
- Do not change expected values to make a test pass. If behaviour changes on purpose, change the fixture's expectation and the test together and say why.
- The small set does not cover every scenario. HTTP status codes, redirects, DNS rules, compression and size limits, database races and 5,000-article data sets are generated inside the tests.

## Regenerating the icons

The files in `icons/` are generated, not drawn by hand:

```sh
python -X utf8 tools/make_icon_fixtures.py
```

Run it from the repository root. It writes the PNG, ICO, BMP-in-ICO, wide-logo, large-image, HTML and SVG fixtures into `fixtures/icons/` and contains nothing taken from a real site.
