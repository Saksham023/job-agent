"""Rankings, the jobs to label (pooling), and the scores. Usage:
   python3 score.py pool  <searchId>...   -> pool-<searchId>.txt: the job ids Opus still has to judge
   python3 score.py score <searchId>...   -> report data (prints a table, writes results.json)
"""
import json, random, subprocess, sys

TOP = 50          # every ranking's top 50 is labeled
SAMPLE = 40       # plus a random sample of the rest, to see what hides below
WEIGHTS = [0.3, 0.5, 0.7]
LAB = sys.path[0]

def psql(sql):
    out = subprocess.run(["docker", "exec", "rag-postgres", "psql", "-U", "postgres", "-d", "jobagent", "-tAF", "\t", "-c", sql],
                         capture_output=True, text=True, check=True).stdout
    return [line.split("\t") for line in out.strip().splitlines() if line]

def norm(values):
    lo, hi = min(values), max(values)
    return [0.0 if hi == lo else (v - lo) / (hi - lo) for v in values]

def rankings(search_id):
    d = json.load(open(f"{LAB}/rankings-{search_id}.json"))
    ids, scores = d["ids"], d["ruleScores"]
    n = len(ids)
    pos = list(range(n))
    r = {"rules (today's search order)": list(ids),
         "rules score only": [ids[i] for i in sorted(pos, key=lambda i: (-scores[i], i))]}
    ns = norm(scores)
    for model, sims in d["cosine"].items():
        r[f"{model} only"] = [ids[i] for i in sorted(pos, key=lambda i: -sims[i])]
        nc = norm(sims)
        for w in WEIGHTS:
            mix = [w * ns[i] + (1 - w) * nc[i] for i in pos]
            r[f"hybrid {model} {int(w*100)}% rules"] = [ids[i] for i in sorted(pos, key=lambda i: -mix[i])]
    # the same orders with today's tiers: likely-NO jobs (positions from lowPriorityFrom on) stay at the end
    low_from = d.get("lowPriorityFrom")
    if low_from:
        low = set(ids[low_from - 1:])
        for name in [k for k in r if k != "rules (today's search order)" and k != "rules score only"]:
            order = r[name]
            r[name + " + tiers"] = [j for j in order if j not in low] + [j for j in order if j in low]
    return d, r

def labels(search_id):
    rows = psql(f"""SELECT g.job_id, g.verdict FROM searches s JOIN judgments g ON g.profile_hash = s.profile_hash
                    AND g.rubric_version = s.rubric_version JOIN jobs j ON j.id = g.job_id AND j.content_hash = g.content_hash
                    WHERE s.id = '{search_id}'""")
    return {int(job): verdict for job, verdict in rows}

def pool(search_id):
    d, r = rankings(search_id)
    chosen = set()
    for order in r.values():
        chosen.update(order[:TOP])
    rest = sorted(set(d["ids"]) - chosen)
    try:                                   # keep the first random sample (already labeled); drop jobs now in a top 50
        sample = [j for j in json.load(open(f"{LAB}/pool-{search_id}.json"))["sample"] if j not in chosen]
    except FileNotFoundError:
        sample = random.Random(7).sample(rest, min(SAMPLE, len(rest)))
    json.dump({"top": sorted(chosen), "sample": sample}, open(f"{LAB}/pool-{search_id}.json", "w"))
    known = labels(search_id)
    todo = [j for j in sorted(chosen) + sample if j not in known]
    open(f"{LAB}/todo-{search_id}.txt", "w").write("\n".join(map(str, todo)))
    print(f"{search_id}: {len(d['ids'])} candidates, pool {len(chosen)} top + {len(sample)} sample, "
          f"already judged {len(chosen) + len(sample) - len(todo)}, to judge {len(todo)}")

def score(search_id):
    d, r = rankings(search_id)
    p = json.load(open(f"{LAB}/pool-{search_id}.json"))
    lab = labels(search_id)
    missing = [j for j in p["top"] + p["sample"] if j not in lab]
    result = {"candidates": len(d["ids"]), "missingLabels": len(missing), "rankings": {}}
    sample = p["sample"]
    result["sample"] = {"size": len(sample), "apply": sum(lab.get(j) == "APPLY" for j in sample),
                        "maybe": sum(lab.get(j) == "MAYBE" for j in sample),
                        "restSize": len(d["ids"]) - len(p["top"])}
    result["poolApply"] = sum(lab.get(j) == "APPLY" for j in p["top"])
    result["poolMaybe"] = sum(lab.get(j) == "MAYBE" for j in p["top"])
    for name, order in r.items():
        v = [lab.get(j) for j in order[:TOP]]
        applies = [i + 1 for i, x in enumerate(v) if x == "APPLY"]
        result["rankings"][name] = {
            "apply@10": sum(x == "APPLY" for x in v[:10]),
            "apply@20": sum(x == "APPLY" for x in v[:20]),
            "apply@50": sum(x == "APPLY" for x in v[:50]),
            "good@20": sum(x in ("APPLY", "MAYBE") for x in v[:20]),
            "firstApply": applies[0] if applies else None,
            "callsFor10Apply": applies[9] if len(applies) >= 10 else None,
            "callsFor5Apply": applies[4] if len(applies) >= 5 else None,
        }
    return result

if __name__ == "__main__":
    mode, ids = sys.argv[1], sys.argv[2:]
    if mode == "pool":
        for s in ids: pool(s)
    else:
        all_results = {s: score(s) for s in ids}
        json.dump(all_results, open(f"{LAB}/results.json", "w"), indent=2)
        for s, res in all_results.items():
            print(f"\n== {s}: {res['candidates']} candidates, missing labels {res['missingLabels']}, "
                  f"APPLY in pool {res['poolApply']}, sample {res['sample']}")
            for name, m in res["rankings"].items():
                print(f"  {name:32s} {m}")
