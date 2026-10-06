# job-agent: notes for Claude

A personal job-search agent for the Indian tech market. It crawls live job postings directly from major
companies' careers platforms (Workday, Eightfold, Oracle, Greenhouse, Lever, SmartRecruiters, plus custom
sites), ranks them against the user's resume with embeddings + an LLM, and helps act on them (shortlist,
tailored notes, outreach drafts, tracking, reminders). The human always clicks "apply".

This file holds everything decided so far (planned 2026-10-01..04 in the `python learning` session).

## 0. RESUME HERE (read first after a context compaction)

**State (2026-10-06, before a compaction):** M0-M7b DONE and committed; M8 (search with the Opus judge) WORKS LIVE
but is NOT COMMITTED yet. Commits: ... `1497473` M6, `86df413` M7 (Workday, rules v8), `75e6566` M7b (Eightfold,
6 more Workday companies, rules v9-v10, family_guessed). Data: 2,340 open India jobs from 29 companies on 6 platforms
(Greenhouse, Lever, SmartRecruiters, Ashby, Workday, Eightfold); extractor v10; guessed and UNCLASSIFIED families 0.
DB schema at Flyway V15. MCP server `http://localhost:8080/mcp`, 5 tools: `list_companies`, `match_jobs` (now a
JUDGED search: returns APPLY jobs + `searchId`; params incl. `wants`, `families`, `limit`), `more_jobs(searchId,
count=10)`, `export_jobs(searchId, includeMaybe)`, `get_job`; prompt `find-jobs`. 217 unit tests green.
Opus spend so far on the judge in M8: 205 verdicts, $5.50 (3 searches, same profile, verdicts reused).
Full detail of everything done: section 0c. Plain-language history: section 0b.

### NEXT (in this order; 2026-10-06)
1. COMMIT M8 (user said M8 works: "Okay, this is working"). Uncommitted: package `search` (SearchProperties,
   SearchRepository, SearchService, SearchController, SearchServiceTest), V14 (judgments, searches), V15
   (searches.low_priority_from), edits to JobTools, JudgeProfile, JobJudge, application.yaml, JobToolsTest,
   JobJudgeTest, CLAUDE.md. Ask before committing (the user usually says yes). `.m8-wip/` (git-ignored) can be deleted
   after the commit.
2. M8a SAVED PROFILES: plan READY (agreed 2026-10-06, build on 2026-10-07; see "### M8a plan" below).
3. Real end-to-end demo through Claude in `~/job-search` (match_jobs with wants + families, more_jobs, export_jobs).
4. Microsoft crawl (see TODO MICROSOFT in M7b status): one test request first; it rate-limited us on 2026-10-06.

### M8a plan: saved profiles / profile IDs (user's idea; decisions made 2026-10-06; build 2026-10-07)
Why: verdicts are cached per profile_hash; Claude re-reading the same resume can produce a slightly different skill
list or wants text -> new hash -> Opus judges everything again. A stored profile freezes the facts, so the cache hits.
Decisions (user): every new resume = a NEW ID, no matter how similar (no dedup by hash; identical facts still share
verdicts because the cache stays keyed by profile_hash). Profiles are never edited. ID format left to Claude:
`p-` + 8 random Crockford base32 chars from SecureRandom (e.g. `p-7k3x9q2m`, 40 bits, unguessable; retry on the rare
collision). Store only facts, never the PDF, name, email or address.
What is stored vs per search:
- PROFILE (frozen facts from the resume): yearsOfExperience, skills, primaryLanguages, wants, source (mcp/api).
- PER SEARCH (preferences, may change any day): preferredLocations, openToRemote, families, postedSince,
  jobYearsFrom/To. The last ones used are saved on the profile as `defaults` and used when a later call with the
  profileId leaves a preference out (null). Claude must still confirm location with the user (rule stands).
Steps (new files: Claude touches them empty, hands over the code, user pastes; existing files: Claude edits):
1. NEW `V16__create_profiles.sql`: `profiles(id TEXT PK, facts JSONB NOT NULL, profile_hash TEXT NOT NULL (index,
   not unique), source TEXT NOT NULL, defaults JSONB, created_at, last_used_at, last_search_at)`;
   `ALTER TABLE searches ADD COLUMN profile_id TEXT REFERENCES profiles(id)` + index.
2. NEW package `profile`: `ProfileFacts` record (the 4 facts), `SavedProfile` record (id, facts, defaults,
   createdAt, lastUsedAt, lastSearchAt), `ProfileIds` (generator), `ProfileRepository` (insert, find, saveDefaults,
   touch), `ProfileService` (create(facts, source) -> id; get(id) or "unknown profile id" error; merge(saved
   defaults, request preferences) -> Profile + wants), `ProfileController` (`POST /admin/profiles`, `GET
   /admin/profiles/{id}`), `ProfileServiceTest` (id format, merge rules, unknown id).
   The hash is recomputed from the stored raw facts on every search (same facts -> same hash; if the skill
   dictionary grows, the hash changes, and re-judging is then correct).
3. EDIT SearchService.start: takes the resolved Profile + wants + profileId; SearchRepository.create stores
   profile_id; SearchPage gains `profileId`; touch last_used_at/last_search_at and save defaults.
4. EDIT JobTools.match_jobs: new optional `profileId`. Given -> facts come from the profile; passing resume facts
   too is an error ("pass either profileId or the resume facts"). Not given -> skills required, a profile is
   created and its id returned with a note: "Next time just give this profile ID instead of the resume."
   NEW tool `get_profile(profileId)`: stored facts, saved defaults, created / last search dates.
   SearchController: `?profileId=` (body then only preferences). find-jobs prompt + server instructions: ask
   whether the user has a profile ID before reading a resume; after the first search tell them the ID.
