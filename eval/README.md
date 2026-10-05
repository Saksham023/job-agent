# Ranking evaluation (Milestone 5)

A fixed, labeled set so every ranking change is measured, not eyeballed.

| File | What it is |
|---|---|
| `profile.json` | The frozen candidate profile (from the user's resume; no name or contact). Every ranking is scored for this input. |
| `pool.csv` | The 100 jobs to label and why each was chosen: `top` = in the top 60 of the ranking (extractor v5, all tech + sales engineering families, no years filter), `random` = seeded random sample of the other 227 such jobs, so good jobs the ranking misses can be found. Rank and score at selection time are kept for analysis and never shown while labeling. |
| `labels.csv` | The user's judgments: `job_id,label,note,company,title`. 2 = would apply, 1 = worth a look, 0 = not for me. |
| `label-jobs.html` | The labeling page (generated, not committed: it embeds the job descriptions). Open it in a browser, label with keys 2/1/0, then "Export labels.csv" and move the file here. |

Rules: judge as the candidate, from the posting (stack, kind of work, experience asked); location does not
matter (open to anywhere in India) and posting age is ignored. Labels belong to job ids; a job that closes later
keeps its label. Experience: the default search window is the candidate's rounded years minus 2 to plus 1
(1.6 -> 2 -> jobs overlapping 0-3 years), so jobs asking more are "not for me" by default; the user revised the
two Paytm 4-6 year roles (395, 396) from 2 to 1 for that reason.

Outcome of the first labeling round (2026-10-05): 6 "would apply", 17 "worth a look", 77 "not for me". The pool
was skewed: the "top 60" were chosen WITHOUT the experience filter, so most were 5+ year roles. Next: a model
judge (fixed rubric via `claude -p`) labels every tech job for several profiles; these 100 human labels are used
to validate that judge, not as the whole reference.

Planned metrics: precision@10 (label 2 = relevant, and 1+ = relevant), nDCG@10 with gains 2/1/0, recall of
label-2 jobs within the top 25, all for the same profile. Because the pool mixes top-ranked and random jobs,
a new ranking that surfaces unlabeled jobs reports how many of its top 10 are unlabeled (label them, then re-score).

## Model judge and reference set (2026-10-05)

| File | What it is |
|---|---|
| `judge-rubric.md` | The judge's fixed instructions (rubric v2): role type, experience window (years -2..+1, one year above = MAYBE, more = NO), stack (no language preference). The text after `---` is the system prompt. |
| `judge-profiles.json` | Candidates as the judge sees them (plain resume facts). Only `saksham` so far; add others to avoid tuning to one person. |
| `runs/<date>-<model>-<profile>/judgments.jsonl` | One line per judged job: verdict, three sub-fits, reason, time, tokens, cost. Written by `POST /admin/eval/judge`, resumable. |
| `reference/saksham.csv` | The frozen reference: Opus (rubric v2) on all 287 open tech + sales-engineering jobs, confirmed by the user (all APPLY/MAYBE and all 20 disagreements with labels.csv reviewed: Opus right). 7 APPLY, 9 MAYBE, 271 NO. |

Judge vs the user's 100 blind labels: 80% agreement, kappa 0.29; 16 of the 20 differences were 5+ year roles the user
had marked "worth a look" before deciding that 5+ years is a no, and the user confirmed Opus on all 20. Opus run:
~4.8 s and ~$0.026 (API-equivalent) per job, 287 jobs in ~7.5 min at parallelism 3, $7.2 total.

Baseline of the rule-based ranking against `reference/saksham.csv` (profile.json, families SOFTWARE_ENGINEERING, data
extractor v7): P@10 APPLY 0.6 (0.7 is the maximum: only 7 APPLY exist), P@10 APPLY+MAYBE 0.9, nDCG@10 0.89, all 7
APPLY in the top 25 (one at #12), 11 of 16 APPLY+MAYBE in the top 25; the 5 missing MAYBE jobs are filtered out by
the experience window (they ask one year above it) or by family (Jira Administrator is INFRA_DEVOPS).
