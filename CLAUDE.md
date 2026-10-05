# job-agent: notes for Claude

A personal job-search agent for the Indian tech market. It crawls live job postings directly from major
companies' careers platforms (Workday, Eightfold, Oracle, Greenhouse, Lever, SmartRecruiters, plus custom
sites), ranks them against the user's resume with embeddings + an LLM, and helps act on them (shortlist,
tailored notes, outreach drafts, tracking, reminders). The human always clicks "apply".

This file holds everything decided so far (planned 2026-10-01..04 in the `python learning` session).

## 0. RESUME HERE (read first after a context compaction)

**State (2026-10-05):** Milestones 0-4 DONE (M4 incl. skills dictionary, implications, recall-first classifier). Commits:
`c517f86` M0, `627fd80` M1, `a562604` M2, `2a3baa1` M3, `ac70084` M4 part 1 (MCP server, list_companies,
match_jobs), then M4 part 2 (get_job). 12 companies on 4 platforms, 706 India jobs, all with requirements
(extractor v2). MCP server live at `http://localhost:8080/mcp` with 3 tools: `list_companies`,
`match_jobs`, `get_job`; Claude Code connects via `.mcp.json` (already approved). End-to-end demos passed.
99 unit tests green. **M4 goes SLOWLY: the user is new to Spring AI; explain every annotation/term briefly.**

**Done since (2026-10-05, uncommitted until the next commit):** M4 step 6 `find-jobs` prompt
(`mcp/JobSearchPrompts`, @McpPrompt + @McpArg, returns GetPromptResult with one USER message; works as
`/mcp__job-agent__find-jobs` in the Claude Code CLI; the desktop Code tab does not list MCP prompts, so the
location rule also lives in the server instructions and the match_jobs description). Skills part A: skills.csv
grew 156 -> ~210 rows (Spring AI, MCP, AI Agents, JWT, CDC, AWS S3, Observability, SRE, Caching, Message
Queues...), chosen by counting mentions in the 706 jobs and reading contexts (rejected: bedrock, payments,
scalable, bare Lambda); EXTRACTOR_VERSION=3; 234 jobs gained skills, none lost. Part B: skill implications
(`classify/skill-implications.csv`, 96 rules, `matching/SkillImplications`, credit 1.0 = certain, 0.5 = related,
candidate side only, one hop; MatchScorer sums credits, labels "AWS (via DynamoDB)"). User's search: Paytm TL
49 -> 76, Sarvam Backend 53 -> 73, Zscaler SDE 60 -> 69, Paytm SSE 51 -> 68. 105 unit tests green.

**Classifier pass "recall first" DONE (2026-10-05, Milestone 4 part 4):** title rules (IT support -> SUPPORT,
"<tool> administrator" -> INFRA_DEVOPS, "Engrg Mgmt" -> ENG_MANAGEMENT, aiml -> DATA_ML, deployment engineer and
fde -> SWE: pre-sale = sales engineering, post-sale deployment = engineering); generic "engineer" catch-all moved
to `classify/title-fallback.csv`; SECONDARY FAMILIES (V8 `job_requirements.secondary_families`, GIN): every other
specific title rule, the department, the description winner, and any tech keyword set with >= 4 distinct hits
(incl. secondary-only `classify/description-secondary-keywords.csv`: infra-specific words; docker/k8s/ci-cd stay
generic TECH). Only TECH, SALES_ENGINEERING and UNCLASSIFIED primaries get secondaries; primary voting unchanged.
Match filter = family OR secondary families (array overlap); match_jobs and get_job show secondaryFamilies.
Specialization AI_ENGINEERING (LLM apps, agents) vs ML_AI (models; research scientists/engineers). Scorer:
MIN_SKILLS_COUNTED = 4. EXTRACTOR_VERSION=5; 706 rebuilt, 56 jobs with secondaries. User's search: 17 -> 23
eligible, Jira/IT support gone, top 4 unchanged, ServiceNow Armis (SECURITY also SWE) found. 125 unit tests.