5. Tests (unit, all green), user runs V16 + restarts, live checks with curl: create via search -> id; repeat search
   with the id -> 0 new Opus calls; unknown id -> clean error; then the demo in ~/job-search.
Later (not in M8a): "new jobs since my last search" (postedSince = last_search_at), digests per profile, years going
stale (store an as-of date and add elapsed time), deleting a profile on request.

### M8 status (2026-10-06): LIVE AND WORKING, uncommitted (see NEXT 1). History below.
- New package `search` (user pastes, in order): V14 (judgments + searches incl. candidate_scores; was V13, renumbered
  because V13 became add_family_guessed), SearchProperties
  (jobagent.search.*: first-batch 10, ready-target 30, stop-after-nos 20, parallelism 4, page-size 10, max 25, opus),
  SearchRepository (state computed from the tables: unnest(candidate_ids, candidate_scores) WITH ORDINALITY + LEFT JOIN
  judgments on current content_hash), SearchService (start / more / export; worker per search: Set<UUID> running,
  re-check in finally; failed job ids skipped; pauses after 3 failures in a row), SearchController
  (POST /admin/search?wants&postedSince&pageSize body Profile; GET /admin/search/{id}/more?count; GET .../export),
  SearchServiceTest. Claude edited: JobTools (match_jobs -> SearchService.start, new `wants`; new more_jobs,
  export_jobs; searchId parsing), JudgeProfile.years Double (null = "not stated" in JobJudge), application.yaml,
  JobToolsTest, JobJudgeTest. SET ASIDE 2026-10-06 so the user could restart after the Qualcomm crawl: the repo is
  back at the committed versions; ALL M8 files (new + edited) are kept in `.m8-wip/` (same paths, git-ignored via
  .git/info/exclude). Resume: hand over the 6 new files for pasting (V14 first), copy the 6 edited files from
  .m8-wip into place, run the tests, restart.
