---
name: skill-creator
description: >
  Creates new AI agent skills following the project skill spec.
  Trigger: When user asks to create a new skill, add agent instructions, or document patterns for AI reuse.
metadata:
  version: "1.0"
  scope: [root]
  auto_invoke:
    - "Create a new skill"
    - "Add agent instructions"
    - "Document a pattern for AI reuse"
    - "Add a slash command"
    - "Teach the agent how to do X"
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# Skill Creator

Protocol for creating and maintaining AI agent skills in this project. Every skill is a Markdown file that the agent loads on demand — the file IS the protocol, not just documentation.

## When to Use (and When NOT to)

| Create a skill when | Skip / extend instead when |
|---------------------|---------------------------|
| A pattern is used repeatedly across tasks | The pattern is trivial or self-explanatory |
| Project conventions differ from generic best practices | It's a one-off task unlikely to recur |
| A workflow has enough steps to need a decision tree | An existing skill already covers it — extend it |
| A new domain is added (new service, new layer, new tool) | The rule fits in a single sentence in CLAUDE.md |

## Decision Tree

```
Should I create a skill?
├─ Does the same pattern come up in 3+ different tasks?
│   └─ NO → Don't create it yet
├─ Does an existing skill cover 80%+ of this?
│   └─ YES → Edit that skill instead
├─ Is the guidance more than 5 lines?
│   └─ NO → Add a note to CLAUDE.md instead
└─ All checks pass → Create the skill
```

## Critical Rules

| Rule | Why |
|------|-----|
| **Check `skills/` before creating** | Avoid duplicates — extend existing skills instead |
| **Name must follow conventions** | Semantic matching in Chroma depends on consistent naming |
| **`auto_invoke` phrases are required** | The hook uses them for semantic routing — no phrases = skill never auto-loads |
| **Run `sync.sh` after every create or edit** | CLAUDE.md tables and Chroma index go stale otherwise |
| **No web URLs in references** | Use local paths only — URLs break and add noise |
| **One pattern per code block** | Dense blocks get skipped; one clear example beats three vague ones |

## Skill Structure

```
.agents/skills/{skill-name}/
├── SKILL.md              # Required — main skill file loaded by the agent
└── references/           # Optional — detailed reference docs
    ├── CHEATSHEET.md     # Quick decision guide
    └── *.md              # One file per topic area
```

## Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Generic tech skill | `{technology}` | `go-aws`, `terraform` |
| Project-specific | `{project}-{area}` | `discovery-api` |
| Workflow skill | `{action}` | `skill-creator`, `commit` |
| AWS-specific | `aws-{topic}` | `aws-mcp` |

## SKILL.md Template

```markdown
---
name: {skill-name}
description: >
  {One-line description of what this skill does}.
  Trigger: {When the agent should load this skill}.
metadata:
  version: "1.0"
  scope: [root]
  auto_invoke:
    - "{Trigger phrase 1}"
    - "{Trigger phrase 2}"
allowed-tools: Read, Edit, Write, Glob, Grep, Bash
---

# {Skill Name}

{One paragraph: what this skill governs and why it matters.}

## When to Use (and When NOT to)

| Use when | Skip when |
|----------|-----------|
| ... | ... |

## Decision Tree / Quick Diagnosis

{ASCII tree for the most common decisions}

## Critical Rules

| Rule | Why |
|------|-----|
| ... | ... |

## Patterns

### 1. {Pattern Name}

{One minimal code example}

## Commands

{Copy-paste bash commands}

## Internal Reference

| File | Content |
|------|---------|
| [references/CHEATSHEET.md](references/CHEATSHEET.md) | Quick decision guide |
```

## Scope Field

```yaml
scope: [root]                   # applies everywhere
scope: [root, internal/auth]    # restricted to a subtree
scope: [root, src/main]         # source code only
```

## Content Guidelines

| Do | Don't |
|----|-------|
| Lead with the most critical rules | Duplicate content from another skill — link instead |
| Use tables for decisions and comparisons | Add web URLs in references — use local paths |
| Keep code examples minimal — one pattern per block | Write lengthy explanations — link to docs |
| Include a Commands section with copy-paste snippets | Create a skill for a pattern that appears only once |
| Reference ADRs when a rule has an architectural origin | Mix languages in `auto_invoke` phrases |

## After Creating a Skill

```bash
# Sync CLAUDE.md tables + re-index Chroma
bash .agents/scripts/sync.sh
```

## Creation Checklist

```
[ ] Checked skills/ — no existing skill to extend
[ ] Pattern is reusable, not one-off
[ ] Name follows conventions (see table above)
[ ] Frontmatter complete: name, description (with Trigger), metadata, allowed-tools
[ ] auto_invoke phrases defined — English only, verb phrases
[ ] # Heading present after frontmatter
[ ] When to Use table present
[ ] Critical Rules section present
[ ] At least one code example or decision tree
[ ] sync.sh run after creation
[ ] Scaffold copy updated: src/main/resources/scaffold/skills/
```
