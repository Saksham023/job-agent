# Do vector embeddings make the job search better? (experiment of 2026-10-06)

## The short answer

**No, not for ranking.** On three fully labeled test sets, today's rule-based search order found the good jobs
just as fast as the best mix of rules and embeddings. The best mix needed exactly the same number of Opus checks
(42 across the three sets) to find 10 jobs worth applying to in each set. Vectors on their own were clearly
**worse** than today's order (46 and 49 checks). So embeddings do not go into the search ranking. What makes
today's order good is mostly the "tiers" we built earlier (likely-NO jobs moved to the end), not the skill score.

Total cost of the experiment: 454 Opus judgments, **$12.77**, about 1 hour including embedding.

## What was tested, and why

The AI search (`match_jobs`) works like this: SQL filters pick the candidate jobs (for your resume: 855), the
rules give each one a score, the search puts likely-NO jobs into a second tier at the end, and Opus then judges
the jobs **in that order** until enough APPLY jobs are found. So the order decides how many (paid) Opus checks it
takes to find good jobs. The rules only match skill names; the hope was that embeddings, which compare
**meaning** ("high-throughput event pipelines" is close to "Kafka, event-driven systems"), would put the good
jobs higher.

Compared, for each test set:

| Name in the tables | What it is |
|---|---|
| Today's search | rule score + tiers: exactly what the search does now |
| Rule score only | the rule score without the tiers |
| MiniLM / bge only | similarity between the profile and the job, from one embedding model alone |
| Mix X% rules | X% rule score + (100 - X)% similarity (both scaled 0 to 1); tried 30%, 50%, 70% |
| ... + tiers | the same order, but with today's tiers applied on top (the fair comparison) |

### The two embedding models (both run locally on the Mac, free)

| Model | Size | Reads up to | Time to embed all 5,300 jobs |
|---|---|---|---|
| all-MiniLM-L6-v2 | 90 MB | 256 tokens | 92 s (17 ms per job) |
| bge-small-en-v1.5 | 133 MB | 512 tokens | 346 s (65 ms per job) |

A job's vector is made from its title plus the posting **without** its intro sections (company blurb, perks),
using the app's own section parser. The profile's vector is made from "Looking for: <wants>. Experience: <years>
years. Skills: <skills>".

### The three test sets

| Test set | Who | Candidates after the filters |
|---|---|---|
| 1 | Your resume profile (`p-p1nqs0fb`: Java + Python, 1.6 years, backend and AI engineering, 0-4 years asked) | 855 |
| 2 | Your profile as used in Milestone 5 (Java, 1.6 years, all engineering families) | 785 |
| 3 | A made-up ML engineer (Python, 4 years, ML/NLP/LLM work) so the result is not tuned to one person | 1,136 |

### How the jobs were labeled (and why it is fair)

Opus is the source of truth: for each test set it judged jobs as APPLY / MAYBE / NO with the same rubric the search
uses. Judging all ~2,800 candidates would have cost about $60, so the experiment used **pooling**, the standard way
search engines are evaluated: Opus labeled **every job that any of the 18 orders puts in its top 50**, plus a
**random sample of 40** of the remaining jobs. Every order is then scored on jobs that all have a label, so no
order is favored (the earlier verdicts alone would have favored the rules, because they only covered jobs the
rules had ranked high).

## Results

### Opus checks needed to find 10 jobs worth applying to (lower is better)

This is the number that costs money: walk down the order, count how many jobs Opus must judge until 10 APPLY
verdicts are found.

| Order | Set 1 (your resume) | Set 2 (Milestone 5 profile) | Set 3 (ML engineer) | Total |
|---|---|---|---|---|
| **Today's search** | **16** | **12** | **14** | **42** |
| Rule score only (no tiers) | 19 | 17 | 14 | 50 |
| MiniLM only | 17 | 14 | 18 | 49 |
| bge only | 18 | 15 | 13 | 46 |
| Mix 70% rules (MiniLM) + tiers | 17 | 12 | 13 | 42 |
| Mix 50% rules (bge) + tiers | 16 | 12 | 14 | 42 |
| Mix 70% rules (bge) + tiers | 17 | 12 | 13 | 42 |
| bge only + tiers | 16 | 13 | 13 | 42 |
| Mix 30% rules (bge), no tiers | 17 | 12 | 14 | 43 |