- TIERS (user's design, 2026-10-06): likely-NO candidates are not dropped but moved to a low-priority tier at the end
  (searches.low_priority_from, V15): senior title (lead 5 / staff, principal, architect, manager 8 / director 12) with
  no years stated and above the window; EMBEDDED without C/C++; job main languages none of the candidate's. The
  low tier is judged only after the main tier is done (all judged or its own 20-NO streak); each tier has its own
  streak. Replay of the first live search: main 133 (29 APPLY, 18 MAYBE, 63 NO), low 102 (1 APPLY, 4 MAYBE, 67 NO).
  match_jobs `families` description now tells Claude to always pass families. First answer judges only unjudged
  candidates among the top first-batch POSITIONS (bug found live: it judged positions 111-120 and made a repeat
  search wait ~17 s). 217 tests.
- First live search 2026-10-06: 235 eligible, 5/10 APPLY in the first batch, more_jobs instant, Opus reasons good;
  answer key: 5 of 7 APPLY agree so far, PhonePe System Integrator got MAYBE (borderline), Zscaler 188 at rank 113.
- RESUMED 2026-10-06 after the M7b commit (75e6566): user pasted all 6 search files (V14 now), Claude put the 6 edited
  files back from .m8-wip; 212 tests green; then tiers (V15, user pasted) + first-batch fix; 217 tests. Flyway at V15.
- Live checks done: search start (5/10 APPLY first batch), more_jobs instant, repeat search reuses all verdicts, tier
  split 133 main / 102 low. Export endpoint not yet shown to the user. Search ids used: 7fec5bab-... (before tiers),
  4668e6f1-... (with tiers).
- Admin curl for a judged search: POST /admin/search?pageSize=10&wants=... with the Profile JSON body (the user's
  profile is in eval/judge-profiles.json, id "saksham"); GET /admin/search/{id}/more?count=10; GET .../export.
- Open M8 follow-ups (backlog): ranking penalty for a specialization mismatch (embedded vs backend) instead of only
  tiering; Zscaler 188 twin ranks 113 vs 21; PhonePe System Integrator flips APPLY/MAYBE between runs (judge noise);
  searches are never cleaned up (last_used_at); no global cap on parallel judge calls across searches.

### Rules v9 (2026-10-06, after crawling Qualcomm 584 + Sprinklr/BlackRock/Wells Fargo/Autodesk/Workday/Ciena 213)
- Claude edited (all existing files, 203 tests): JobClassifier: a generic "engineer" title (title-fallback.csv) is
  ignored when the department names a specific technical/tech-adjacent family (it then decides; the job keeps
  SOFTWARE_ENGINEERING as a secondary unless it is HARDWARE); business departments do not overrule it.
  department-families: ASICS -> hardware; title-families: dv, synthesis engineer -> hardware. ExperienceExtractor:
  level ladders ("Senior Engineer: 3-5 years ... Principal Engineer: 18+") count as their lowest level.
  LocationParser: "IND.Pune" dotted codes, "Remote- India- Gurugram" dash-space separator. EXTRACTOR_VERSION=9.
- Before/after on all 2,340 jobs: 71 SWE -> HARDWARE (Qualcomm/Intel hardware departments), 8 to their specific family
  with SWE kept as secondary, 4 Qualcomm years fixed (18+ -> 3-5), 4 location strings fixed, nothing else changed.
- Next: user restarts, rebuild ?all=true, re-crawl workday + ciena (cities), gap fill (~40 jobs).

### Family guessed (2026-10-06, user chose "the mix"): extractor v10
- Rules keep the catch-all guess (title only "engineer"/"engineering"/"architect" -> SOFTWARE_ENGINEERING) but mark it
  `job_requirements.family_guessed` (V13__add_family_guessed.sql, user pastes). The gap filler treats a guessed family
  like UNCLASSIFIED (only for jobagent.gap-fill.families); applying a model family clears the flag and keeps the
  guessed SOFTWARE_ENGINEERING as a secondary family (not for HARDWARE). reapply() fills a guessed family from an
  earlier stored answer (the model always answers the family) with no new call. Claude edited JobClassifier
  (Classification.familyGuessed), RequirementsRepository, GapFillRepository, RequirementsService (v10), tests (204).
- Counts: 295 guessed (Qualcomm 164), 64 reuse earlier answers, next gap fill ~287 calls ~$7.50.
- DONE 2026-10-06: user ran V13, rebuild v10, re-crawl workday+ciena (cities fixed), gap fill at parallelism 10
  (GapFillRunner.MAX_PARALLELISM raised 4 -> 10 at the user's request): 287 jobs in ~3 min, 0 failed, $8.09.
  Guessed families 0, UNCLASSIFIED 0, 367 families set/confirmed by Opus (this run: SWE confirmed 132, HARDWARE 34,
  DATA_ML 27, QA 19, SALES_ENG 9...). Spot check of 14 changes: all sensible. Next: commit, then resume M8.

### PENDING ACTIONS (older list; all done except Microsoft, see NEXT above)
1. DONE 2026-10-06: Qualcomm + the 6 V12 Workday companies crawled, checked (rules v9/v10), gap fill run.
2. **Microsoft**: still open, see "TODO MICROSOFT" in the M7b status below.
3. DONE: Eightfold work committed in 75e6566.

### ROADMAP (agreed 2026-10-05; work strictly in this order, one milestone at a time)
| # | Milestone | Status |
|---|---|---|
| 0-3 | Skeleton, 4 adapters, rule extraction, rule matching | DONE |
| 4 | MCP server (tools, find-jobs prompt, postedSince) | DONE |
| 5 | Quality foundation: answer key (Opus judge, user-confirmed `eval/reference/saksham.csv`), baseline nDCG@10 0.89, experience window, bullet/typo extraction fixes, `ClaudeCliChatModel` | DONE |
| 6 | Opus extraction-gap filler (status below) | DONE 2026-10-05 (all 145 gap jobs, $3.48) |
| 7 | Workday adapter (then Eightfold, Oracle) with the generalization check (design below) | Workday + Eightfold DONE (86df413, 75e6566); Microsoft not crawled yet; Oracle later |
| 8 | Search with the Opus judge: first batch fast, background judging, `more_jobs`, export (design below) = the END-TO-END DEMO | WORKS LIVE, NOT COMMITTED (see NEXT); tiers added; demo via Claude pending |
| 8a | Saved profiles / profile IDs (user's idea 2026-10-06; plan in "### M8a plan") | PLAN READY, build 2026-10-07 |
| 8b | Self-learning skill dictionary (user's idea 2026-10-05, design below; suggested placement: after M8, user to confirm) | planned |
| 9 | Operations: scheduler (per-host virtual threads), change tracking + closed jobs, dedup, health alerts, SmartRecruiters incremental details | planned |
| 10 | Custom adapters by value: Amazon, IBM, Cisco, Google, Apple, then the rest (section 3) | planned |
| 11 | Cost and scale: Anthropic API instead of the CLI, cheaper/local judges (code computes the experience window), embeddings + hybrid retrieval, recall@K regression tests, answer keys for more profiles | later |
| 12 | Extras: digests, tracker, outreach drafts, README with measured numbers, web UI | later |
User priorities: Workday is eagerly wanted (lots more jobs); the gap filler must come first because new connectors bring
many unextractable postings. Use OPUS for every model call until M11 (single user; cost fine even at 8-10k jobs). Do not
loop on evaluation; build. Keep this roadmap updated when a milestone finishes.

### M6 status (2026-10-05): code written by Claude, 171 unit tests green, SQL checked on real data (temp tables)
- Scope (user's call, to save Opus usage): open TECH + SALES_ENGINEERING jobs (primary or secondary) and UNCLASSIFIED
  ones, with a gap: years NONE/LOW, family UNCLASSIFIED, or no main language. 145 jobs today (40 years, 29 family,
  137 languages). One call per job asks ALL fields; only gap fields are checked and applied; the whole answer is stored.
- Files: `db/migration/V9__create_job_gap_fills.sql` (table `job_gap_fills` + `job_requirements.{years,family,
  languages}_source`), `resources/classify/gap-filler-prompt.md` (prompt v1: every family with a one-line meaning, the
  specializations, the allowed language names), `requirements/` GapAnswer (model answer record), GapFillPrompt,
  GapFiller (call + checks: years 0-30, min<=max, evidence copied from the posting after normalizing and stating the
  minimum; family not UNCLASSIFIED; languages canonical LANGUAGE skills mentioned in the posting; nullable fields
  patched into the generated schema), GapFillRepository (gap query, save, apply only into gap fields, reapply),
  GapFillRunner (background, Semaphore, shuffled seed 42, stops after 3 initial failures). RequirementsService
  re-applies stored fills after every rules upsert (no new call). `get_job` shows `filledByModel`.
- Endpoints: `POST /admin/requirements/fill-gaps?model=opus&limit=15&parallelism=3`,
  `GET /admin/requirements/fill-gaps/status`. Review fills: `SELECT j.title, f.accepted, f.rejected FROM job_gap_fills f
  JOIN jobs j ON j.id = f.job_id ORDER BY f.filled_at DESC`.
- RESULT (2026-10-05): 145 jobs asked, 0 failures, 0 rejected, $3.48, ~5 s/job. UNCLASSIFIED 29 -> 0 (all fills checked,
  sensible: 12 Paytm "Team Leader - LRM" -> SALES, CTM -> RISK_COMPLIANCE, Workday integration leads -> SWE...); years
  3 filled with quotes (21 tech jobs truly state none); languages 0 filled, CORRECT: none of those postings names a
  language (Databricks/Freshworks write language-free postings). Opus agreed with the rules' years on every job where
  both answered. Log line shows only the gaps asked and their outcome.

### 8b design: self-learning skill dictionary (user's idea, discussed 2026-10-05; viable, ~2 days)
- Problem: skills.csv is hand-made; new tech terms (as RAG was a few years ago) are missed until someone edits it.
- Mine candidates during extraction (rules, free): tech-looking tokens/phrases in requirement sections (acronyms,
  CamelCase, names with digits/dots like "Node.js", "GPT-5", terms in skill lists after "experience with") that match
  no alias; table `skill_candidates(term, job_count, company_count, first_seen, last_seen, sample_contexts, status)`.
  Count DISTINCT companies (boilerplate and product names stay at 1). Also record the resume skills match_jobs reports as
  `unknownSkills` (source = profile).
- Monthly (or on demand) review run: top candidates (e.g. >= 3 companies) go to Opus with our categories, nearby
  existing skills and the sample contexts; Opus answers per term: reject (not a skill), alias of an existing skill,
  or new skill (canonical name, category, aliases, ambiguous flag, implications like "LangGraph -> AI Agents 0.5").
- Apply without editing resource files: learned rows in a DB table loaded by SkillExtractor on top of the CSV seed;
  checks: alias occurs in the contexts, no clash with existing aliases, ambiguous names marked ~; audit log + undo;
  then re-extract only jobs containing the new aliases. Auto-apply confident clear terms, queue ambiguous ones.
- Better after M7: more jobs and companies make the counts meaningful.

### M6 design: Opus extraction-gap filler (agreed with the user 2026-10-05)
- Goal: when the rules give no answer, Opus reads the posting and fills it. Gaps today: years NONE 61 + LOW 53, family
  UNCLASSIFIED 29, tech jobs without a main language 78 (~200 jobs, ~$6 of Opus, one-time; then only new/changed jobs).
- Order: rules always run first; Opus only fills fields the rules left empty or LOW. Never overwrite a HIGH/MEDIUM rule
  value. One Opus call per job returning JSON for all its gaps (years min/max, family + specialization, main
  languages) plus a short EVIDENCE quote per field.
- Guard: grounding check (the evidence quote must literally occur in the posting, else discard), enum/schema check
  (BeanOutputConverter record, as in the judge), sane ranges (years 0-30, min <= max).
- Storage: per-field source (`rules` / `claude:opus`) and evidence, so fills are explainable and re-runnable; a fill is
  redone only when the job's content hash changes or the filler prompt version bumps; a rules rebuild keeps fills.
- Run: background like the judge (Semaphore parallelism, resumable), endpoint e.g. `POST /admin/requirements/fill-gaps`,
  later automatically after each crawl. Reuse `ClaudeCliChatModel` (thinking off, Opus).
- Check: show the user ~15 fills with evidence (optional quick review), report coverage before/after.

### M7 status (2026-10-06): Workday adapter + 9 companies crawled (837 jobs); rules pass v8 READY, not yet applied
- WorkdayAdapter (user pasted) discovers the country filter from facets; V10 seeds 9 tenants; crawl done by the user.
- Generalization check: chip jobs labelled SWE (~160), PayPal intro "for more than 25 years" read as 25+ on 11 jobs,
  Samsung "8 years to 18 years" -> 18+, Salesforce "Technical Consulting" -> SUPPORT. Rules pass v8 (verified on all
  1,543 jobs, 188 tests): HARDWARE_ENGINEERING (TECH_ADJACENT, user's option a), CompanyBoilerplate (lines under >50% of
  a company's distinct titles skipped; generic, not per company), range fix, technical consulting rule; Opus gap fill
  families configurable (`jobagent.gap-fill.families`, default SWE, DATA_ML, INFRA_DEVOPS, UNCLASSIFIED; ~208 jobs
  ~$5); prompt v2 no longer re-asks answered jobs. CompanyBoilerplate, GapFillProperties, CompanyBoilerplateTest were
  filled by Claude at the user's request (one-off; the new-file rule still stands). 188 tests green. Next: user
  restarts, `POST /admin/requirements/rebuild?all=true`, coverage, then `POST /admin/requirements/fill-gaps`.
- User questioned the boilerplate fix as "hard-coded" and asked to test a LOCAL model on the year lines instead
  (2026-10-06): Ollama (already installed via brew, server started by Claude) + qwen2.5:3b and qwen3:4b (think off),
  input = lines matching the years regex + title, JSON schema answer, 152 jobs (124 with year lines). Results vs my
  reading of every disagreement: rules v8 ~119/124 right; qwen3:4b ~108/124 (0.75 s/job; ignores company-history
  lines well, but picks the smaller sub-requirement: "14-18 years CRM with a minimum of 10 on Salesforce" -> 10, gives
  max < min); qwen2.5:3b far worse (56/124 same as rules; puts N+ into maxYears). DECISION (user): rules first, Opus
  for uncertain cases, local models scrapped for now; both models deleted and the Ollama server stopped.

### M7b status (2026-10-06): Eightfold adapter in progress (Microsoft + Qualcomm first; M7 committed as 86df413)
- PCSX API: GET https://{host}/api/pcsx/search?domain={domain}&query=&location=India&start=N (10/page, data.count,
  server-side location), GET /api/pcsx/position_details?position_id=..&domain=..&hl=en (jobDescription, publicUrl,
  department, standardizedLocations "Hyderabad, TS, IN" -> ISO code, postedTs epoch s, workLocationOption,
  Microsoft efcustom* fields are LISTS). robots.txt allows /api/pcsx. User pasted EightfoldAdapter, V11 (microsoft,
  qualcomm), EightfoldAdapterTest. Then, at the user's request, ADAPTIVE PACE (Claude edited): each crawl starts at
  1 request/s (config delayMs = starting pace), every 429 or refused/timed-out connection adds 1 s per request (max
  15 s) for the rest of that crawl, cool-down 10 s x signals in a row, same request retried; gives up after 5 signals
  in a row (list page -> crawl fails, detail -> job kept without description). 196 tests green.
- MICROSOFT THROTTLES HARD: at 1 s and at 2 s per request it answered 429 every few requests during the 24 list
  pages, then refused connections (connect timeout, likely a temporary IP block). Stopped all Microsoft requests
  2026-10-06 01:22. Do not hammer it; next try only much slower (e.g. 5-10 s per request, once a day) and only with
  the user's ok. Qualcomm answered normally; full Qualcomm test run started (584 jobs).
- detailDepartments (Claude edited, 197 tests): optional regex in companies.config; only matching departments get a
  detail request, the rest are saved from the list (title, department, location, date; no description). The Microsoft
  list has NO years and NO languages (checked), so descriptions stay needed for the jobs that matter.
- TODO MICROSOFT (user, 2026-10-06; "handle tomorrow", NOT crawled yet): it put our IP in a 429 cool-down (still 429
  at 01:35, 12 min after the last request). Next session: (1) one test request to see that the cool-down is over;
  (2) set its detailDepartments (data, not code), e.g. a V12 migration
  `UPDATE companies SET config = config || '{"detailDepartments": "software|engineering|data|applied sciences|research|cloud|ai|security"}'
  WHERE slug = 'microsoft'` after looking at its real department names (~234 jobs; list pages first); (3) crawl.
- Qualcomm crawl started by the user 2026-10-06 ~01:45 (no department filter, 584 jobs).
- Morgan Stanley / UKG need the page's session cookie + CSRF token: later.

### M7 design: Workday (then Eightfold, Oracle)
- Recipes in section 3.2 (facet discovery for the India filter, never searchText "India"; detail endpoint per job).
  Tenants: Nvidia 244, Mastercard 207, Salesforce 111, Visa 81, Adobe 79, Intel 58, Samsung 31, Expedia 30, PayPal 10.
- Generalization check (section 8): crawl, run rule extraction BEFORE editing any rule, measure abstain rates
  (NONE/LOW/UNCLASSIFIED/unresolved locations) and accuracy on ~50 random new jobs; then the M6 filler covers gaps;
  then fix rules/data (expect a HARDWARE_ENGINEERING family for Nvidia/Intel/Samsung, bank level ladders later).
- Politeness: low rate per host, delays; Workday details may need incremental fetching (like SmartRecruiters).

### M8 design: search with the Opus judge (AGREED with the user 2026-10-05: "this design is perfect")
```
1,000 jobs --SQL hard filters (open, India, locations/remote, family primary|secondary, experience window overlap;
             the ONLY step that may drop a job)--> ~120 candidates --code: rule score for ALL, sort (ms)-->
sorted list (saved as the search, ~hours) --Opus judges in score order: FIRST 10 in parallel (~15 s with the CLI),
then the rest in the BACKGROUND--> verdicts saved forever per (profile hash, job, rubric version, job content hash)
```
- `match_jobs` returns the first APPLY batch (~8-9 of the top 10) plus a `searchId`; background judging continues.
- New tool `more_jobs(searchId, count)`: default 10, or the number asked; returns the next APPLY not yet shown; if fewer
  are judged yet, returns what is ready plus "N more being judged, ~X min". MAYBE only after all APPLY are exhausted
  (everything judged, no unseen APPLY left). NO never shown. Later APPLY have lower rule scores, so pages only append.
- Storage in Postgres (no Redis for now): `judgments` table (durable: verdicts cost money) and a search table (sorted
  candidate list + how far each list has been shown). The same search started twice reuses the running job.
- Export: all APPLY so far as company, title, apply link (the user wants hundreds of apply-worthy jobs later).
- REFINED 2026-10-06 (user): "keep N ready" buffer instead of judging everything. First call judges `first-batch` (10)
  in parallel; a background worker per search keeps judging in score order until `ready-target` (30) unseen APPLY
  are ready, then pauses; `more_jobs(n)` returns up to n ready ones at once and wakes the worker to refill to 30
  (if fewer are ready: return those + "N being judged, ~X s"). Stop rule: worker stops after `stop-after-nos` (20)
  NO verdicts in a row (lower scores rarely APPLY). All three in application.yaml (jobagent.search.*). Verdicts are
  persisted, so a restart just resumes on the next call. Decided: match_jobs gets an optional `wants` text (role
  types the candidate wants, filled by Claude from the conversation) for the judge's rubric.
- Later (M11): Anthropic API (no process start, high parallelism -> first batch ~5 s), prefetch, several jobs per call,
  stop rule (a full batch with no APPLY/MAYBE), spot checks below the cut, recall@K tests.

### Agreed rules for evaluation (from M5)
Rubric `eval/judge-rubric.md` v2 (role type wanted = asked for OR shown by the skills; experience window = rounded years
-2..+1, one year above = at most MAYBE, more = NO; stack has no language preference). Answer key
`eval/reference/saksham.csv` (Opus, user-confirmed: "Opus is right" on every disagreement). Haiku without thinking
fails the experience arithmetic (kappa 0.46); with thinking 0.85; fix later by computing the window in code.

**How we work (user preferences, keep following them):**
- NEW FILES (rule restated by the user 2026-10-05 after M6, "remember it"): Claude only creates the EMPTY file
  (touch) and gives the whole verified code with purpose, a short method table, new concepts and shortcomings; the
  user understands it first and pastes it. Never write a new file's code into the repo. EXISTING files: Claude may
  edit them itself and report per file what changed and why, SHORT and precise. (M6 files were written by Claude by
  mistake; the user accepted it once.)
- Keep answers SHORT, simple and to the point (user complaint 2026-10-05: long answers with lots of information are
  hard to follow). Explain with small concrete examples. Never use em-dashes.
- Verify before handing over or claiming done: compile with JDK 25
  (`/Users/saksham/Library/Java/JavaVirtualMachines/openjdk-25.0.2/Contents/Home`) against `~/.m2` jars, run against
  real data (read-only SELECTs), run the unit tests; before a rules change dump a baseline for all jobs and diff after.
- Testing the RUNNING app: give the user the curl commands; the user runs them and pastes results (Claude may read
  result FILES like `eval/runs/*` directly). Using the product through the job-agent MCP tools is fine for Claude.
- Ask before commits unless the user said "commit" (they have generally approved committing finished work). Never push.
  Ask before downloading anything (models, packages). Keep CLAUDE.md section 0 current: the user compacts often and
  wants zero context loss.

**Run / check:**
- App: IntelliJ run `JobagentApplication`. Its run configuration must have env vars `CLAUDE_CODE_OAUTH_TOKEN` (value
  from `claude setup-token`, also exported in ~/.zshrc; never in git or chat) and
  `JOBAGENT_LLM_CLAUDECLI_COMMAND=/Users/saksham/.local/bin/claude`. Or `./mvnw spring-boot:run` from a terminal.
- Unit tests (no DB): `./mvnw -q test -Dtest='*Test'` (217 green on 2026-10-06). `JobagentApplicationTests` would migrate the real DB.
- DB: `docker exec -it rag-postgres psql -U postgres -d jobagent` (Docker Desktop must run). Flyway V1-V15.
- Endpoints: `GET /admin/companies[/{slug}]`, `GET /admin/crawl/{slug}/preview?limit&full`, `POST /admin/crawl[/{slug}]`,
  `POST /admin/requirements/rebuild[?all=true]` (after any EXTRACTOR_VERSION bump), `GET /admin/requirements/coverage`,
  `POST /admin/match?limit&postedSince` (Profile JSON body), `POST /admin/eval/judge?profile&model&limit&parallelism`
  (1-4), `GET /admin/eval/judge/status`, `GET /admin/eval/report?run=<date>-<model>-<profile>`,
  `POST /admin/requirements/fill-gaps?model&limit&parallelism` (1-10), `GET /admin/requirements/fill-gaps/status`,
  `POST /admin/search?wants&postedSince&pageSize` (Profile body), `GET /admin/search/{id}/more?count`,
  `GET /admin/search/{id}/export?includeMaybe`.
- Using the product: from a clean folder (`~/job-search`; server added with `claude mcp add --transport http --scope user
  job-agent http://localhost:8080/mcp`), not from this repo (this CLAUDE.md would leak into the session).

**Code map:** `company` (registry) · `crawl` (+ `crawl.adapter`: Greenhouse/Lever/SmartRecruiters/Ashby/Workday/Eightfold,
JsonFields) · `search` (M8: SearchProperties, SearchRepository, SearchService, SearchController) ·
`geo` (Gazetteer, LocationParser) · `job` (NormalizedJob, JobRepository upsert, JobQueryRepository read side) ·
`requirements` (ExperienceExtractor, JobClassifier/JobFamily with secondary families, DescriptionSections incl.
`isBullet`, SkillExtractor, Requirements{Repository,Service,Controller}, EXTRACTOR_VERSION=7, M6 gap filler:
GapAnswer, GapFillPrompt, GapFiller, GapFillRepository, GapFillRunner) · `matching` (Profile with
decimal years + jobYearsFrom/To, ExperienceWindow, MatchingProperties `jobagent.matching.*`, SkillImplications,
MatchCandidateRepository, MatchScorer, MatchService, MatchController) · `mcp` (CompanyTools, JobTools, JobSearchPrompts) ·
`llm` (ClaudeCliChatModel, ClaudeCliProperties `jobagent.llm.claude-cli.*` incl. thinking=false, ClaudeCliException) ·
`eval` (Rubric, JudgeProfile, Judgment, JobJudge, JudgmentLine, JudgeRunner, EvalReport, EvalController,
EvalProperties `jobagent.eval.dir`) · `common` (CsvResource). Data: `resources/geo/*.csv`, `resources/classify/*.csv`
(skills, skill-implications, title-families, title-fallback, department-families, description-keywords,
description-secondary-keywords, specializations). Eval files: `eval/` (README explains each).

**Backlog (not on the roadmap yet; pull in when relevant):**
- SAVED PROFILES (user, 2026-10-06, first "keep as backlog", then proposed as resume IDs; see NEXT 2): verdicts are reused per profile_hash (SHA-256 of years,
  sorted canonical languages + skills, lower-cased wants). Claude re-extracting a resume can differ slightly (skill
  list, wants wording) -> new hash -> everything judged again. Plan: `save_profile` tool (profile stored under a name,
  match_jobs(profile: "saksham") uses the stored facts -> stable hash; also enables digests). Option: wants as an enum.
- Ranking: specialization boost (backend over frontend); skill category weights; Zscaler job 188 (APPLY) ranks #12 while
  its twin 187 is #2 (likely an extraction difference, investigate); "Jira Administrator" looks Java-primary.
- Accepted for now (user): MAYBE jobs one year above the window are filtered out; Jira Admin is INFRA_DEVOPS.
- get_job: when cutting long descriptions, drop INTRO sections first.
- Company boilerplate lines (same line in >50% of a company's postings) should be ignored by extractors.
- Faster skill matching (Aho-Corasick / pre-filter) before ~8,000 jobs (full rebuild 17.6 s for 706).
- Retry with backoff for 529 Overloaded when runs become unattended (now: re-run retries failed jobs).
- Raise JudgeRunner.MAX_PARALLELISM (4) if needed for big runs.

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

Milestone 4 DONE 2026-10-05: Spring AI 2.0.1 MCP server (streamable HTTP at /mcp), tools list_companies, match_jobs,
get_job, prompt find-jobs; skills dictionary grown from real job text plus candidate-side skill implications;
recall-first classification with secondary families; postedSince filter. Demo in a clean folder passed.
Milestone 5 DONE 2026-10-05: eval set (user's 100 labels), extraction fixes found while labeling (typed and numbered
bullets, "ears" typo), configurable experience window (decimal years rounded by the server, window -2..+1, overlap),
order-independent language score, ClaudeCliChatModel (Spring AI ChatModel over claude -p), Opus judge with a fixed
rubric, answer key for the user's profile (7 APPLY / 9 MAYBE / 271 NO, user-confirmed), baseline nDCG@10 0.89, Haiku
tested and parked. Then the roadmap was reordered: M6 Opus gap filler, M7 Workday, M8 search with the judge.
Milestone 6 DONE 2026-10-05 (1497473): Opus gap filler (years, family, main languages; one call per gap job; checks:
quote in the posting, family from our list, known languages), stored per content hash, re-applied after rebuilds.
Milestone 7 DONE 2026-10-06 (86df413): Workday adapter (country filter discovered from facets), 9 companies, rules v8
(HARDWARE_ENGINEERING, company boilerplate lines skipped, ranges), configurable Opus families; local models tested
(qwen2.5:3b, qwen3:4b) and rejected; models deleted.
Milestone 7b DONE 2026-10-06 (75e6566): Eightfold adapter (adaptive pace, detailDepartments), Qualcomm + 6 more
Workday companies, rules v9 (department beats generic engineer title, level ladders, locations), v10 (family_guessed
checked by Opus); 2,340 jobs, guessed/UNCLASSIFIED 0.
Milestone 8 WORKING 2026-10-06 (uncommitted): judged search (first 10 in parallel, background worker keeps 30 APPLY
ready, stop after 20 NO in a row), more_jobs / export_jobs, verdicts cached per profile hash, two tiers.

## 0c. Detailed record of the 2026-10-05 session (moved here from section 0; nothing deleted)


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

**Milestone 5 so far (2026-10-05, commit "Milestone 5 (part 1)"):**
- Extraction fixes found while labeling: typed bullets (`• ● * ➢ ✓`) and numbered items (`1)`, `2.`) are list
  items (`DescriptionSections.isBullet`; before, short typed bullets were taken for HEADINGS and dropped/flipped
  sections, and "• 3-6 years in backend" was ignored); the typo "4 to 6 ears" counts (only after a number, on an
  experience line). EXTRACTOR_VERSION=7 (user must rebuild). Policy: rules for repeating patterns, one-off oddities
  are for the local-model tier, not more rules.
- Experience window (user's design): resume years are DECIMAL (`Profile.yearsOfExperience` Double), the SERVER
  rounds (`ExperienceWindow.roundYears`, round-up-from 0.5: 1.5 -> 2, 1.4 -> 1), default window = years -2..+1,
  a job is eligible when its range OVERLAPS the window (2 yrs -> 0-3: "3-5" shown, "4-6" not); unknown years never
  exclude. Optional `jobYearsFrom/jobYearsTo` on match_jobs / Profile ONLY when the user explicitly asks.
  Config `jobagent.matching.{country, years-below, years-above, round-up-from}` (`MatchingProperties`,
  `@ConfigurationPropertiesScan`). MatchResponse reports `experienceWindow`. 1.6 yrs -> 23 SWE jobs, asks up to 4 -> 33.
- Language score is ORDER-INDEPENDENT and generic: all of the job's main languages are yours = full, some =
  partial (0.6), none = 0 (never prefer a language because the user knows it; the profile is just input).
- Eval: `eval/` (profile.json 1.6 yrs, pool.csv, labels.csv, README). User labeled 100 (6 would apply, 17 worth a
  look, 77 not for me); the pool was skewed to 5+ year roles (top 60 chosen without the years filter). User does NOT
  want more manual labeling. Baseline on these labels (SWE, v5 data): 4 of 6 "would apply" at ranks 1-4.

**Model judge DONE (2026-10-05, commit "Milestone 5 (part 2)"):** `llm/ClaudeCliChatModel` (Spring AI ChatModel over
`claude -p`: empty work dir so no CLAUDE.md leaks in, tools/MCP/skills off, system prompt via --system-prompt, user
text via stdin, schema via --json-schema with the `$schema` draft line REMOVED (the CLI's validator rejects draft
2020-12), reads structured_output/result, usage -> DefaultUsage + costUsd; 3 virtual threads per call for the pipes),
`eval/*` (Rubric, JudgeProfile, Judgment record = schema via BeanOutputConverter, JobJudge -> Result with tokens/cost,
JudgeRunner: background run, Semaphore parallelism 1-4, one JSONL line per job, resumable, stops after 3 failures
with no success; EvalReport: agreement, Cohen's kappa, table, disagreements, usage; EvalController
`POST /admin/eval/judge?profile&model&limit&parallelism`, `GET /admin/eval/judge/status`, `GET /admin/eval/report?run`).
App env (IntelliJ run config, never in git): `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`, also in ~/.zshrc)
and `JOBAGENT_LLM_CLAUDECLI_COMMAND=/Users/saksham/.local/bin/claude`. Opus judged all 287 tech jobs for the user:
7 APPLY / 9 MAYBE / 271 NO, ~$0.026 and ~4.8 s per job; user confirmed every APPLY/MAYBE and all 20 disagreements with
their own labels ("Opus is right") -> frozen `eval/reference/saksham.csv`. A 529 Overloaded can happen: the CLI retries
~100 s, our run records the failure, re-running retries only failed jobs. 160 unit tests.
Baseline (rules ranking vs reference): P@10 APPLY 0.6 of max 0.7, APPLY+MAYBE 0.9, nDCG@10 0.89, 7/7 APPLY in top 25.

**Target architecture at scale (agreed 2026-10-05, for when Workday etc. bring thousands of jobs):** per job offline:
extracted facts + an embedding of title + requirements section (no boilerplate) in pgvector. Per resume: (1) hard SQL
filters = the ONLY way a job may disappear; (2) cheap score for EVERY eligible job (rules + vector similarity, hybrid);
(3) a cheap model classifies APPLY/MAYBE/NO in score order up to a configurable budget, answers cached per
(profile, job, rubric); (4) APPLY then MAYBE, sorted by score, paginated, exportable as company + title + link
(the user wants hundreds of apply-worthy jobs). Robust candidate picking, to do THEN (user: not needed at 287 jobs):
recall@K check against answer keys as an automated regression test; soft filters (stretch zone one year above the
window with a penalty; neighbouring families with a penalty); union of several retrieval methods (rules, vectors,
title keywords); adaptive stop (judge until N consecutive NO) instead of a fixed top-K; spot-check a random sample
below the cut to measure the real miss rate; answer keys for more profiles and companies. Vectors are blind to
seniority and to required-vs-plus, so they never replace filters/rules/model; adopt them only if the answer keys
show a recall gain. The user accepted today's misses (4 MAYBE one year above the window, Jira Admin in INFRA).

**Cheap-judge test DONE, then PARKED (2026-10-05, user's call):** Haiku with thinking ON (Claude Code default)
wrote thousands of hidden tokens (20-120 s per job), so `ClaudeCliProperties.thinking` (default false) passes
`--settings {"alwaysThinkingEnabled":false}`. Haiku no-thinking: 287 jobs, ~9 s/job, $2.0, agreement 95%, kappa 0.46,
only 1/7 APPLY exact and 2 APPLY -> NO, because it botches the experience arithmetic (forgets 1.6 -> 2, the window);
with thinking (180 jobs) kappa 0.85. Fix for later: compute window/overlap in code and put it in the prompt
(models judge, code does math). Runs kept in eval/runs/*haiku*. Production latency plan (later): rules list shown
instantly, top 10 judged first and updated live, Anthropic API instead of the CLI (no process start, high
parallelism), verdict cache per (profile, job, rubric), optionally several jobs per call.

**DECISION (2026-10-05, user):** use OPUS (ClaudeCliChatModel) for everything for now: the job judge AND filling
extraction gaps the rules miss (years NONE/LOW, UNCLASSIFIED family, missing main language, skills). No local
models or cheap-model testing until a working END-TO-END demo exists; single user, so scale/cost is fine even at
8-10k jobs. Do not loop on evaluation; build the product.

**EXACT NEXT STEP: the end-to-end demo.** (1) Judge inside the search: match_jobs -> rules shortlist (filters +
score, configurable size) -> Opus verdicts, cached per (profile, job, rubric) in a DB table -> APPLY then MAYBE,
each by rule score; plus a plain list export (company, title, link), paginated. (2) Opus extraction fallback in
the requirements pipeline for the gaps above, storing the source (rules vs claude). (3) Then Milestone 6 (Workday,
Eightfold, Oracle) for real volume. Agree the design of (1) with the user first.
NOTE: Claude Code loads this file into EVERY session in this folder, including sessions where the user USES
the job-agent tools. Keep it current (a stale "not built yet" here made a demo session work around a feature
that existed), and remember a product session may take the user's profile from §1 instead of the resume.


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
- Learning mode history: 2026-10-04 the user pasted ALL code; from M4 Claude edits EXISTING files; from M5 Claude
  makes ALL changes (new files too) unless the user explicitly asks to paste and learn (they did for the judge's
  Spring AI classes). Current rule: section 0 "How we work". Claude may always edit docs and data files.
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

## 7. Milestones

The original plan of 2026-10-04 was reordered on 2026-10-05; the CURRENT roadmap is the table in section 0.
Original list for reference:

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
