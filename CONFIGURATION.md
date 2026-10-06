# Configuration

Every setting of job-agent, in one place. There are exactly two homes:

| Where | What lives there | How to change it |
|---|---|---|
| `src/main/resources/application.yaml` | settings for the **whole app** (search, crawl schedule, Opus usage, skill learning) | edit the file, restart the app |
| `companies` table, columns `enabled` and `config` (JSON) | settings for **one company** (where its careers site is, how fast to crawl it) | one SQL `UPDATE`, takes effect on the next crawl, no restart |

Why company settings are in the database: adding a company on a known platform is one row, no code and no restart,
and each company needs different values (its host, its site name, its own pace).

Rule: every new setting is added to this file in the same change that introduces it.

## 1. App settings (`application.yaml`)

Any of them can also be set as an environment variable (Spring's naming: `jobagent.crawl.schedule.enabled` ->
`JOBAGENT_CRAWL_SCHEDULE_ENABLED=true`).

### Crawl job: `jobagent.crawl.*`

| Setting | Default | What it does |
|---|---|---|
| `countries` | `IN` | countries whose jobs are kept (others are counted as `otherCountries` and dropped) |
| `detail-max-age-days` | `7` | a known, unchanged job's stored detail is reused until it is this old, then fetched again |
| `schedule.enabled` | `false` | run the crawl job automatically (manual: `POST /admin/crawl`) |
| `schedule.cron` | `0 0 */6 * * *` | when (every 6 hours) |
| `schedule.gap-fill` | `true` | Opus fills the gaps of new jobs: for each company right after its own crawl (so a slow company never delays the others), and once more at the end for anything still unasked |
| `schedule.gap-fill-model` | `opus` | model for that gap fill |
| `schedule.gap-fill-parallelism` | `10` | Opus calls at the same time (1 to 10) |
| `schedule.close-after-misses` | `2` | a job missing from this many good crawls in a row is closed |

### Opus gap fill: `jobagent.gap-fill.*`

| Setting | Default | What it does |
|---|---|---|
| `families` | `SOFTWARE_ENGINEERING, DATA_ML, INFRA_DEVOPS, UNCLASSIFIED` | only jobs in these families (primary or secondary) are sent to Opus; others keep their gaps |

### Skill learning: `jobagent.skills.learning.*`

| Setting | Default | What it does |
|---|---|---|
| `enabled` | `false` | run skill learning automatically (manual: `POST /admin/skills/learn`) |
| `cron` | `0 30 3 * * *` | when (daily 03:30) |
| `min-companies` | `3` | a word must appear in skill lists at this many companies before Opus is asked |
| `max-terms-per-run` | `300` | cost cap: words sent to Opus per run |
| `batch-size` | `20` | words per Opus call |
| `parallelism` | `4` | Opus calls at the same time |
| `model` | `opus` | model that decides |

### AI search (MCP `match_jobs`): `jobagent.search.*`

| Setting | Default | What it does |
|---|---|---|
| `first-batch` | `10` | jobs judged before the first answer (in parallel) |
| `ready-target` | `15` | the background judge keeps this many APPLY jobs ready |
| `stop-after-nos` | `20` | the background judge stops after this many NO verdicts in a row |
| `parallelism` | `4` | judge calls at the same time |
| `page-size` | `10` | jobs per answer when no number is asked |
| `max-page-size` | `25` | largest answer allowed |
| `model` | `opus` | judge model |

### Matching: `jobagent.matching.*`

| Setting | Default | What it does |
|---|---|---|
| `country` | `IN` | country searched |
| `years-below` | `2` | experience window: a job's range must reach down to (candidate years - 2) ... |
| `years-above` | `1` | ... and start at most at (candidate years + 1) |
| `round-up-from` | `0.5` | resume years are rounded: 1.5 -> 2, 1.4 -> 1 |

### Claude CLI (how the app calls Opus): `jobagent.llm.claude-cli.*`

