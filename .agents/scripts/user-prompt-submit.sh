#!/usr/bin/env bash
# Hook UserPromptSubmit — Driven Agent Development
#
# 1. Queries Chroma 'skills' collection to find the best skill for the task
# 2. Queries Chroma 'changes' collection for prior project context
# 3. Injects both as additionalContext — the agent starts informed
#
# Input:  JSON on stdin  { "session_id": "…", "prompt": "…", … }
# Output: JSON on stdout { "hookSpecificOutput": { ... "additionalContext": "…" } }

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CHROMA_PATH="$ROOT/.agents/memory/chroma"
QUERY_ALL="$SCRIPT_DIR/query-all.py"

INPUT=$(cat)
PROMPT=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('prompt',''))" 2>/dev/null)

if [ -z "$PROMPT" ]; then
  echo '{}'
  exit 0
fi

HAS_CHROMA=false
python3 -c "import chromadb" &>/dev/null && HAS_CHROMA=true

# ── 1+2. Single Python process — loads model once, queries skills + changes ──
CTX=""
if [ "$HAS_CHROMA" = true ] && [ -f "$QUERY_ALL" ]; then
  CTX=$(python3 "$QUERY_ALL" "$PROMPT" "$CHROMA_PATH" 2>/dev/null || true)
fi

if [ -z "$CTX" ]; then
  echo '{}'
  exit 0
fi

# ── 3. Return JSON ───────────────────────────────────────────────────────
python3 -c "
import json, sys
ctx = sys.stdin.read()
print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'UserPromptSubmit',
        'additionalContext': ctx
    }
}))
" <<< "$CTX"
