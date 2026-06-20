#!/usr/bin/env python3
"""
query-all.py — single-process hook runner for UserPromptSubmit.

Loads the embedding model once, then queries:
  1. 'skills'  collection → injects skill hint
  2. 'changes' collection → injects prior project context

Writes one entry to query-log.jsonl per invocation.

Usage: python3 query-all.py <prompt> <chroma_path>
"""
import os
import sys
import json
import warnings
from datetime import datetime
from pathlib import Path

os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
warnings.filterwarnings("ignore", message=".*unauthenticated.*")

SKILL_THRESHOLD = 0.25
CHANGES_THRESHOLD = 0.72


def main():
    if len(sys.argv) < 3:
        print("Usage: query-all.py <prompt> <chroma_path>", file=sys.stderr)
        sys.exit(1)

    prompt = sys.argv[1]
    chroma_path = sys.argv[2]
    output = []

    try:
        import chromadb
        from chromadb.utils import embedding_functions
    except ImportError:
        sys.exit(0)

    try:
        client = chromadb.PersistentClient(path=chroma_path)
        ef = embedding_functions.SentenceTransformerEmbeddingFunction(
            model_name="intfloat/multilingual-e5-small"
        )

        # ── 1. skills ────────────────────────────────────────────────────
        try:
            col = client.get_collection("skills", embedding_function=ef)
            r = col.query(query_texts=[prompt], n_results=1)
            if r["ids"][0]:
                dist = r["distances"][0][0] if r.get("distances") else 1
                score = max(0, 1 - dist)
                if score >= SKILL_THRESHOLD:
                    name = r["metadatas"][0][0]["name"]
                    output.append(f"## Relevant Skill: {name}")
                    output.append(f"Load and follow: .agents/skills/{name}/SKILL.md")
                    output.append(f"Match confidence: {score:.0%}")
        except Exception:
            pass

        # ── 2. changes ───────────────────────────────────────────────────
        all_hits = []
        injected_hits = []
        try:
            col = client.get_collection("changes", embedding_function=ef)
            results = col.query(query_texts=[prompt], n_results=5)
            if results["ids"][0]:
                for i in range(len(results["ids"][0])):
                    m = results["metadatas"][0][i]
                    dist = results["distances"][0][i] if results.get("distances") else 0
                    score = max(0, 1 - dist)
                    all_hits.append({"score": score, "meta": m})
                    if score >= CHANGES_THRESHOLD:
                        injected_hits.append({"score": score, "meta": m})

                if injected_hits:
                    output.append("\n## Prior Context from Memory (Chroma)")
                    output.append(
                        "The following changes were previously made in this project "
                        "and are relevant to the current task:\n"
                    )
                    for h in injected_hits:
                        m = h["meta"]
                        sc = h["score"]
                        output.append(
                            f"## {m.get('symbol','')} "
                            f"({m.get('kind', m.get('file_kind', ''))})"
                        )
                        output.append(f"   File:   {m.get('file')}")
                        output.append(f"   Intent: {m.get('intent')}")
                        output.append(f"   Score:  {sc:.2%}\n")
        except Exception:
            pass

        # ── 3. audit log ─────────────────────────────────────────────────
        try:
            log_path = Path(chroma_path).parent / "query-log.jsonl"
            entry = {
                "ts": datetime.now().astimezone().isoformat(),
                "prompt": prompt[:120],
                "total_hits": len(all_hits),
                "injected": len(injected_hits),
                "dropped": len(all_hits) - len(injected_hits),
                "scores": [h["score"] for h in all_hits],
                "injected_files": [h["meta"].get("file", "") for h in injected_hits],
            }
            with open(log_path, "a") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
        except Exception:
            pass

    except Exception:
        pass

    print("\n".join(output))


if __name__ == "__main__":
    main()
