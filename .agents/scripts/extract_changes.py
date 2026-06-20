#!/usr/bin/env python3
"""
Post-commit extractor — indexes each modified hunk directly into ChromaDB.
Reads what/why/breaking from the commit body and the diff for exact location.
No intermediate JSONL — git is the source of truth, Chroma is the search layer.
"""

import os
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"

import warnings
warnings.filterwarnings("ignore", message=".*unauthenticated.*")

import re
import subprocess
from pathlib import Path


def run(cmd: str) -> str:
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout.strip()


LANGUAGE_MAP = {
    ".java": "java", ".kt": "kotlin", ".scala": "scala",
    ".py": "python", ".go": "go", ".rs": "rust",
    ".ts": "typescript", ".tsx": "typescript",
    ".js": "javascript", ".jsx": "javascript",
    ".cs": "csharp", ".rb": "ruby", ".php": "php",
    ".c": "c", ".cpp": "cpp", ".h": "c",
    ".sh": "shell", ".bash": "shell", ".zsh": "shell",
    ".yaml": "yaml", ".yml": "yaml",
    ".json": "json", ".toml": "toml",
    ".sql": "sql", ".md": "markdown",
    ".css": "css", ".scss": "scss",
}


def detect_language(path: str) -> str:
    return LANGUAGE_MAP.get(Path(path).suffix.lower(), "other")


def file_kind(path: str) -> str:
    ext = Path(path).suffix.lower()
    name = Path(path).stem.lower()
    if ext in {".sh", ".bash", ".zsh"}: return "script"
    if ext in {".py", ".go", ".ts", ".tsx", ".js", ".jsx", ".rs",
               ".java", ".kt", ".c", ".cpp", ".rb", ".cs"}:
        return "test" if "test" in name or "spec" in name else "source"
    if ext in {".json", ".yaml", ".yml", ".toml", ".env", ".ini", ".cfg"}: return "config"
    if ext in {".md", ".txt", ".rst", ".adoc"}: return "doc"
    if ext in {".css", ".scss", ".sass", ".less"}: return "style"
    return "other"


def parse_commit_parts(intent: str) -> tuple[str, str]:
    m = re.match(r"^(\w+)(?:\(([\w/.-]+)\))?:", intent)
    return (m.group(1) if m else "", m.group(2) if m and m.group(2) else "")


def parse_hunks(diff: str) -> list[dict]:
    hunks, current = [], None
    for line in diff.splitlines():
        if line.startswith("@@"):
            if current: hunks.append(current)
            m = re.match(r"@@\s+-\d+(?:,\d+)?\s+\+(\d+)(?:,(\d+))?\s+@@(.*)", line)
            if not m: continue
            start = int(m.group(1))
            count = int(m.group(2)) if m.group(2) is not None else 1
            current = {"symbol": m.group(3).strip(), "lines_start": start,
                       "lines_end": start + max(count - 1, 0), "lines_delta": 0,
                       "adds": False, "dels": False}
        elif current is not None:
            if line.startswith("+") and not line.startswith("+++"):
                current["adds"] = True; current["lines_delta"] += 1
            elif line.startswith("-") and not line.startswith("---"):
                current["dels"] = True; current["lines_delta"] -= 1
    if current: hunks.append(current)
    return hunks


def change_type(adds: bool, dels: bool) -> str:
    if adds and dels: return "modification"
    return "addition" if adds else "deletion"


def semantic_desc(what: str, why: str, intent: str, symbol: str, file: str) -> str:
    if what and why: return f"{what} — {why}"
    if what: return f"{what} ({symbol or file})"
    if why: return f"{intent} — {why}"
    return f"{intent} ({symbol or file})"


