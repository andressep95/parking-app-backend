#!/usr/bin/env python3
# Stop hook (Claude Code only) — reads token usage from transcript, appends to token-usage.jsonl.
import json, sys
from pathlib import Path
from datetime import datetime

LOG_PATH = Path(".agents/memory/token-usage.jsonl")


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        print("{}")
        return

    transcript_path = payload.get("transcript_path", "")
    if not transcript_path or not Path(transcript_path).exists():
        print("{}")
        return

    last_usage = None
    for line in reversed(Path(transcript_path).read_text().splitlines()):
        try:
            entry = json.loads(line)
            if entry.get("type") == "assistant":
                usage = entry.get("message", {}).get("usage")
                if usage:
                    last_usage = usage
                    break
        except Exception:
            continue

    if not last_usage:
        print("{}")
        return

    inp = last_usage.get("input_tokens", 0)
    out = last_usage.get("output_tokens", 0)
    cache_create = last_usage.get("cache_creation_input_tokens", 0)
    cache_read = last_usage.get("cache_read_input_tokens", 0)
    readable = inp + cache_read
    hit_ratio = cache_read / readable if readable > 0 else 0

    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    record = {
        "ts": datetime.now().astimezone().isoformat(),
        "agent": "claude",
        "session_id": payload.get("session_id", "unknown"),
        "input": inp,
        "output": out,
        "cache_creation": cache_create,
        "cache_read": cache_read,
        "quality": "alta" if hit_ratio >= 0.80 else "media" if hit_ratio >= 0.40 else "baja",
    }
    with open(LOG_PATH, "a") as f:
        f.write(json.dumps(record) + "\n")

    print("{}")


if __name__ == "__main__":
    main()
