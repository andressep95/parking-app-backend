#!/usr/bin/env bash
# Replays full git history into Chroma in a single Python process.
# Loads the embedding model ONCE, then processes all commits.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT"

total=$(git log --oneline | wc -l | tr -d ' ')
echo "Replaying $total commits into Chroma (single process)..."

git log --reverse --format="%H" > /tmp/driven-commits.txt

python3 << 'PYEOF'
import os
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"

import warnings
warnings.filterwarnings("ignore", message=".*unauthenticated.*")
warnings.filterwarnings("ignore", message=".*HF_TOKEN.*")

import sys, subprocess, re
from pathlib import Path

try:
    import chromadb
    from chromadb.utils import embedding_functions
except ImportError:
    print("ERROR: pip install chromadb sentence-transformers", file=sys.stderr)
    sys.exit(1)

# Load model ONCE (suppress HF Hub warnings)
import io, contextlib
client = chromadb.PersistentClient(path=".agents/memory/chroma")
with contextlib.redirect_stderr(io.StringIO()):
    ef = embedding_functions.SentenceTransformerEmbeddingFunction(model_name="intfloat/multilingual-e5-small")
collection = client.get_or_create_collection(name="changes", embedding_function=ef, metadata={"hnsw:space": "cosine"})

LANGUAGE_MAP = {
    ".java": "java", ".kt": "kotlin", ".py": "python", ".go": "go", ".rs": "rust",
    ".ts": "typescript", ".tsx": "typescript", ".js": "javascript", ".jsx": "javascript",
    ".cs": "csharp", ".rb": "ruby", ".php": "php", ".c": "c", ".cpp": "cpp",
    ".sh": "shell", ".bash": "shell", ".yaml": "yaml", ".yml": "yaml",
    ".json": "json", ".toml": "toml", ".sql": "sql", ".md": "markdown",
    ".css": "css", ".scss": "scss",
}

def run(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout.strip()

def file_kind(p):
    ext, name = Path(p).suffix.lower(), Path(p).stem.lower()
    if ext in {".sh",".bash",".zsh"}: return "script"
    if ext in {".py",".go",".ts",".tsx",".js",".jsx",".rs",".java",".kt",".c",".cpp",".rb",".cs"}:
        return "test" if "test" in name or "spec" in name else "source"
    if ext in {".json",".yaml",".yml",".toml",".env",".ini",".cfg"}: return "config"
    if ext in {".md",".txt",".rst",".adoc"}: return "doc"
    return "other"

def parse_hunks(diff):
    hunks, cur = [], None
    for line in diff.splitlines():
        if line.startswith("@@"):
            if cur: hunks.append(cur)
            m = re.match(r"@@\s+-\d+(?:,\d+)?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)", line)
            if not m: continue
            s = int(m.group(1)); c = int(m.group(2)) if m.group(2) else 1
            cur = {"symbol": m.group(3).strip(), "lines_start": s, "lines_end": s+max(c-1,0),
                   "adds": False, "dels": False}
        elif cur:
            if line.startswith("+") and not line.startswith("+++"): cur["adds"] = True
            elif line.startswith("-") and not line.startswith("---"): cur["dels"] = True
    if cur: hunks.append(cur)
    return hunks

project = Path(run("git rev-parse --show-toplevel")).name
with open("/tmp/driven-commits.txt") as f:
    commits = [l.strip() for l in f if l.strip()]
total = len(commits)
indexed = 0

for i, full_hash in enumerate(commits, 1):
    short = full_hash[:7]
    print(f"  [{i}/{total}] {short}", end="\r", flush=True)

    commit = run(f"git log -1 --format=%h {full_hash}")
    author = run(f"git log -1 --format=%an {full_hash}")
    ts     = run(f"git log -1 --format=%cI {full_hash}")[:10]
    intent = run(f"git log -1 --format=%s {full_hash}")
    body   = run(f"git log -1 --format=%b {full_hash}")

    raw = run(f"git branch --contains {full_hash} --format='%(refname:short)' 2>/dev/null")
    candidates = [b.strip().strip("'") for b in raw.splitlines() if b.strip()]
    main = [b for b in candidates if b in ("main","master")]
    branch = main[0] if main else (candidates[0] if candidates else "unknown")

    what, why, breaking = "", "", False
    for line in body.splitlines():
        if line.startswith("what:"): what = line[5:].strip()
        elif line.startswith("why:"): why = line[4:].strip()
        elif line.startswith("breaking:"): breaking = line[9:].strip().lower() == "true"

    m = re.match(r"^(\w+)(?:\(([\w/.-]+)\))?:", intent)
    commit_type = m.group(1) if m else ""
    scope = m.group(2) if m and m.group(2) else ""

    parent = run(f"git rev-parse --verify {full_hash}~1 2>/dev/null")
    if not parent: parent = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

    files = [f for f in run(f"git diff-tree --no-commit-id -r --name-only {full_hash}").splitlines() if f]
    if not files: continue

    # Check existing
    existing = set(collection.get(where={"commit": commit}, include=[])["ids"]) if collection.count() > 0 else set()

    ids, docs, metas = [], [], []
    for file in files:
        kind = file_kind(file)
        lang = LANGUAGE_MAP.get(Path(file).suffix.lower(), "other")
        diff = run(f'git diff {parent} {full_hash} -- "{file}" 2>/dev/null') or run(f'git show {full_hash} -- "{file}"')
        lines = diff.splitlines()
        si = next((j for j,l in enumerate(lines) if l.startswith("@@")), None)
        hunks = parse_hunks("\n".join(lines[si:]) if si else "")
        if not hunks:
            hunks = [{"symbol":"","lines_start":1,"lines_end":1,
                      "adds":any(l.startswith("+") for l in lines),
                      "dels":any(l.startswith("-") for l in lines)}]

        for h in hunks:
            rid = f"{commit}:{file}:{h['lines_start']}"
            if rid in existing: continue
            ctype = "modification" if h["adds"] and h["dels"] else ("addition" if h["adds"] else "deletion")
            tags = [t for t in [commit_type, ctype, kind, scope] if t]
            sdesc = f"{what} — {why}" if what and why else (what or f"{intent} ({h['symbol'] or file})")

            ids.append(rid)
            docs.append(sdesc)
            metas.append({"file":file,"file_kind":kind,"language":lang,"symbol":h["symbol"],
                          "what":what,"why":why,"intent":intent,"commit_type":commit_type,
                          "scope":scope,"change_type":ctype,"commit":commit,"branch":branch,
                          "project":project,"ts":ts,"author":author,"tags":",".join(tags),
                          "lines_start":h["lines_start"],"lines_end":h["lines_end"],
                          "breaking":str(breaking).lower()})

    if ids:
        for b in range(0, len(ids), 100):
            collection.upsert(ids=ids[b:b+100], documents=docs[b:b+100], metadatas=metas[b:b+100])
        indexed += len(ids)

print(f"\nDone. {total} commits processed, {indexed} hunks indexed.")
PYEOF