**M4 step 5 DONE (2026-10-05) as a PARAMETER, not a separate tool:** `match_jobs` has optional `postedSince`
(YYYY-MM-DD, India time; `JobTools.startOfDayInIndia`) and every result carries `postedAt` = the platform's
publish date (Greenhouse first_published, Lever createdAt, Ashby publishedAt, SmartRecruiters releasedDate),
falling back to `first_seen_at` (which is the first crawl, Oct 4-5, for all current jobs, so useless for "new").
Admin: `POST /admin/match?postedSince=`. Real data (user's profile): 23 eligible, 7 since Sep 1, 2 since Oct 1.

**MILESTONE 4 DONE (2026-10-05).** Final demo in a clean folder (`~/job-search`, server added with
`claude mcp add --transport http --scope user job-agent http://localhost:8080/mcp`): /mcp__job-agent__find-jobs
-> resume read (1.6 yrs, Java primary, full skill list) -> asked location + remote -> match_jobs -> Paytm TL 82,
Zscaler SDE 69, Paytm SSE 68, Sarvam Backend 73; Claude flagged July-2025 postings as possibly stale (postedAt).
Claude skipped the "confirm job families" step once (prompt guidance is not enforcement). 128 unit tests.

**EXACT NEXT STEP:** Milestone 5 (quality). Plan to agree with the user first, in this order: (1) labeled
set: user marks ~100 jobs fit / not fit (spread across companies, oversample MEDIUM/LOW/UNCLASSIFIED) to get
precision@10 for every ranking change; (2) wider shortlist (~50) + model re-rank, user sees top N, allow >25 on
request; (3) embeddings for fuzzy skill/role similarity (ask before downloading any model); (4) extraction
cascade (rules -> local model -> Claude) with the bake-off. Use product sessions from ~/job-search, not this repo.
NOTE: Claude Code loads this file into EVERY session in this folder, including sessions where the user USES
the job-agent tools. Keep it current (a stale "not built yet" here made a demo session work around a feature
that existed), and remember a product session may take the user's profile from §1 instead of the resume.

**How we work (user preferences, keep following them):**
- User types/pastes all NEW code; Claude creates the empty file first (`touch`), then gives the whole file
  with: purpose, a short table of methods, new concepts, shortcomings. Keep explanations SHORT and precise.
- EXISTING files: Claude makes every change itself (decided 2026-10-05: tracing paste locations is hard)
  and tells the user exactly what changed and why. NEW files: Claude creates them empty and the user pastes.
  Claude may write data files (CSV) and docs. Never push. Ask before commits unless the user said "commit".
- Before handing over code, Claude verifies it in the session scratchpad: compile with JDK 25
  (`/Users/saksham/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home`) against jars in
  `~/.m2`, run against real data (read-only `psql` export or JDBC SELECT), and run JUnit tests. Before a
  refactor, dump a baseline of results for all jobs and diff after.
- Never use em-dashes in writing.
- Testing the RUNNING app while building (curl against localhost endpoints, hand-made MCP calls): do NOT run
  it yourself; give the user the curl commands with what each one shows, and the user runs them and pastes
  results (decided 2026-10-05). Scratch compilation / offline checks are still fine. This does NOT apply
  when the user asks Claude a question that it answers through the job-agent MCP tools: that is the product
  being used, so just call the tools.

**Run / check:**
- App: IntelliJ run `JobagentApplication`, or `export JAVA_HOME=<jdk25 above>; ./mvnw spring-boot:run`.
- Unit tests (no DB needed): `./mvnw -q test -Dtest='*Test'` (`JobagentApplicationTests` boots Spring and
  would migrate the real DB, so it is excluded by this pattern).
- DB: `docker exec -it rag-postgres psql -U postgres -d jobagent` (Docker Desktop must be running).
- Endpoints: `GET /admin/companies[/{slug}]`, `GET /admin/crawl/{slug}/preview?limit&full`,
  `POST /admin/crawl/{slug}`, `POST /admin/crawl` (all), `POST /admin/requirements/rebuild[?all=true]`,
  `GET /admin/requirements/coverage`, `POST /admin/match?limit=` with a Profile JSON body.

**Code map:** `company` (registry) · `crawl` (+ `crawl.adapter`: Greenhouse/Lever/SmartRecruiters/Ashby,
JsonFields) · `geo` (Gazetteer, LocationParser) · `job` (NormalizedJob, JobRepository upsert) ·
`requirements` (ExperienceExtractor, JobClassifier/JobFamily, DescriptionSections, SkillExtractor,
Requirements{Repository,Service,Controller}) · `matching` (Profile, MatchCandidateRepository, MatchScorer,
MatchService, MatchController) · `common` (CsvResource). Data: `resources/geo/*.csv`, `resources/classify/*.csv`.
DB: Flyway V1-V7 (companies, jobs, job_requirements, function column).

**Milestone 4 plan (MCP server), versions checked on Maven Central 2026-10-05:** Spring AI **2.0.1** (latest
GA; built against Spring Boot 4.1.1; uses the MCP Java SDK 2.0.0). Use the Spring AI BOM
(`org.springframework.ai:spring-ai-bom:2.0.1`, import scope) and the starter
`spring-ai-starter-mcp-server-webmvc` (HTTP transport on our existing Tomcat; the plain
`spring-ai-starter-mcp-server` is the stdio variant). Steps, one at a time, explained slowly:
1. pom: BOM + starter (DONE 2026-10-05, jars downloaded) and `spring.ai.mcp.server.*` properties.
   Finding: with no properties the server starts on the DEPRECATED SSE transport (`GET /sse` answers,
   `/mcp` is 404), although the metadata claims `protocol=streamable`; the auto-config conditions check
   `spring.ai.mcp.server.protocol` = SSE (the effective default) / STREAMABLE / STATELESS. So set
   `protocol: STREAMABLE` explicitly. Annotations (package `org.springframework.ai.mcp.annotation`):
   @McpTool, @McpToolParam, @McpPrompt, @McpArg, @McpResource (+ advanced: @McpProgress, @McpLogging,
   @McpElicitation, @McpSampling, @McpComplete). `@Tool`/`@ToolParam` in spring-ai-model are for tools an
   in-app LLM calls, not for MCP. Defaults: endpoint `/mcp`, type `sync`, annotation scanner enabled.
   DONE: `application.yaml` has `spring.ai.mcp.server` name job-agent, version 0.1.0, protocol STREAMABLE,
   instructions (incl. "never guess location, ask"). Handshake verified by hand with curl (JSON-RPC:
   initialize -> Mcp-Session-Id header -> notifications/initialized -> tools/list -> tools/call; answers as
   SSE `event:message` / `data:`).
2. First tool: `list_companies` (smallest possible), connect Claude Code, call it. DONE 2026-10-05:
   `mcp/CompanyTools` (@Component, @McpTool name/description/annotations hints, returns CompanySummary
   records). Client config: `.mcp.json` in repo root (project scope, `{"type":"http","url":
   "http://localhost:8080/mcp"}`), approved once via `claude` in the folder; `claude mcp list` shows
   Connected. Works in new Claude Code sessions (desktop app Code tab or terminal) opened in this folder; the
   desktop Settings > Connectors screen never lists project servers. Tomcat logs harmless macOS
   "Error setting socket options ... setSoLinger ... Invalid argument" when clients drop connections early.
3. `match_jobs(profile)`: tool + parameter descriptions; the description must tell Claude to ASK the user
   for location preference, never infer it from the resume. DONE 2026-10-05: `mcp/JobTools.matchJobs` with
   FLAT parameters (user chose option B): yearsOfExperience, skills (only required one), primaryLanguages,
   preferredLocations, openToRemote, families (List<JobFamily> -> enum in the schema), limit (default 10,
   max 25); builds a Profile and calls MatchService. Schema generated by Spring AI's McpJsonSchemaGenerator
   (needs `-parameters`, which the Boot parent sets). A thrown IllegalArgumentException becomes an MCP tool
   error (isError true, message duplicated by Spring AI). Verified over curl: 52 eligible, Paytm on top.
   FIRST END-TO-END DEMO PASSED 2026-10-05 (new Claude Code session, desktop app): Claude ASKED for location
   (choice UI) before calling match_jobs, sent 3 yrs / Java / 10 skills (all recognized), got 52 eligible,
   and grouped results into strong fits (Paytm Backend TL/STL 81, Zscaler Sr. SDE 78), worth a look, and skip
   (security/SIEM/Jira roles that only score from Java/AWS). Findings: (1) non-backend tech roles rank too
   high for a backend profile (default families = all TECH); tune in M5 (families hint, specialization);
   (2) Claude offered to "open the full description" -> needs get_job (step 4).
4. `get_job(id)`: full description + extracted requirements. DONE 2026-10-05: `job/JobQueryRepository`
   (read side; JobDetails record; LEFT JOIN job_requirements) + `JobTools.getJob(long jobId)` returning
   JobView(job, descriptionTruncated) with the description cut at 12,000 chars; unknown id -> tool error.
   Demo: Claude chained match_jobs -> get_job(395) and compared requirements line by line, used postedAt
   (posting 16 months old), and chose families itself (SOFTWARE_ENGINEERING + INFRA_DEVOPS). Found a
   classifier gap: Zeta "Executive - IT Support" -> SOFTWARE_ENGINEERING (add an "it support" SUPPORT title
   rule + bump EXTRACTOR_VERSION in the next rules pass). Possible improvement: when cutting long
   descriptions, drop INTRO sections first (DescriptionSections).
5. `new_jobs_since(date, profile)`.
6. `find-jobs` MCP prompt (read resume -> fill profile -> ask location -> call match_jobs -> explain).
7. End-to-end demo with the user's resume.

**MCP know-how learned (Spring AI 2.0.1):** tools = `@Component` bean + `@McpTool(name, description,
annotations = @McpTool.McpAnnotations(title, readOnlyHint, destructiveHint (defaults TRUE, set false),
idempotentHint, openWorldHint))`; params = `@McpToolParam(description, required)` (default required=true);
schema built by `McpJsonSchemaGenerator` from parameter names/types (enum -> allowed values); return value
is serialized to a text content block; a thrown exception becomes `isError: true` (message shown twice).
Protocol over curl: initialize (save `Mcp-Session-Id`) -> notifications/initialized (202) -> tools/list ->
tools/call; replies come as SSE (`event:message`, `data:{...}`); JSON-RPC ids match replies to requests.
`type: sync` = blocking tool methods (vs async Mono/Flux). Logs: `McpAsyncServer : Client initialize request`
per connection; harmless macOS Tomcat "setSoLinger Invalid argument" errors.

**Backlog (noted, not done; pick up in M5 or a rules pass):**
- Result size: keep max 25 now, but allow the user to ask for more (e.g. 50) later; account for token/LLM cost
  then (user, 2026-10-05). M5: scorer builds a wider shortlist, a model re-ranks it, user sees the top N.
- Ranking boost when the job's specialization matches the profile's (backend over frontend), tuned on the
  labeled set in M5.
- Classifier: "Executive - IT Support" (Zeta) -> SOFTWARE_ENGINEERING; add an "it support" SUPPORT title rule;
  bump EXTRACTOR_VERSION after any rule/CSV change and run `POST /admin/requirements/rebuild`.
- Primary-language quirk: "Jira Administrator" looks Java-primary from "Java/Python scripting".
- Non-backend tech roles (security, SIEM) rank high for a backend profile: consider specialization (BACKEND)
  in scoring and category weights for skills (LANGUAGE/FRAMEWORK/DATASTORE above TOOL/CONCEPT).
- get_job: when cutting long descriptions, drop INTRO sections first.
- Company boilerplate lines (same line in >50% of a company's postings) should be ignored by extractors.
- Duplicate postings per city (Freshworks/ServiceNow "Armis" pairs) -> dedup (M7).
- SmartRecruiters fetches every detail each crawl (~117 s PhonePe) -> fetch only new/changed (M7).
- Closed-job detection, scheduler with per-host virtual threads, crawl_runs, health alerts (M7).
- Faster skill matching (Aho-Corasick / pre-filter) before 8,000 jobs; full rebuild is 17.6 s for 706.
- Constants to config: country IN and years tolerance (-1/+3) in MatchService.
- M5: gold set (user labels ~100 jobs, spread across companies), local-model bake-off, cascade, embeddings.
- M6: Workday/Eightfold/Oracle with the generalization check (expect hardware roles -> HARDWARE family,
  bank level ladders via levelScheme).

## 0b. Progress log
Milestone 0 DONE (2026-10-04): DB `jobagent`, Flyway V1 (companies table, config jsonb) + V2
(12 companies: greenhouse/lever/smartrecruiters/ashby), `company` package (Company record,
CompanyRepository with JdbcClient + manual row mapper, CompanyController GET /admin/companies[/{slug}]).
Milestone 1 in progress: V3 jobs table, `crawl` package (RawJob, RawLocation, JobBoardAdapter,
CrawlHttpConfig shared RestClient, adapter/GreenhouseAdapter, CrawlService with adapter map, CrawlController
GET /admin/crawl/{slug}/preview), Jsoup 1.23.2 added, `geo` package (Gazetteer over 4 CSVs: 249 countries,
111 subdivision codes, 3 metros, 169 city names), ParsedLocation + LocationParser (country voting,
multi-city segments, connector splitting) + LocationParserTest (27 green), HtmlToText (Jsoup),
job/NormalizedJob, crawl/JobNormalizer (SHA-256 content hash), CrawlService preview with country filter
(`jobagent.crawl.countries`, default IN) and unresolved-location report. First measured run (2026-10-04):
Groww 7/7, Razorpay 17/21 (other 4 are Malaysia/Singapore, so the research "21 India" was wrong),
Zscaler 82/364, Databricks 94/887, 0 unresolved jobs. Then: spaced " - " as part separator (28 tests),
V4 (country_codes, places JSONB, GIN on cities + country_codes; query with `&&` / `@>`, not `= ANY`),
job/JobRepository.upsert (ON CONFLICT, one seenAt per crawl, RETURNING -> INSERTED/UPDATED/UNCHANGED),
CrawlService.crawl (fetch outside tx, upserts in one TransactionTemplate tx, per-job skip on errors),
POST /admin/crawl/{slug} and POST /admin/crawl (all). First saved crawl: 200 India jobs (databricks 94,
zscaler 82, razorpay 17, groww 7); re-crawl = all unchanged. Then LeverAdapter (country hint only for
single-location postings), SmartRecruitersAdapter (server-side country=in, offset paging, one detail call
per posting with 250 ms pause: PhonePe ~117 s), AshbyAdapter (postal address -> text). Milestone 1 core
DONE 2026-10-05: 12/12 companies, 706 India jobs (lever 249, greenhouse 200, smartrecruiters 198,
ashby 59). JsonFields refactor done (shared null-safe JSON helpers for adapters).
Milestone 2 core DONE 2026-10-05: `requirements` package: ExperienceExtractor (+ title estimate fallback,
23 tests), JobFamily + JobClassifier (evidence voting, TECH umbrella, 14 tests), DescriptionSections,
SkillExtractor (127-skill dictionary, ambiguous aliases, 16 tests), RequirementsRepository/Service/Controller
(V5 job_requirements, V6 years_confidence, EXTRACTOR_VERSION=1, POST /admin/requirements/rebuild[?all],
GET /admin/requirements/coverage); data files in src/main/resources/classify/. First full run: 706 jobs in
17.6 s; years HIGH 447 / MEDIUM 136 / LOW 53 / NONE 70; 29 UNCLASSIFIED; 392 with required skills, 205 with
a primary language; re-run extracts 0. Leftovers fixed by Claude on request (2026-10-05): common/CsvResource
(one CSV reader), ExperienceExtractor uses DescriptionSections (intro sections skipped), skills after a slash
count ("Python/Java", "Java/Go"), `~ML` is ambiguous (Zscaler appends "AI/ML" boilerplate to every job),
jobs.function column (V7, backfilled) filled by adapters, CrawlService runs incremental extraction after a
crawl that inserted/updated jobs (CrawlReport.extracted). EXTRACTOR_VERSION=2. 90 unit tests green.
Future idea: detect company boilerplate lines (same line in >50% of a company's postings) and skip them.
Concurrency plan (decided 2026-10-05): crawling is network-bound, so in M7 the scheduler crawls companies
grouped BY HOST in parallel (one virtual thread per host, sequential + polite within a host); extraction is
CPU-bound and incremental, so it stays single-threaded (optimize regex matching before adding threads).
Milestone 3 DONE 2026-10-05: `matching` package: Profile (preferredLocations = explicit filter only),
MatchCandidateRepository (hard filters in SQL: open, IN, family, location/remote/no-city, years -1/+3,
unknown years pass), MatchScorer (skills 65 capped at 8 required, primary language 20, experience 15,
neutral 0.5 for missing data, human-readable reasons; 9 tests), MatchService (canonical skills via
SkillExtractor.canonical, places via Gazetteer, unknownSkills/unknownLocations reported), MatchController
POST /admin/match. Fixed Gazetteer.citiesInMetro key bug. Real run (3-yr Java backend profile): 52 eligible
across India, Paytm Java backend roles on top; NCR filter -> 8 eligible. Known quirk: "Jira Administrator"
looks Java-primary from "Java/Python scripting". Next: Milestone 4 (MCP server).

## 1. Why this project (context)

- The user is a backend engineer (Java, Spring Boot, Kafka, Postgres, Redis, DynamoDB, Elasticsearch; UKG
  payments, Goldman Sachs internship; resume has a distributed chat platform project). They want an AI
  project for their resume that is a real agent that gets things done, not a wrapper.
- Lessons from earlier projects:
  - `~/Desktop/Study/code-mcp` (closed): vector search over code was weak; agents used grep. Vectors work
    when the query and the documents are both natural language, paraphrased, without exact identifiers, on
    data the model has not memorized, and when there is an answer key. Resume text vs job descriptions
    fits all of these.
  - `~/Desktop/Study/oncall-copilot`: judged too thin for the resume because "the model does the
    impressive part". So here the impressive part must be OUR engineering: multi-source crawling, adapters,
    normalization, dedup, change detection, health monitoring, ranking quality with measured numbers.
- Separately, the user also chose an **LLM gateway** as their one systems/infra project (not started; see
  the memory note). The job agent can route its LLM calls through that gateway later, tying both together.
- Ideas considered and rejected for this agent: auto-applying (breaks site terms, CAPTCHAs, bans, spammy);
  LinkedIn / Naukri / Instahyre scraping (no API, against terms); aggregator APIs like JSearch / Adzuna as
  the main source (easy but less impressive; free tiers limited). They may be an optional fallback.

## 2. Working agreement (user preferences)

- Explain concepts briefly with concrete examples, then code. Short answers for small questions.
- Learning mode (decided 2026-10-04): the user builds step by step and types/pastes ALL code themselves.
  Claude NEVER writes code into the repo (no Java, SQL, YAML, pom edits). For a new file, Claude only
  creates it empty (touch) at the right path; the user pastes the contents after asking questions.
  Claude may edit docs (CLAUDE.md, notes) and data files (e.g. `src/main/resources/geo/*.csv`) itself.
- Give WHOLE files, ONE file at a time. With each file: what it achieves and why it exists, a table of
  every function/method + a one-line purpose, and its shortcomings / known limitations / what a
  production version would do differently.
- The user runs commands; Claude may run read-only checks when asked. Ask before anything that writes
  (DB, git commits, installs) and before downloading anything (models, packages). Never push.
- Analyses of pasted output: a few lines (what's right, what's wrong, did it improve).
- Never use em-dashes in writing.
- The user is new to Python (comfortable with requests, functions, classes, list comprehensions, env vars;
  no async/typing yet) but strong in Java. Language/stack choice is still open (see §8).
- Local services available: Postgres 18 + pgvector in Docker container `rag-postgres` (localhost:5433,
  postgres/ragpass). Hugging Face embedding models were deleted on 2026-10-01 to free disk; any embedding
  model must be re-downloaded (ask first).
- The Claude CLI is logged in on the user's subscription (headless `claude -p` works), as used in
  oncall-copilot.

## 3. Data sources: research results (2026-10-04)

51 major tech companies in India were checked live with read-only requests. Full details (exact URL,
method, body, headers, India count, sample job, notes) are in `research/sources_2026-10-04.json`.

Summary: **41 work, 2 partial, 4 have no fetchable list, 4 blocked by bot protection. ~7,700 India jobs
in total.** The user manually verified the two lowest counts on the websites (Flipkart 8 = "Showing 8
Jobs", Oracle 15 = "JOBS 15"), so the API totals are credible.

### 3.1 By platform (India job counts as of 2026-10-04)

| Platform | Companies (India jobs) | Notes |
|---|---|---|
| **Workday** (9) | Nvidia 244, Mastercard 207, Salesforce 111, Visa 81, Adobe 79, Intel 58, Samsung 31, Expedia 30, PayPal 10 | One adapter |
| **Eightfold** (4) | Qualcomm 573, Microsoft 219, Morgan Stanley 118, UKG 81 | One adapter; MS and UKG need a cookie + CSRF handshake |
| **Oracle Recruiting Cloud** (5 + Dell) | JPMorgan 313, Texas Instruments 133, Goldman Sachs 102, Amex 49, Oracle 15 | One adapter; India `locationId` differs per tenant |
| **Greenhouse** (4) | Databricks 95, Zscaler 84, Razorpay 21, Groww 7 | Official public API |
| **Lever** (3 + Zeta) | Paytm 161, Meesho 56, CRED 8 (Zeta 23) | Official public API |
| **SmartRecruiters** (3) | PhonePe 90, ServiceNow 77, Freshworks 31 | Official public API |
| **TurboHire** (2) | Flipkart 8, Ola 0 | Anonymous token flow |
| **Custom / one-off** | **Amazon 2,324**, IBM 884, Cisco 285 (Phenom), Google 285, Deutsche Bank 232 (BeeSite), AMD 205 (iCIMS/Jibe), Apple 160, Swiggy 78 (MyNextHire), Atlassian 62, Intuit 28 (Radancy), Zoho 2 (Zoho Recruit) | One adapter each |
| Partial | Dell (Oracle feed works, 0 India jobs; mid-migration, re-check), LinkedIn (guest HTML endpoint; DO NOT use, against terms) | |
| No fetchable list | Walmart Global Tech (Next.js site, probable GraphQL not identified), Zomato/Eternal (no listings), Myntra (Spire2Grow API needs a token), Dream11 (Lever board removed) | Skip for now |
| Blocked (bot protection) | Meta (GraphQL + tokens), SAP (Cloudflare), Uber (Cloudflare), Zepto (AWS WAF) | **Skip. Never bypass bot protection** |

About 6 platform adapters cover ~28 companies; custom adapters are added selectively by value (Amazon alone
has ~2,300 India jobs).

### 3.2 Platform recipes (what worked)

- **Workday**: `POST https://<tenant>.wd<N>.myworkdayjobs.com/wday/cxs/<tenant>/<site>/jobs`, JSON body
  `{"appliedFacets":{...},"limit":20,"offset":0,"searchText":""}`. Response: `total`, `jobPostings[]`,
  `facets[]`. **Never filter with `searchText:"India"`**: it matches "Indiana". Instead read the facets of
  an unfiltered first call and apply the India filter id. The country facet has different names per
  tenant: `locationCountry` (Adobe, Visa), `Location_Country` (Samsung), a long custom name (Salesforce),
  `locationHierarchy1` (Nvidia), or only city-level `locations` (Intel, PayPal, Mastercard, Expedia: sum
  the India cities). The India country id `c4f78be1a8f14da0ab49ce1162348a5e` was the same across tenants.
  Job details: `GET .../wday/cxs/<tenant>/<site>/job/<externalPath>`.
- **Eightfold**: only the PCSX API works: `GET https://<host>/api/pcsx/search?domain=<company domain>&query=&location=India&start=0`
  (`data.count` = total, 10 per page). `/api/apply/v2/jobs` returns 403 "Not authorized for PCSX". Some
  tenants (Morgan Stanley, UKG) need the session cookie from `GET <host>/careers?location=India` plus an
  `x-csrf-token` header taken from `<meta name="_csrf">` on that page. Hosts: apply.careers.microsoft.com,
  careers.qualcomm.com, morganstanley.eightfold.ai, apply.ukg.com.
- **Oracle Recruiting Cloud**: `GET https://<host>/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true&expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber=<site>,locationId=<id>,limit=25,offset=0,sortBy=POSTING_DATES_DESC`
  (`TotalJobsCount`). The India `locationId` is tenant-specific: discover it with
  `facetsList=LOCATIONS` + `expand=locationsFacet`. Keyword "India" undercounts (TI: 16 vs 133).
  Tenants: eeho.fa.us2/CX_45001 (Oracle), edbz.fa.us2/CX (TI), hdpc.fa.us2/LateralHiring (Goldman, found
  in higher.gs.com's JS), jpmc.fa/CX_1001 (JPMorgan), egug.fa.us2/CX_1 (Amex), enterpriseplatform.dell.com/CX_1001 (Dell).
- **Greenhouse**: `GET https://boards-api.greenhouse.io/v1/boards/<token>/jobs` (`?content=true` for
  descriptions). No server-side location filter; filter on `location.name` (Zscaler uses "IND" too).
- **Lever**: `GET https://api.lever.co/v0/postings/<name>?mode=json`; filter by `categories.location`.
- **SmartRecruiters**: `GET https://api.smartrecruiters.com/v1/companies/<id>/postings?country=in&limit=100&offset=N`.
  Company ids can be non-obvious (`PHONEPELIMITED`, not `PhonePe`).
- **Ashby**: `GET https://api.ashbyhq.com/posting-api/job-board/<name>` (e.g. Sarvam AI, 59 jobs).
- **TurboHire**: `GET https://api.turbohire.co/api/token/noauth` with the career page's Origin + Referer
  headers gives the same anonymous token the public page uses, then `POST https://api.turbohire.co/api/careerpagev2/filteredjobs?orgId=<org>&pageType=0`
  body `{}` with `Authorization: Bearer <token>`.
- **Custom**:
  - Amazon: `GET https://www.amazon.jobs/en/search.json?normalized_country_code[]=IND&result_limit=10&offset=0`.
  - IBM: `POST https://www-api.ibm.com/search/api/v2`, an Elasticsearch-style body with `post_filter` on
    `field_keyword_05` = India, plus an `Origin: https://www.ibm.com` header.
  - Cisco (Phenom): `POST https://careers.cisco.com/widgets` with `ddoKey: refineSearch`,
    `selected_fields.country: ["India"]`, `refNum: CISCISGLOBAL`.
  - Google: no JSON API. Parse the `AF_initDataCallback` `ds:1` blob in the results page HTML (20 per page).
  - Apple: parse `window.__staticRouterHydrationData` in `jobs.apple.com/en-in/search?location=india-INDC`.
  - Deutsche Bank (BeeSite): one GET returns all ~1,864 jobs; filter `CountryCode=IN` locally.
  - AMD (Jibe): `GET https://careers.amd.com/api/jobs?location=India&page=1&limit=10`.
  - Atlassian: `GET https://www.atlassian.com/endpoint/careers/listings` returns all jobs; filter locally.
  - Intuit (Radancy): JSON wrapping an HTML fragment; India facet id 1269750.
  - Swiggy (MyNextHire): `POST https://swiggy.mynexthire.com/employer/careers/reqlist/get` body
    `{"source":"careers","code":"","filterByBuId":-1}`.
  - PhonePe: also `phonepe.com/apollo/job-postings/latest.json` (keep `status=PUBLIC`).
  - Zoho: a JSON list in a hidden `<input>` on careers.zohocorp.com/jobs/Careers.
- **Uber**: `jobs.uber.com/robots.txt` allows all and publishes `https://jobs.uber.com/en/jobs/sitemap.xml`
  (583 job IDs like `/en/jobs/149574/`), but the sitemap has no titles or locations, and the job pages are
  behind Cloudflare's challenge. Applications go through Workday, but there is no public Workday board. At
  most: use the sitemap to detect "N new Uber jobs" and link the user to open them in their own browser.

### 3.3 How to verify a count manually

Open the careers page in Chrome → DevTools (Cmd+Option+I) → Network → Fetch/XHR → apply the India filter
→ click the request → Response. Compare its total with the number on the page. If the page shows more,
we are querying the wrong site/org or too narrow a filter; if both agree, the number is real.

## 4. Rules for crawling (non-negotiable)

- Read-only requests to public, unauthenticated endpoints that the public careers page itself uses, or
  official APIs. Never log in, never create accounts.
- Never bypass bot protection: no Cloudflare/WAF/CAPTCHA solving, no headless-browser stealth. A blocked
  site is skipped.
- Be gentle: low request rate per host, delays, caching, conditional requests where possible, crawl every
  few hours, not continuously. Respect robots.txt. Identify with a sensible User-Agent.
- Store only what is needed (job fields), keep personal data (resume) local.
- No auto-apply. The agent prepares; the human submits.

## 5. Architecture (planned)

```
companies config (DB) ──> scheduler ──> adapters (per platform + custom) ──> normalizer ──> jobs DB
                                             │                                    │
                                    health checks/alerts                 dedup + change tracking
                                                                                  │
resume ──> embeddings ──> candidate shortlist (vector) ──> LLM re-rank with reasons ──> agent (MCP tools)
                                                                                  │
                                    digests, outreach drafts, tracker (Notion/Sheets), reminders
```

- **Company registry** (in the DB, not code): company, platform, tenant/host, site, India filter ids,
  enabled flag, last success, last count. Adding a company on a known platform = one row, no code.
- **Adapters**: one per platform (Workday, Eightfold, Oracle, Greenhouse, Lever, SmartRecruiters, Ashby,
  TurboHire, Phenom, Radancy, Jibe) + one per custom site (Amazon, IBM, Google, Apple, Deutsche Bank,
  Atlassian, Swiggy...). A common interface: `list_jobs(config) -> [RawJob]`, `job_detail(config, id)`.
- **Normalizer**: one schema: source, company, external id, title, locations (normalized cities: Bengaluru
  = Bangalore), remote flag, department, experience range if available, posted date, URL, description,
  first_seen, last_seen, closed_at.
- **Change tracking**: diff each crawl: new jobs, updated, closed (missing for N crawls). Enables "new
  jobs since yesterday" digests.
- **Dedup**: same job posted in several cities or on two sites (e.g. PhonePe on SmartRecruiters and its
  own JSON).
- **Health detection and alerts (admin)**: after each crawl per company, check: HTTP errors, schema/parse
  failures, redirects to a new domain, platform fingerprint changed (e.g. page now mentions eightfold
  instead of myworkdayjobs), count dropped to 0 or by >X% vs the recent average. Raise an alert (log +
  email/Slack/dashboard) so someone manually checks and updates the company's config row. Real examples
  already seen: Microsoft moved to Eightfold, Adobe has a custom site alongside Workday, UKG moved from
  Workday to Eightfold, Amex's old Eightfold link is dead, Dell is mid-migration, Dream11's Lever board
  was removed.
- **Matching**: embed the resume (and/or its sections) and job descriptions; vector shortlist (top ~50),
  then an LLM re-ranks with reasons and a fit score; hard filters for location / experience.
- **Agent**: Claude (headless `claude -p` on the subscription, as in oncall-copilot, or the API later)
  with MCP tools over the jobs DB: search jobs, get job, explain fit, draft a tailored summary / cover
  note / referral message, add to tracker, set a follow-up reminder. Tracker via a Notion or Google
  Sheets MCP server.

## 6. Evaluation (resume numbers must be real)

- Coverage: companies and platforms covered, jobs per crawl, crawl duration, freshness (time from posting
  to our DB), adapter failure rate, time to detect a broken source.
- Matching quality: the user labels ~100 jobs as fit / not fit; report precision@10 / nDCG of keyword vs
  vector vs vector + LLM re-rank.
- Placeholder numbers are allowed only in sample resumes shared with friends; real resume numbers come
  from measurements.

## 7. Milestones (decided 2026-10-04; vertical slice first, then widen)

0. Skeleton: Spring Initializr project, own database `jobagent` in `rag-postgres`, Flyway schema
   (companies, jobs, crawl_runs), import the company registry from `research/sources_2026-10-04.json`.
1. Adapter framework + easy official APIs (Greenhouse, Lever, SmartRecruiters, Ashby): RestClient,
   per-host politeness, normalizer (cities, remote), upsert into `jobs`, manual trigger endpoint.
2. Rule-based requirement extraction: years-of-experience regex, skill dictionary with aliases, primary
   language, seniority, job family (tech vs non-tech: SOFTWARE_ENGINEERING, DATA_ML, INFRA_DEVOPS,
   SECURITY, QA, ENG_MANAGEMENT, PRODUCT, DESIGN, SALES_ENGINEERING, SALES, MARKETING, FINANCE, HR, LEGAL,
   OPERATIONS, SUPPORT, OTHER) + specialization (BACKEND, FRONTEND, ...), normalized employment type;
   stored per job. Family rules: specific title patterns first ("Sales Engineer" before "Engineer"),
   department/function as evidence (ignore "Business"/"Other"), unclassified report, labeled accuracy.
   Family is DERIVED by us (platforms give only free-text department; SmartRecruiters adds a `function`,
   often "Other"). Raw department names map to our taxonomy via a data file (Engineering/Technology/Tech/
   R&D -> tech families), like city aliases. Titles are split into ROLE words (engineer, SDE, SWE, MTS,
   data scientist, accountant: decide family) and LEVEL words (Analyst/Associate/VP at banks, SDE I/II/III,
   AMTS/MTS/SMTS/LMTS/PMTS, Senior/Staff/Principal: decide seniority, never family). "Analyst"/"Associate"
   alone carry no family (seen at Paytm/PhonePe/Zeta for support, legal, ops). Company-specific level
   ladders via an optional `levelScheme` in companies.config ("bank": Analyst < Associate < VP < ED < MD).
   Family decided by evidence voting (title role words > department/function > description skills),
   low evidence = UNCLASSIFIED (reported), LLM fallback in M5.
   End-goal search filters: family, specialization, city/metro/country/remote, years, skills,
   employment type, company, freshness, keyword text (M3), fit score (M3/M5).
   Seniority labels DROPPED (decided 2026-10-05, user's call): matching needs years, which ExperienceExtractor
   finds for 602/706 jobs; level words like Manager/Executive/Analyst/Associate mean different things per
   company (in India "Manager" is often a pay band). Of the 104 jobs without years only 35 have a clear level
   word. At most a tiny fallback: estimate years only from unambiguous title words (intern/trainee 0-1,
   junior 0-2, senior 5+, staff/principal 8+, director/head/VP 12+), LOW confidence. Unknown years never
   exclude a job. The `seniority` column in job_requirements stays unused (nullable).
3. Matching v1 (no LLM, no embeddings): profile record, experience hard filter, weighted skill score,
   score breakdown; REST endpoint to test it.
4. MCP server (Spring AI MCP server starter): match_jobs (with postedSince), get_job, list_companies + a
   `find-jobs` prompt. Connect Claude Code. FIRST END-TO-END DEMO. DONE 2026-10-05.
5. Quality: ClaudeCliChatModel (custom Spring AI ChatModel over `claude -p`) for extraction leftovers,
   local embeddings + PgVectorStore (ask before download), labeled eval set, keyword vs hybrid numbers.
   Build the extraction cascade (see §8 "Extraction cascade"), including the local-model bake-off.
6. Workday (facet discovery), Eightfold (cookie/CSRF), Oracle (locationId discovery).
7. Scheduler, change tracking (new/updated/closed), dedup, health checks + admin alerts.
8. Custom adapters by value: Amazon, IBM, Cisco, Google, Apple, then the rest.
9. Extras: digests, draft_referral, tracker, README, measurements; later a read-only web UI.

## 8. Decisions and open questions

Decided (2026-10-04):
- Stack: all Java / Spring Boot + Spring AI (user's strength, Spring AI learning goal). Spring Boot
  4.1.1 (modular starters: `starter-webmvc`, per-module `-test` starters), Maven, Java 21 target (virtual
  threads; avoid `synchronized` around blocking I/O, use ReentrantLock), run on the local JDK 25.
  Group `io.github.saksham023`, package `io.github.saksham023.jobagent`, artifact `jobagent`.
  Data access: plain `JdbcClient` (JDBC API starter), no JPA / Spring Data JDBC / Spring Batch.
  Schema via Flyway (`src/main/resources/db/migration`). DB: database `jobagent` in `rag-postgres`.
  Spring AI deps (MCP server, pgvector, embeddings) are added by hand to the pom when needed.
- Location handling (decided 2026-10-04, from real Greenhouse strings): adapters emit
  `RawLocation(text, city, region, countryCode, remote)` with structured parts when the platform has them;
  a gazetteer-based `LocationParser` (data files in `src/main/resources/geo/`: country aliases on top of
  JDK `Locale` ISO lists, subdivisions, cities with aliases) resolves the rest: split on `;`/`|`/` / `,
  strip remote prefixes, split components on `,`, resolve right to left, whole-component matches only
  (Indiana != India), bare 2-letter codes from free text never trusted as countries, ambiguous = marked.
  Jobs store `country_codes TEXT[]` + parsed `places JSONB`; "India only" is a filter (`'IN' = ANY`), not
  parser logic. Unresolved strings are kept, reported (top unresolved), and measured per company
  (% resolved = health metric); LLM fallback per distinct string later (Milestone 5), cached.
- Company registry seed: curated SQL migrations per milestone (the research JSON is free text, not
  machine-importable); per-platform settings live in a `config jsonb` column.
- LLM access without an API key: (a) background jobs (job requirement extraction, digests) call headless
  `claude -p` via a custom Spring AI `ClaudeCliChatModel implements ChatModel` (`--output-format json`,
  `--json-schema`, `--tools ""`), used for completions/structured output only (Spring AI tool calling
  does not work through the CLI). Swappable for `AnthropicChatModel` / the LLM gateway later.
  (b) Interactive use: MCP-first. Our Spring AI MCP server exposes tools; the USER's own Claude (Desktop,
  claude.ai, Code) reads the resume, fills the profile schema, calls `match_jobs`, explains/re-ranks.
  No per-user LLM cost on our side; serving other users from a personal subscription is not allowed.
- **Extraction cascade (decided 2026-10-05, user's design; a focus area, build it properly).** Applies to
  every extraction task: experience, job family + seniority, location (unresolved strings), skills.
  Tier 1 rules (ms, deterministic, tested) -> HIGH confidence is final. Tier 2 a small LOCAL model
  (Ollama, ~2-4 GB, via Spring AI `OllamaChatModel`) for LOW / UNCLASSIFIED / unresolved cases and
  rule-vs-model disagreements. Tier 3 Claude (`ClaudeCliChatModel`) only when tier 2 fails. MEDIUM is
  verified by tier 2 only if the labeled eval shows rules are weak there.
  A tier-2 answer is accepted only if ALL pass: JSON schema; the model did not answer NOT_SURE (the prompt
  asks it to, but small models are poorly calibrated, so this is never the only check); grounding (the
  returned value literally appears in the source text); agreement with rule candidates (or two runs/models
  agree). Every model answer is cached and stored with its source (`rules` / `local:<model>` / `claude`).
  Model bake-off before adopting tier 2: user labels ~100 jobs (oversample MEDIUM/LOW/UNCLASSIFIED), split
  into tune/test halves; compare 2-3 local models at temperature 0 on accuracy, abstain rate,
  hallucination (grounding-fail) rate and latency, PER TASK; a task skips tier 2 if no model is good enough.
  Report calls, accuracy and latency per tier (resume numbers).
- **Generalization check (decided 2026-10-05).** Extraction rules were fitted to 706 jobs from 12 companies, so
  they are a measured first tier, not truth. For every new platform/company batch (Workday, Eightfold,
  Oracle, custom): run extraction BEFORE editing any rule, measure abstain rate (UNCLASSIFIED / NONE / LOW /
  unresolved) and accuracy of the confident results on ~50 random new jobs (= the honest generalization
  number), then fix rules/data and re-measure. Known gap to expect: semiconductor companies (Nvidia, Intel,
  Qualcomm, AMD, TI) have hardware roles ("ASIC Design / Physical Design / Verification Engineer") that the
  generic "engineer" rule would mislabel as SOFTWARE_ENGINEERING; add a HARDWARE_ENGINEERING family then.
  Banks bring the Analyst/Associate/VP ladder (levelScheme). The gold set must span companies/platforms;
  every rule change is re-scored (regression). Bake-off compares rules vs embedding nearest-neighbour vs
  local LLM on UNSEEN companies; the winner becomes tier 1/2.
- No LLM in the matching step itself: deterministic filters + skill scoring (+ embeddings later).
- **Recall first (decided 2026-10-05, user's call).** Hard filters (family, location, years) must not drop a
  relevant job; including an irrelevant one is cheap because ranking (and later a model re-rank) handles
  precision. So: ambiguous titles stay in the broad family, jobs get secondary families from all evidence
  (title rules, description, department), and the family filter matches any of them. Pure backend roles should
  still rank above hybrids (FDE, solutions) through skills/language now and specialization later.
- Skills: dictionary (exact, explainable) + candidate-side implications (1.0 certain / 0.5 related, one hop) now;
  embeddings for fuzzy similarity in M5. Dictionary growth is data-driven (mention counts + context checks).
- **Location is the user's explicit choice (decided 2026-10-05, user's call).** Never inferred from the
  resume's address (where someone lives is not where they want to work). `Profile.preferredLocations` is a
  FILTER only (empty = anywhere in India; remote jobs pass when openToRemote); location is never part of the
  score. The M4 MCP tool description and `find-jobs` prompt must tell Claude to ASK the user for location
  preferences and never fill them from the resume. Score = skills 65 + primary language 20 + experience 15.
- Stateless profiles first (profile passed per call); `save_profile` later for digests.
- Web UI: later, as a thin layer over the same services (REST + MCP tools share services).

Open:
- Which embedding model: in-JVM ONNX (spring-ai-transformers) vs Ollama (local, needs a download, ask first).
- Delivery of digests: email, Telegram, Slack, or a small web UI.
- Whether to add an aggregator API (Adzuna has an India API with a free key) as a fallback for blocked or
  unlisted companies (Meta, Uber, Walmart, Zomato).
- Extending beyond the 51 companies (more Workday/Greenhouse/Lever tenants are cheap to add).

## 9. Related folders

- `~/Desktop/Study/oncall-copilot`: headless `claude -p` + MCP server patterns (its CLAUDE.md §8).
- `~/Desktop/Study/code-mcp`: MCP server (Python SDK v2), pgvector + embeddings, eval methodology.
- `~/Desktop/Study/python learning`: the RAG learning track.
