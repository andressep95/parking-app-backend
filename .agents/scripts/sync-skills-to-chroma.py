#!/usr/bin/env python3
"""
Indexes all SKILL.md files into a Chroma 'skills' collection.
Embeds description + auto_invoke keywords for semantic skill matching.
Idempotent — rebuilds the collection each time (skills are few).

Usage:
  python3 .agents/scripts/sync-skills-to-chroma.py
"""
import os
os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
os.environ["TOKENIZERS_PARALLELISM"] = "false"
os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"

import warnings
warnings.filterwarnings("ignore", message=".*unauthenticated.*")
warnings.filterwarnings("ignore", message=".*HF_TOKEN.*")

import sys
from pathlib import Path


def parse_skill(skill_dir: Path) -> dict | None:
    skill_file = skill_dir / "SKILL.md"
    if not skill_file.exists():
        return None

    text = skill_file.read_text(encoding="utf-8")
    name = skill_dir.name

    # Extract description and auto_invoke from frontmatter
    lines = text.split("\n")
    desc_parts = []
    in_auto = False
    for line in lines[:40]:
        if line.startswith("description:"):
            desc_parts.append(line.split(":", 1)[1].strip())
        elif desc_parts and line.startswith("  ") and not line.strip().startswith("-") and not ":" in line.split()[0] if line.strip() else False:
            desc_parts.append(line.strip())
        elif "auto_invoke" in line:
            in_auto = True
        elif in_auto and line.strip().startswith("- "):
            desc_parts.append(line.strip().lstrip("- ").strip('"').strip("'"))
        elif in_auto and not line.strip().startswith("-") and line.strip():
            in_auto = False

    # Extract "When to Use" section for richer embedding
    in_when = False
    for line in lines:
        if "when to use" in line.lower() or "cuándo usar" in line.lower():
            in_when = True
            continue
        if in_when:
            if line.startswith("#"):
                break
            stripped = line.strip().lstrip("- ")
            if stripped and not stripped.startswith("```"):
                desc_parts.append(stripped)

    if not desc_parts:
        # fallback: use first non-frontmatter paragraph
        in_body = False
        for line in lines:
            if line.startswith("---") and in_body:
                break
            if line.startswith("---"):
                in_body = True
                continue
            if in_body and line.strip() and not line.startswith("#"):
                desc_parts.append(line.strip())
                if len(desc_parts) >= 3:
                    break

    return {
        "name": name,
        "document": f"{name}: {' '.join(desc_parts)}",
        "path": str(skill_file),
    }


def main():
    skills_dir = Path(".agents/skills")
    chroma_path = ".agents/memory/chroma"
    collection_name = "skills"

    if not skills_dir.is_dir():
        print("No .agents/skills/ directory found.")
        sys.exit(1)

    try:
        import chromadb
        from chromadb.utils import embedding_functions
    except ImportError:
        print("ERROR: pip install chromadb", file=sys.stderr)
        sys.exit(1)

    skills = []
    for d in sorted(skills_dir.iterdir()):
        if d.is_dir():
            s = parse_skill(d)
            if s:
                skills.append(s)

    if not skills:
        print("No skills found.")
        return

    client = chromadb.PersistentClient(path=chroma_path)
    import io, contextlib
    with contextlib.redirect_stderr(io.StringIO()):
        ef = embedding_functions.SentenceTransformerEmbeddingFunction(model_name="intfloat/multilingual-e5-small")

    # Rebuild — skills are few, always fresh
    try:
        client.delete_collection(collection_name)
    except Exception:
        pass

    collection = client.get_or_create_collection(
        name=collection_name,
        embedding_function=ef,
        metadata={"hnsw:space": "cosine"},
    )

    collection.upsert(
        ids=[s["name"] for s in skills],
        documents=[s["document"] for s in skills],
        metadatas=[{"name": s["name"], "path": s["path"]} for s in skills],
    )

    print(f"Indexed {len(skills)} skills into Chroma collection '{collection_name}':")
    for s in skills:
        print(f"  - {s['name']}: {s['document'][:80]}...")


if __name__ == "__main__":
    main()
