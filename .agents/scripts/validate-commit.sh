#!/usr/bin/env bash
# Hook PreToolUse — Driven Agent Development
#
# Intercepts `git commit` commands and blocks them if the commit body
# is missing what:, why:, or breaking: fields. These fields feed the
# RAG memory system — without them, future context is lost.
#
# Input:  JSON on stdin  { "tool_name": "Bash", "tool_input": { "command": "..." } }
# Output: JSON on stdout with permissionDecision deny if invalid

set -uo pipefail

INPUT=$(cat)
CMD=$(echo "$INPUT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('tool_input',{}).get('command',''))" 2>/dev/null)

# Only intercept git commit commands
if ! echo "$CMD" | grep -qE 'git\s+commit'; then
  echo '{}'
  exit 0
fi

# Allow --amend and merge commits (changelog, etc)
if echo "$CMD" | grep -qE '\-\-amend|\-\-no-verify.*chore\(changelog\)|merge'; then
  echo '{}'
  exit 0
fi

# Check for the three required fields
MISSING=""
echo "$CMD" | grep -q "what:" || MISSING="${MISSING}what: "
echo "$CMD" | grep -q "why:"  || MISSING="${MISSING}why: "
echo "$CMD" | grep -q "breaking:" || MISSING="${MISSING}breaking: "

if [ -n "$MISSING" ]; then
  python3 -c "
import json, sys
missing = sys.argv[1]
print(json.dumps({
    'hookSpecificOutput': {
        'hookEventName': 'PreToolUse',
        'permissionDecision': 'deny',
        'permissionDecisionReason': f'Commit body is missing: {missing}. Rewrite with what:, why:, and breaking: fields. These feed the RAG memory — without them, future context is lost. Example:\n\ngit commit -m \"feat(auth): add JWT validation\" -m \"what: Added JwtTokenValidator with RS256 support\nwhy: API endpoints need stateless auth\nbreaking: none\"'
    }
}))
" "$MISSING"
else
  echo '{}'
fi