All other orders were between 43 and 50. Full numbers: `results.json`.

### How many of the first 20 jobs are worth applying to

| Order | Set 1 | Set 2 | Set 3 | Total (out of 60) |
|---|---|---|---|---|
| Today's search | 13 | 15 | 14 | 42 |
| Mix 70% rules (bge) + tiers | 12 | 17 | 14 | 43 |
| Mix 50% rules (MiniLM) + tiers | 12 | 17 | 12 | 41 |
| bge only | 10 | 12 | 14 | 36 |
| MiniLM only | 13 | 13 | 11 | 37 |
| Rule score only (no tiers) | 11 | 11 | 14 | 36 |

The best mix is one job better out of 60, which is within noise: a different random choice of test jobs could
easily flip it.

## What we learned

1. **Today's search order is already as good as it gets with these tools.** No mix of rules and embeddings found
   good jobs with fewer Opus checks. Differences of 1 to 2 jobs in these tables are noise.
2. **The tiers do most of the work.** Without the tiers the rule score needs 50 checks, with them 42. The tiers
   catch things a similarity score cannot see: a "Principal" title with no years stated, embedded work for
   someone without C/C++, a job whose main language the candidate does not have.
3. **Embeddings alone beat the plain rule score.** bge alone (46) and MiniLM alone (49) are better than the rule
   score without tiers (50). So vectors do capture what a job is about, but they add nothing once rules and tiers
   are combined: they mostly see the same things.
4. **bge is a little better than MiniLM, and about 4 times slower.** bge did better alone (46 vs 49) and in the
   mixes. If embeddings are ever used, bge-small is the one to take.
5. **Vectors cannot judge seniority.** In bge's top 20, Opus rejected 4 jobs (over the three sets) because they
   ask for too much experience; today's top 20 had none. The hard experience filter already removes the worst
   cases, so the effect is small, but it confirms the earlier decision: vectors may only reorder, never filter.
6. **There are many more good jobs than the first page shows.** For your resume profile, 8 of the 39 randomly
   sampled jobs from the part no order reached were APPLY, which suggests roughly 140 more APPLY jobs further down
   the list (many Amazon SDE roles). For the ML engineer, 0 of 40 were APPLY. So for a backend profile the limit is
   not the ranking but how far the search judges (it stops at 15 ready APPLY or 20 NOs in a row).

## Decision

- **Do not add embeddings to the search ranking.** Same results, plus an extra model (133 MB), a vector table and
  an embedding step after every crawl, for no measurable gain.
- **Keep rules + tiers.** If ranking needs to improve later, improve the tiers and the rules (they carry the
  quality) and re-run this experiment: the code and labels are kept here.
- **Still possible, as a feature rather than a ranking fix:** "Similar jobs" in the web UI's job drawer (bge
  vectors, nearest neighbours of the open job). That is a browsing aid that needs no proof of better ranking. Not
  built.

## Limits of this experiment

- Three profiles, two of them yours. The labels come from Opus with the search's rubric, not from people (Opus was
  checked against your own labels in Milestone 5 and you agreed with it on every disagreement).
- Only the top 50 of each order is fully labeled. That is the part the search actually uses; beyond it the random
  sample gives an estimate only.
- The profile text and the job text were built one way (title + requirements, wants + skills). A different text
  or a larger model might do better, but the gap to beat is zero, so a large improvement would be needed to matter.

## Code

The experiment code (embedding program, scoring script, rankings and pool files) was removed after the decision; it
is in git history (commit f7939b6, folder eval/embeddings/). The downloaded models were deleted too. The Opus
verdicts stay in the `judgments` table. Test searches: set 1 `35c89870-4473-4d78-bb80-d13938edef6c`, set 2
`0e60e802-dc87-4f67-8c2c-1c1dc3180766`, set 3 `2f27677e-913e-425a-8ca8-eaf53cb07cbf`.
