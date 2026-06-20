#!/usr/bin/env python3
"""
Crosses token-usage.jsonl with query-log.jsonl to report system health.
Usage: python3 .agents/scripts/token-report.py
"""
import json
from pathlib import Path
from datetime import datetime


def load(path):
    p = Path(path)
    if not p.exists():
        return []
    return [json.loads(line) for line in p.read_text().splitlines() if line.strip()]


def fmt_ts(raw):
    try:
        dt = datetime.fromisoformat(raw)
        return dt.strftime("%Y-%m-%d %H:%M")
    except Exception:
        return raw[:16]


def cache_hit_ratio(r):
    readable = r.get("cache_read", 0)
    total = r["input"] + readable
    return readable / total if total > 0 else 0


tokens = load(".agents/memory/token-usage.jsonl")
queries = load(".agents/memory/query-log.jsonl")

# --- Token usage ---
print("\n=== TOKEN USAGE (last 20 turns) ===")
print(f"{'Timestamp':<18} {'Agent':<8} {'Session':>10} {'Input':>8} {'Output':>8} {'CacheCreate':>12} {'CacheRead':>10} {'Total':>8} {'HitRate':>8} {'Quality':>8}")
print("-" * 112)
for r in tokens[-20:]:
    total = r["input"] + r["output"]
    sid = r.get("session_id", "")[-8:]
    agent = r.get("agent", "?")
    ratio = cache_hit_ratio(r)
    print(
        f"{fmt_ts(r['ts']):<18} {agent:<8} {sid:>10} {r['input']:>8} {r['output']:>8} "
        f"{r.get('cache_creation', 0):>12} {r.get('cache_read', 0):>10} {total:>8} {ratio:>7.0%} {r.get('quality', '-'):>8}"
    )

if tokens:
    # Per-agent summary
    by_agent = {}
    for r in tokens:
        a = r.get("agent", "unknown")
        s = by_agent.setdefault(a, {"turns": 0, "input": 0, "output": 0, "cache_creation": 0, "cache_read": 0})
        s["turns"] += 1
        s["input"] += r["input"]
        s["output"] += r["output"]
        s["cache_creation"] += r.get("cache_creation", 0)
        s["cache_read"] += r.get("cache_read", 0)

    print(f"\n  --- Summary by agent ---")
    for agent, s in by_agent.items():
        readable = s["input"] + s["cache_read"]
        ratio = s["cache_read"] / readable if readable > 0 else 0
        print(f"  {agent}: {s['turns']} turns | input: {s['input']:,} | output: {s['output']:,} | cache_hit: {ratio:.0%}")

# --- Chroma injection quality ---
print("\n=== CHROMA INJECTION QUALITY (last 20 queries) ===")
print(f"{'Prompt (inicio)':<40} {'Hits':>5} {'Injected':>9} {'Dropped':>8} {'Avg Score':>10}")
print("-" * 75)
for q in queries[-20:]:
    avg_score = round(sum(q["scores"]) / len(q["scores"]), 3) if q["scores"] else 0
    print(
        f"{q['prompt'][:38]:<40} "
        f"{q['total_hits']:>5} {q['injected']:>9} {q['dropped']:>8} {avg_score:>10}"
    )

low_quality = [
    q for q in queries
    if q["injected"] > 0 and q["scores"] and min(q["scores"][:q["injected"]]) < 0.72
]
if low_quality:
    print(f"\n  WARNING: {len(low_quality)} queries injected context with score < 0.72 — consider raising threshold")

zero_inject = [q for q in queries if q["injected"] == 0]
print(f"\n  Queries with no injection (correct, no relevant context): {len(zero_inject)}")
print(f"  Queries with relevant injection: {len(queries) - len(zero_inject)}")
