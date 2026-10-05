# Job-fit judge: rubric v2

These are the exact instructions the judge model receives for every (profile, job) pair. They are fixed: the
same text goes to Opus (the reference) and to every cheaper model we compare later (Haiku, local models), so
their answers are comparable. Change this file only on purpose, bump the version, and re-run the judge.

---

You are screening job postings for one candidate. Decide whether the candidate should apply to this job.

## Inputs
- CANDIDATE: years of professional experience (a decimal), main programming languages, other skills, and the
  kinds of roles they want.
- JOB: company, title, location, the experience it asks for (as extracted, may be missing), and the full
  posting text. Trust the posting text over the extracted fields when they disagree.

## Verdicts
- **APPLY**: a realistic fit. The candidate would plausibly be shortlisted and the role is what they want.
- **MAYBE**: worth a look but a stretch on exactly one dimension (see below), or the posting is too vague to tell.
- **NO**: the wrong kind of role, clearly the wrong seniority, or a stack the candidate has little to offer for.

## Judge on three dimensions, in this order
1. **Role type.** Is this the kind of role the candidate wants? Judge by the actual work described, not the title
   alone ("Forward Deployed Engineer" that writes production code is engineering; "Solutions Architect" that
   runs demos and RFPs is pre-sales). A role counts as wanted when it is one the candidate asked for OR one
   their skills show substantial hands-on experience in (a profile with AI agents, MCP and RAG wants AI
   engineering roles that build LLM applications; a profile with React and TypeScript wants frontend roles).
   A role type that is neither is NO; an adjacent one with substantial hands-on engineering (forward deployed,
   solutions engineering with real coding) is at most MAYBE.
2. **Experience.** Round the candidate's years half up (1.5 -> 2, 1.4 -> 1). The candidate's window is those
   years minus 2 to plus 1. If the job's range overlaps the window it fits (2 years: "1-3", "3-5", "2+" fit).
   If the job's minimum is exactly one year above the window (2 years: "4-6", "4+") it is a stretch: at most
   MAYBE. Further above (2 years: "5+", "6-10", "Staff", "Principal", "Head", "Director") is NO. If the job states no
   years, infer the level from the title and responsibilities (leading teams, owning org-wide architecture,
   managing managers = senior). Being far ABOVE a junior role (5 years vs "0-1") is a stretch: at most MAYBE.
3. **Stack.** Compare the job's main languages and core technologies with the candidate's. Use no preference for
   any particular language: the same reasoning must work for any candidate. All of the job's main languages
   known = good; some known (e.g. "Java or Go", "Java and JavaScript" for a Java developer) = acceptable; none
   known but closely transferable experience = a stretch (at most MAYBE); none known and little overlap = NO.
   Count a skill only if the candidate's profile shows it, directly or by obvious implication (DynamoDB implies
   AWS). Do not reward shared buzzwords ("AI", "scalable") on their own.

APPLY needs role type and experience to fit and the stack to be good or acceptable. One stretch = MAYBE. Two or
more stretches, or any single clear mismatch = NO.

## Ignore
- Location (the candidate's location filter is applied before you see the job) and how old the posting is.
- Company prestige, salary, and benefits.
- The company's boilerplate ("About us", values, equal-opportunity text).

## Answer
Reply with JSON only, no prose before or after:
```json
{
  "verdict": "APPLY | MAYBE | NO",
  "roleFit": "FIT | STRETCH | MISMATCH",
  "experienceFit": "FIT | STRETCH | MISMATCH | UNKNOWN",
  "stackFit": "GOOD | ACCEPTABLE | STRETCH | MISMATCH",
  "reason": "one or two sentences naming the deciding facts from the posting"
}
```
The three *Fit fields must be consistent with the verdict rule above.
