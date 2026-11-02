# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private vulnerability reporting for this
repository (Security > Report a vulnerability). Describe what an attacker could do, the Folirel version, and the steps or
a synthetic feed that shows it. Do not attach your own OPML file or private feed addresses.

You can expect a first reply within two weeks. Folirel is maintained by one person in their spare time, so a fix may take
longer; you will be told what is planned.

## Scope

Folirel reads untrusted feeds, OPML files, web pages and images. Problems in how it parses or fetches them (XML entity or
DTD handling, URL and redirect checks, private-address blocking, TLS, size limits) are in scope. See
[docs/fetch-and-security.md](docs/fetch-and-security.md) for the rules the app enforces.

Only the latest release is supported.
