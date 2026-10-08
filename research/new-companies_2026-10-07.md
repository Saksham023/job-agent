# New companies: API research, 2026-10-07

Read-only research by six subagents (about 5 to 12 polite requests each, public endpoints only, `robots.txt` checked first,
nothing bypassed, nothing written to the repo). The findings are the agents' reports; the claims that matter most (robots.txt
verdicts, India counts, field names) must be re-verified when each adapter is built.

## Summary

| Company | India jobs | Platform | robots.txt | Description | Posted date | Requests for a full crawl | Rate limiting | Effort | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| Atlassian | 51 (unique ids) | one JSON list, atlassian.com/endpoint/careers/listings | nothing blocks it | in the list (3 HTML fields) | none: only a last-updated time, minute precision | 1 (about 0.5 MB gzip), ETag works | none seen in 7 requests | low, 2-3 h | build first |
| Cisco | 286 | Phenom: POST careers.cisco.com/widgets (cookie handshake, refNum CISCISGLOBAL) | /widgets not disallowed | detail request per job (POST jobDetail) | `postedDate` date only; `dateCreated` real time but is the Workday req creation | 3 list calls (size 100) + about 286 details (first crawl) | none seen in 8 requests | low-medium, 4-6 h | build second |
| Apple | 166 rows (fewer jobs after merging multi-city duplicates) | React Router SSR: JSON embedded in the HTML of search and detail pages | no robots file (301 to a not-found page) | detail page per job (summary + description + min/preferred qualifications) | real UTC timestamp for normal jobs (about 8 retail "pipeline" roles have a meaningless one) | 9 list pages + about 160 details (about 9 min at 3 s) | none seen in 5 requests, pages are slow (3-4 s) | low-medium, 3-5 h | build third |
| Intuit | 26 seen (Bengaluru) | Radancy: job pages carry JSON-LD; list via sitemap | `/search-jobs/` is DISALLOWED (the old list endpoint); sitemap and `/job/` pages are allowed | JSON-LD on each job page | date only, not zero-padded ("2026-10-6") | 1 sitemap + about 30 job pages | none seen in 5 requests | low-medium, 4-6 h | worth it only for completeness (few jobs) |
| Google | 269 | HTML with a positional array blob | PAGINATION DISALLOWED (`page=` on the results path) | in the list blob | real timestamps (to the second) | only page 1 (20 of 269 jobs) is permitted | none seen (2 requests) | 3-5 h if built | BLOCKED by robots.txt: skip unless you accept a filter-variation workaround |
| Flipkart | 6 (0 are engineering) | TurboHire API (token handshake with the page's origin) | page host disallows all bots; API host has no robots | in the list | real timestamp | 1 | none seen in 12 requests | low, 2-3 h | skip: no engineering jobs listed publicly |

## Per company notes

### Atlassian
- `GET https://www.atlassian.com/endpoint/careers/listings`: one JSON array of about 356 postings worldwide, no cookies, no tokens,
  no pagination, about 2.45 MB (0.5 MB gzip), 0.4 s. An ETag is returned and conditional GET gives 304.
- India: no country field. Match any `locations` element containing "India" ("Bengaluru - India - ...", "Remote - India - Remote").
  Skip "Remote - Remote" (no country). The array contains duplicate entries for some ids: dedupe by `id`.
- Fields: `id`, `title`, `category` (the only department/team), `type` (missing on most India jobs), `locations`, `overview`,
  `responsibilities`, `qualifications` (HTML, concatenate with headings like the Amazon adapter), `applyUrl` (iCIMS, host varies per job).
- No real posted date. `portalJobPost.updatedDate` ("2026-09-24 03:33 PM", minute precision, no zone) is a LAST-UPDATED time that
  changes on edits: do NOT use it as the posted date (it would make edited jobs look new). Leave posted date null so the derived
  posting time falls back to first seen.
- Mostly senior roles (Principal, Senior Principal, Engineering Manager); 32 of 51 engineering. Years only as free text in `qualifications`.
- Risks: undocumented internal endpoint (low-medium), optional keys, free-text locations.

### Cisco (Phenom)
- Handshake: `GET https://careers.cisco.com/global/en/search-results?keywords=&country=India` sets cookies; the page has `refNum` =
  `CISCISGLOBAL` (constant, configurable). The agent's POSTs worked with the cookie jar alone (no csrfToken).
- List: `POST https://careers.cisco.com/widgets` (`ddoKey: refineSearch`, `selected_fields.country: ["India"]`, `from` = offset,
  `size` 100 honoured). 286 India jobs in 3 calls (0.4-0.6 s each, 317 KB per page).
- Detail: `POST /widgets` with `ddoKey: jobDetail`, `jobId`, `pageId: page11`, `refNum` (80 KB, 0.4 s); full HTML `description`
  plus `compensationRange`, `jobFamily`, `GradeLevel`, `standardised_multi_location`.
- Locations: `multi_location` list ("Hyderabad, Telangana, India"), `RemoteType` (Hybrid / Onsite Only / Remote). `department`
  is junk (an org name): use `category` / `multi_category`.
- Dates: `postedDate` is date only (midnight); `dateCreated` is a real time but means the Workday requisition creation.
- Years only as free text in the description (it also contains boilerplate with unrelated numbers: watch the extractor).
- Cost: first crawl 3 + about 286 requests; later crawls reuse stored descriptions (7-day cache). Pace about 1 request per second.
- Risks: cookie handshake, loose schema (state often empty), ids equal Workday requisition ids.

### Apple
- `GET https://jobs.apple.com/en-in/search?location=india-INDC&page=N` (20 per page, 9 pages, 3-4 s each). Data:
  `window.__staticRouterHydrationData = JSON.parse("...")` (decode the JS string literal, then the JSON; find the script by its marker
  and parse with Jackson). List items have a `jobSummary` only; `loaderData.search.totalRecords` has the total.
- Detail: `GET https://jobs.apple.com/en-in/details/{positionId}/{transformedPostingTitle}` embeds `jobDetails.jobsData` with
  `description`, `minimumQualifications` ("8 - 12 years of experience ..."), `preferredQualifications`. Concatenate with headings.
- Multi-location jobs repeat as separate rows with the same `positionId`: dedupe and merge locations. About 8 rows are retail
  "pipeline" roles (`type: PIPE`, no city, meaningless timestamp): skip them or accept list-only data.
- Posted date: `postDateInGMT` is a real UTC timestamp for normal (`REQ`) jobs.
- No robots file. The CSRF-protected JSON API exists; do not use it (not needed).
- Cost: first crawl about 170 requests (about 9 min at 3 s); later crawls about 9 list pages plus new jobs.
- Risk: embedded JSON in an SSR app can change with a front-end deploy: parse defensively and let the health check flag 0 rows.

### Intuit (Radancy)
- `robots.txt`: `Disallow: /search-jobs/`. The list endpoint from the 2026-10-04 research lives under it, so it must NOT be used.
- Compliant route: `GET https://jobs.intuit.com/sitemap.xml` (1,689 URLs, 597 `/job/` URLs); job URLs look like
  `/job/{city-slug}/{title-slug}/27595/{jobId}`. Each job page has a schema.org `JobPosting` JSON-LD block with `description` (HTML, full),
  `datePosted` (date only, not zero-padded), `employmentType`, `identifier`, structured `jobLocation` (city, region, country).
- India selection: only the city slug is in the sitemap (26 `bengaluru` URLs; other Indian city slugs unknown): filter by a list of
  Indian cities, then confirm `addressCountry` in the JSON-LD. Sitemap `lastmod` is the generation time: no change signal.
- Cost: 1 sitemap + about 30 job pages (600 KB each). No rate limiting seen.
- India volume is small (about 26 jobs).

### Google
- `robots.txt` for `User-agent: *` disallows `/about/careers/applications/jobs/results?page=` (and the `?*&page=` forms): only page 1
  (20 of 269 India jobs) may be fetched. A compliant full crawl is impossible.
- Data is easy: `AF_initDataCallback({key: 'ds:1', ... data: [jobs[20], null, 269, 20]` with 21-element positional arrays per job
  (id, title, apply URL, responsibilities, qualifications with "N years of experience", locations with country codes, timestamps).
- Only workaround found: vary other filters (city, employment type, ...) to get different page-1 lists. That keeps the letter of
  robots.txt but arguably not its spirit: not used without the user's decision.

#### Google, verified follow-up (same day)
- Re-read robots.txt myself: lines 202-205 disallow `/about/careers/applications/jobs/results?page=` and the `?*&page=` forms for
  `User-agent: *` (the broader `results` Disallow belongs to the Yandex group). The careers-specific robots.txt is a 404, and Google's
  sitemap index (23 sitemaps) has no careers or jobs sitemap. There is no compliant way to page the list or to list every job id.
- `GET https://www.google.com/about/careers/applications/jobs/results?location=India&sort_by=date` (no page parameter: allowed) works:
  the first 20 jobs are the NEWEST India jobs, timestamps descending to the minute (270 India jobs in total today). A normal first
  page is not date-ordered.
- What that allows: a "fresh jobs feed" (poll that one page each round; every new posting appears at the top within minutes). It
  cannot give the inventory of the older ~250 jobs or tell which jobs closed (job pages `/jobs/results/<id>-<slug>` are not
  disallowed and could serve a per-job "still open?" check). Varying other filters to reconstruct the list was NOT used: it would
  rebuild what the page= rule forbids.

### Flipkart
- 6 jobs on the TurboHire board, none in engineering; the earlier "8" has shrunk. Engineering hiring is not listed publicly.
- Technically easy (token + one POST, full descriptions, structured `Experience {MinExp, MaxExp}`), but the page host disallows all
  bots in robots.txt and the token only works with the page's own Origin/Referer. Recruiter names and emails are in the data: do not store.
- Skip; re-check only if Flipkart starts posting engineering roles publicly.

## Recommended order
1. Atlassian (cheapest, one request, 51 jobs).
2. Cisco (most jobs of the buildable ones, 286).
3. Apple (about 160 jobs, real timestamps).
4. Intuit (small, needs the sitemap route; low priority).
Skipped for now: Google (robots.txt), Flipkart (no engineering jobs).

## Common work per adapter (same as the other companies)
Adapter + company row migration + adapter test with a saved fixture + a crawl + the generalization check BEFORE any rule edit
(measure UNCLASSIFIED / years NONE / unresolved locations), then the Opus gap fill, then rule fixes if the numbers call for them.
Posting date: only pass a posted date when it is the real publish date (not an "updated" time).
