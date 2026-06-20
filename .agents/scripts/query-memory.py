#!/usr/bin/env python3
"""
Searches agent memory via ChromaDB semantic search.
"""
import os
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"

import warnings
warnings.filterwarnings("ignore", message=".*unauthenticated.*")

import sys, argparse, json
from datetime import datetime
from pathlib import Path

THRESHOLD_DEFAULT = 0.72


def _log_query(prompt: str, all_hits: list, injected_hits: list, chroma_path: str) -> None:
    log_path = Path(chroma_path).parent / "query-log.jsonl"
    entry = {
        "ts":             datetime.now().astimezone().isoformat(),
        "prompt":         prompt[:120],
        "total_hits":     len(all_hits),
        "injected":       len(injected_hits),
        "dropped":        len(all_hits) - len(injected_hits),
        "scores":         [h["score"] for h in all_hits],
        "injected_files": [h["meta"].get("file", "") for h in injected_hits],
    }
    try:
        with open(log_path, "a") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:
        pass


def print_symbol(m, score_label):
    lines = m.get('lines', m.get('lines_start'))
    if isinstance(lines, list):
        line_str = f"{lines[0]}-{lines[1]}"
    elif lines:
        line_str = f"{m.get('lines_start', 0)}-{m.get('lines_end', 0)}"
    else:
        line_str = '—'
    print(f"## {m.get('symbol')} ({m.get('kind', m.get('file_kind', ''))})")
    print(f"   File:   {m.get('file')}")
    print(f"   Lines:  {line_str}")
    print(f"   Intent: {m.get('intent')}")
    print(f"   Score:  {score_label}")
    print()


def print_change(m, score_label):
    print(f"## [{m.get('change_type','?').upper()}] {m.get('symbol')} — {m.get('file_kind','')}")
    print(f"   File:   {m.get('file')}")
    print(f"   Lines:  {m.get('lines_start', 0)}-{m.get('lines_end', 0)}  Δ{m.get('lines_delta', 0):+d}")
    print(f"   Intent: {m.get('intent')}")
    print(f"   Commit: {m.get('commit')}  {m.get('ts')}  {m.get('author')} <{m.get('email')}>")
    hunk = (m.get('hunk_content') or '').strip()
    if hunk:
        preview = '\n   '.join(hunk.splitlines()[:6])
        print(f"   Diff:\n   {preview}")
    print(f"   Score:  {score_label}")
    print()


def print_result(m, score_label):
    if m.get('type') == 'change':
        print_change(m, score_label)
    else:
        print_symbol(m, score_label)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('query', nargs='?', default='')
    p.add_argument('--chroma',     default='.agents/memory/chroma')
    p.add_argument('--collection', default='changes')
    p.add_argument('--kind',       default='', help='Filter by kind/file_kind')
    p.add_argument('--type',       default='', help='Filter by type: symbol | change')
    p.add_argument('--limit',      type=int, default=10)
    p.add_argument('--threshold',  type=float, default=THRESHOLD_DEFAULT,
                   help='Minimum relevance score to include a result (default: 0.72)')
    p.add_argument('--no-log',     action='store_true',
                   help='Skip audit logging to query-log.jsonl')
    args = p.parse_args()

    if not args.query:
        print("Usage: query-memory.py <query> [--type change|symbol] [--kind skill] [--limit 5]")
        sys.exit(1)

    try:
        import chromadb
        from chromadb.utils import embedding_functions
    except ImportError:
        print("ERROR: chromadb not installed. Run: pip install chromadb", file=sys.stderr)
        sys.exit(1)

    try:
        client = chromadb.PersistentClient(path=args.chroma)
        ef = embedding_functions.SentenceTransformerEmbeddingFunction(model_name="intfloat/multilingual-e5-small")
        collection = client.get_collection(args.collection, embedding_function=ef)

        where = {}
        if args.kind:
            where['$or'] = [{'kind': args.kind}, {'file_kind': args.kind}]
        if args.type:
            where['type'] = args.type

        results = collection.query(
            query_texts=[args.query],
            n_results=args.limit,
            where=where or None,
        )

        if not results['ids'][0]:
            print("No results found.")
            return

        all_hits = []
        injected_hits = []
        for i in range(len(results['ids'][0])):
            m = results['metadatas'][0][i]
            dist  = results['distances'][0][i] if results.get('distances') else 0
            score = max(0, 1 - dist)
            all_hits.append({"score": score, "meta": m})
            if score >= args.threshold:
                injected_hits.append({"score": score, "meta": m})
                print_result(m, f"{score:.2%}")

        if not args.no_log:
            _log_query(args.query, all_hits, injected_hits, args.chroma)

        if not injected_hits:
            print(f"No results above relevance threshold ({args.threshold:.0%}).")

    except Exception as e:
        print(f"[query-memory] Chroma error: {e}", file=sys.stderr)
        print("No results found. Run 'bash .agents/scripts/init.sh' to initialize memory.")


if __name__ == '__main__':
    main()
