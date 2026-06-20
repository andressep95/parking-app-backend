---
name: skill-sync
description: >
  Keeps the Available Skills and Auto-Invoke Skills tables in sync with skill
  metadata after any skill is created or modified. Detects CLAUDE.md and
  .kiro/steering/project-rules.md by file existence and updates both.
  Trigger: After creating or modifying any SKILL.md file.
metadata:
  version: "2.0"
  scope: [root]
  auto_invoke:
    - "After creating or modifying a skill"
    - "Auto-invoke table is out of sync"
    - "Después de crear o modificar un skill"
    - "Sincronizar tabla de skills"
allowed-tools: Read, Glob, Edit, Bash
---

## Commands

```bash
# Sync all skills into all detected context files
bash .agents/scripts/sync.sh

# Preview without writing
bash .agents/scripts/sync.sh --dry-run
```

---

## What the Script Does

After any skill is created or modified, perform these steps:

### 1. Detect context files

The script detects which context files exist and syncs only those:

| File | AI Assistant |
|------|-------------|
| `CLAUDE.md` | Claude Code |
| `.kiro/steering/project-rules.md` | Kiro |

### 2. Read all skills and rebuild tables

Replaces `## Available Skills` and `## Auto-Invoke Skills` sections with
freshly generated tables from SKILL.md frontmatter.

### 3. Re-index skills in Chroma

Runs `sync-skills-to-chroma.py` to rebuild the `skills` collection so the
`UserPromptSubmit` hook can match skills semantically (cross-language).

---

## Required Metadata in Every SKILL.md

```yaml
metadata:
  version: "1.0"
  scope: [root]
  auto_invoke:
    - "Trigger phrase 1"
    - "Trigger phrase 2"
```

Skills missing `auto_invoke` appear in the "missing sync metadata" report but
are still listed in the Available Skills table.