def main() -> None:
    import argparse, sys
    p = argparse.ArgumentParser()
    p.add_argument("--ref", default="HEAD")
    p.add_argument("--chroma", default=".agents/memory/chroma")
    p.add_argument("--collection", default="changes")
    args = p.parse_args()
    ref = args.ref

    try:
        import chromadb
        from chromadb.utils import embedding_functions
    except ImportError:
        print("ERROR: pip install chromadb", file=sys.stderr)
        sys.exit(1)

    # Git metadata
    commit  = run(f"git log -1 --format=%h {ref}")
    author  = run(f"git log -1 --format=%an {ref}")
    email   = run(f"git log -1 --format=%ae {ref}")
    ts      = run(f"git log -1 --format=%cI {ref}")[:10]
    intent  = run(f"git log -1 --format=%s {ref}")
    body    = run(f"git log -1 --format=%b {ref}")
    branch  = run("git rev-parse --abbrev-ref HEAD") if ref == "HEAD" else \
              (run(f"git branch --contains {ref} --format='%(refname:short)' 2>/dev/null").splitlines() or ["unknown"])[0].strip("'")
    project = Path(run("git rev-parse --show-toplevel")).name

    what, why, breaking = "", "", False
    for line in body.splitlines():
        if line.startswith("what:"): what = line[5:].strip()
        elif line.startswith("why:"): why = line[4:].strip()
        elif line.startswith("breaking:"): breaking = line[9:].strip().lower() == "true"

    commit_type, scope = parse_commit_parts(intent)

    parent = run(f"git rev-parse --verify {ref}~1 2>/dev/null")
    if not parent: parent = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

    files_raw = run(f"git diff-tree --no-commit-id -r --name-only {ref}")
    all_files = [f for f in files_raw.splitlines() if f]
    if not all_files: return

    # Chroma
    client = chromadb.PersistentClient(path=args.chroma)
    ef = embedding_functions.SentenceTransformerEmbeddingFunction(model_name="intfloat/multilingual-e5-small")
    collection = client.get_or_create_collection(
        name=args.collection, embedding_function=ef,
        metadata={"hnsw:space": "cosine"})

    # Check existing to skip duplicates
    existing = set(collection.get(where={"commit": commit}, include=[])["ids"]) \
               if collection.count() > 0 else set()

    ids, documents, metadatas = [], [], []

    for file in all_files:
        kind = file_kind(file)
        lang = detect_language(file)
        diff = run(f'git diff {parent} {ref} -- "{file}" 2>/dev/null') or \
               run(f'git show {ref} -- "{file}"')

        lines = diff.splitlines()
        start_idx = next((i for i, l in enumerate(lines) if l.startswith("@@")), None)
        diff_body = "\n".join(lines[start_idx:]) if start_idx is not None else ""

        hunks = parse_hunks(diff_body)
        if not hunks:
            hunks = [{"symbol": "", "lines_start": 1, "lines_end": 1, "lines_delta": 0,
                       "adds": any(l.startswith("+") for l in lines),
                       "dels": any(l.startswith("-") for l in lines)}]

        for h in hunks:
            rid = f"{commit}:{file}:{h['lines_start']}"
            if rid in existing: continue

            ctype = change_type(h["adds"], h["dels"])
            tags = [t for t in [commit_type, ctype, kind, scope] if t]
            sdesc = semantic_desc(what, why, intent, h["symbol"], file)

            ids.append(rid)
            documents.append(sdesc)
            metadatas.append({
                "file": file, "file_kind": kind, "language": lang,
                "symbol": h["symbol"], "what": what, "why": why,
                "intent": intent, "commit_type": commit_type, "scope": scope,
                "change_type": ctype, "commit": commit, "branch": branch,
                "project": project, "ts": ts, "author": author,
                "tags": ",".join(tags), "lines_start": h["lines_start"],
                "lines_end": h["lines_end"], "breaking": str(breaking).lower(),
            })

    if not ids: return

    BATCH = 100
    for i in range(0, len(ids), BATCH):
        collection.upsert(
            ids=ids[i:i+BATCH],
            documents=documents[i:i+BATCH],
            metadatas=metadatas[i:i+BATCH])

    print(f"[memory] {len(ids)} hunk(s) → Chroma ({commit} {branch})")


if __name__ == "__main__":
    main()