| Setting | Default | What it does |
|---|---|---|
| `command` | `claude` | path of the Claude Code CLI (env `JOBAGENT_LLM_CLAUDECLI_COMMAND`) |
| `default-model` | `opus` | model when none is given |
| `timeout` | `5m` | one call's time limit |
| `work-dir` | temp folder | empty folder the CLI runs in (so no CLAUDE.md leaks in) |
| `thinking` | `false` | extended thinking (off: much faster, same answers for our tasks) |

Also needed as an environment variable, never in a file: `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`).

### Evaluation: `jobagent.eval.*`

| Setting | Default | What it does |
|---|---|---|
| `dir` | `eval` | folder for judge runs and reports |

### Security: `jobagent.security.*`

| Setting | Default | What it does |
|---|---|---|
| `api-key` | empty (protection off, a warning is logged) | shared secret for `/admin/**` and `/mcp`; requests must send `X-API-Key: <key>` or `Authorization: Bearer <key>`, else 401. `/api/v1/**` (the web UI's read-only API) and `/actuator/health` stay open. Env `JOBAGENT_SECURITY_API_KEY`, never in a file in git. Use HTTPS when the app is reachable from the internet. |
| `rate-limit.requests-per-minute` | `120` | sustained rate per client on `/api/**`; over it the answer is 429 with `Retry-After`; `0` = no limit |
| `rate-limit.burst` | `30` | requests one client may send at once |
| `rate-limit.client-ip-header` | empty (the connection's address) | behind a proxy or tunnel, the header that carries the visitor's address (Tailscale Funnel: `X-Forwarded-For`; Cloudflare: `CF-Connecting-IP`). When the header holds a list, the last entry is used (the one our proxy added). Env `JOBAGENT_CLIENT_IP_HEADER`. Set it ONLY when all traffic comes through that proxy, otherwise a caller can invent addresses and escape the limit. On the home server: `export JOBAGENT_CLIENT_IP_HEADER=X-Forwarded-For` in `~/jobagent/env`. |

The public search also refuses, for an ordinary visitor, requests built to be expensive: more than 50 companies, cities or
skills, a keyword over 100 characters, a page above 1000; page size is capped at 60.

**A request that carries the API key skips all of this** on `/api/**`: no rate limit, no caps (page size up to 1000).
Use it for your own scripts: `curl -H 'X-API-Key: <key>' '.../api/v1/jobs?size=500'`.

### Shutdown: `jobagent.shutdown.*` and Spring's own

| Setting | Default | What it does |
|---|---|---|
| `jobagent.shutdown.wait-seconds` | `60` | when the app is told to stop (a deploy, a restart), running crawls and gap fills get this long to wind down: a crawl saves its batch and records its run as partial, then the app exits |
| `spring.lifecycle.timeout-per-shutdown-phase` | `90s` | Spring's limit for the whole wind-down; keep it above `wait-seconds` |
| `server.shutdown` | `graceful` | web requests already in flight finish before the web server stops |

The launchd service file on the server needs a matching `ExitTimeOut` of 90 seconds, otherwise launchd kills the app sooner
(see the deploy notes in `deploy/deploy.sh`). The deploy script stops the app with SIGTERM and waits up to 80 s (`STOP_WAIT`).

### Web UI: `jobagent.web.*`

| Setting | Default | What it does |
|---|---|---|
| `dir` | empty (UI not served) | folder holding the built UI (`npm --prefix web run build` creates `web/dist`); when set, the app serves it at `/`, next to the API. Env `JOBAGENT_WEB_DIR`. |

The health check shows only `UP` / `DOWN` (`management.endpoint.health.show-details: never`), because it is public.

### Infrastructure (standard Spring settings)

| Setting | Default | What it does |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/jobagent` | database |
| `spring.datasource.username` / `password` | env `JOBAGENT_DB_USER` / `JOBAGENT_DB_PASSWORD`, else `postgres` / `ragpass` | database login |
| `server.port` | `8080` | HTTP port (API, MCP at `/mcp`) |
| `spring.ai.mcp.server.instructions` | see file | what Claude is told about the MCP server |

## 2. Company settings (`companies` table)

See them all:

```sql
SELECT slug, platform, enabled, config FROM companies ORDER BY platform, slug;
```

Change one (example: slow Microsoft down):

```sql
UPDATE companies SET config = config || '{"delayMs": 5000}', updated_at = now() WHERE slug = 'microsoft';
```

Remove a key: `config = config - 'delayMs'`. Changes made this way are not in git; when a change should be permanent
and reproducible, put the same `UPDATE` in a new Flyway migration (`src/main/resources/db/migration/V<next>__....sql`).

### For every company

| Column / key | Default | What it does |
|---|---|---|
| `enabled` (column) | `true` | `false` = never crawled (today: Qualcomm) |
| `minCrawlHours` | none (every run) | full runs skip the company until its last crawl started this many hours ago; `POST /admin/crawl/{slug}` ignores it. Set: Qualcomm 24, Microsoft 24 |
| `delayMs` | see platform | Microsoft: 10000 (V23, first load) |

### Per platform

| Platform | Key | Required | Default | What it does |
|---|---|---|---|---|
| Greenhouse | `boardToken` | yes | | board name in `boards-api.greenhouse.io/v1/boards/<token>` |
| Lever | `site` | yes | | name in `api.lever.co/v0/postings/<site>` |
| Ashby | `boardName` | yes | | name in the Ashby job board API |
| SmartRecruiters | `companyId` | yes | | company id (e.g. `PHONEPELIMITED`) |
| SmartRecruiters | `country` | no | `in` | country filter sent to the API |
| Workday | `host`, `tenant`, `site` | yes | | e.g. `adobe.wd5.myworkdayjobs.com`, `adobe`, `external_experienced` |
| Workday | `country` | no | `IN` | country looked up in the facets |
| Workday | `departmentFacet` | no | `jobFamilyGroup` | facet used to split big lists into smaller queries |
| Eightfold | `host`, `domain` | yes | | e.g. `careers.qualcomm.com`, `qualcomm.com` |
| Eightfold | `location` | no | `India` | location filter sent to the API |
| Eightfold | `delayMs` | no | `1000` | starting pace per request (slows down by itself on throttling) |
| Eightfold | `detailDepartments` | no | all | regex; only jobs whose department matches get a detail request, the rest are saved from the list |
| Eightfold | `saveEvery` | no | `10` | the crawl saves its jobs and logs a progress line after this many jobs, so a crawl that stops part way keeps what it had; a debug line per job shows each job's own timestamp |
| Eightfold | `maxDetailsPerCrawl` | no | no limit | at most this many detail requests per crawl; the rest are saved from the list (or keep an older stored detail) and fetched by the next crawls. For a gentle first load |
| Oracle | `host`, `siteNumber` | yes | | e.g. `jpmc.fa.oraclecloud.com`, `CX_1001` |
| Oracle | `country` | no | `India` | location facet entry looked up |
| Oracle | `locationId` | no | discovered | skip the discovery and use this id |
| Oracle | `delayMs` | no | `1000` | pause per request |
| Amazon | `countryCode` | no | `IND` | country filter |
| Amazon | `delayMs` | no | `1000` | pause per list page |

## 3. Data files (rules, edited by hand)

Not settings, but tuned by editing files (then `POST /admin/requirements/rebuild?all=true`):
`src/main/resources/classify/*.csv` (skills, title and department families, keywords, skill implications) and
`src/main/resources/geo/*.csv` (countries, states, cities). Skills learned by the app live in the `learned_skills` table
on top of `skills.csv` (the CSV wins on conflicts).

## 4. Crawl results to know

A company's crawl ends as one of: **OK** (complete, may close vanished jobs), **SUSPECT** (saved, but the count collapsed, nothing closed),
**PARTIAL** (an Eightfold crawl that saved some jobs and then stopped, for example because the site kept blocking
descriptions; the saved jobs stay, nothing is closed, the next crawl continues from them), **FAILED** (nothing saved).
Eightfold crawls stop after 3 description requests in a row fail (the site is blocking us). When the app is stopped on
purpose in the middle of a crawl (a deploy or restart), the crawl saves its batch and is recorded as PARTIAL (or FAILED when
nothing was saved yet) with the note "interrupted: app shutting down"; such a run does not count for `minCrawlHours`, so the
company is crawled again at the next run. Only a crash, a power cut or a hard kill loses the run record (the saved jobs stay).
