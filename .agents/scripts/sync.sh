#!/usr/bin/env bash
# Skill Sync — Updates Auto-Invoke Skills table in all detected context files.
#
# Detection (file existence, no config needed):
#   CLAUDE.md                          → Claude Code context
#   .kiro/steering/project-rules.md    → Kiro context
#
# Usage:
#   ./sync.sh             # Auto-detect and sync all existing context files
#   ./sync.sh --dry-run   # Preview without writing
#   ./sync.sh --help

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
SKILLS_DIR="$ROOT/.agents/skills"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

DRY_RUN=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=true; shift ;;
        --help|-h)
            echo "Usage: $0 [--dry-run]"
            echo ""
            echo "Syncs the Auto-Invoke Skills table into all detected context files."
            echo "Detection is file-based — no configuration required."
            echo ""
            echo "  CLAUDE.md                        → Claude Code"
            echo "  .kiro/steering/project-rules.md  → Kiro"
            exit 0 ;;
        *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# ── detect targets ────────────────────────────────────────────────────────────

TARGETS=()
[ -f "$ROOT/CLAUDE.md" ]                              && TARGETS+=("Claude Code|$ROOT/CLAUDE.md")
[ -f "$ROOT/.kiro/steering/project-rules.md" ]        && TARGETS+=("Kiro|$ROOT/.kiro/steering/project-rules.md")

echo -e "${BLUE}Skill Sync — syncing Auto-Invoke tables${NC}"
echo "======================================="
echo ""

if [ ${#TARGETS[@]} -eq 0 ]; then
    echo -e "${YELLOW}No context files found. Run setup first:${NC}"
    echo "  java -jar target/agent.jar setup-agent"
    exit 0
fi

echo -e "Detected context files:"
for t in "${TARGETS[@]}"; do
    label="${t%%|*}"; path="${t##*|}"
    echo -e "  ${GREEN}✓${NC} $label  →  ${path#$ROOT/}"
done
echo ""

# ── helpers ───────────────────────────────────────────────────────────────────

extract_field() {
    local file="$1" field="$2"
    awk -v field="$field" '
        /^---$/ { in_frontmatter = !in_frontmatter; next }
        in_frontmatter && $1 == field":" {
            sub(/^[^:]+:[[:space:]]*/, "")
            if ($0 != "" && $0 != ">") { gsub(/^["'"'"']|["'"'"']$/, ""); print; exit }
            getline
            while (/^[[:space:]]/ && !/^---$/) { sub(/^[[:space:]]+/, ""); printf "%s ", $0; if (!getline) break }
            print ""; exit
        }
    ' "$file" | sed 's/[[:space:]]*$//'
}

extract_auto_invoke() {
    local file="$1"
    awk '
        function trim(s) { sub(/^[[:space:]]+/, "", s); sub(/[[:space:]]+$/, "", s); return s }
        /^---$/ { in_fm = !in_fm; next }
        in_fm && /^metadata:/ { in_meta = 1; next }
        in_fm && in_meta && /^[a-z]/ && !/^[[:space:]]/ { in_meta = 0 }
        in_fm && in_meta && $1 == "auto_invoke:" {
            sub(/^[^:]+:[[:space:]]*/, "")
            if ($0 != "") { v = $0; gsub(/^["'"'"']|["'"'"']$/, "", v); print trim(v); exit }
            out = ""
            while (getline) {
                if (!in_fm || !in_meta) break
                if ($0 ~ /^[a-z]/ && $0 !~ /^[[:space:]]/) break
                if ($0 ~ /^---$/) break
                line = $0
                if (line ~ /^[[:space:]]*-[[:space:]]*/) {
                    sub(/^[[:space:]]*-[[:space:]]*/, "", line); line = trim(line)
                    gsub(/^["'"'"']|["'"'"']$/, "", line)
                    if (line != "") out = (out == "") ? line : out "|" line
                } else break
            }
            if (out != "") print out
            exit
        }
    ' "$file"
}

# ── collect all skill rows ────────────────────────────────────────────────────

ROWS_FILE=$(mktemp)
SKILLS_TABLE_FILE=$(mktemp)
trap 'rm -f "$ROWS_FILE" "$SKILLS_TABLE_FILE"' EXIT

while IFS= read -r skill_file; do
    [ -f "$skill_file" ] || continue
    name=$(extract_field "$skill_file" "name")
    desc=$(extract_field "$skill_file" "description")
    [ -z "$name" ] && continue

    # Skills table row
    rel_path="${skill_file#$ROOT/}"
    printf "%s\t%s\t%s\n" "$name" "$desc" "$rel_path" >> "$SKILLS_TABLE_FILE"

    # Auto-invoke rows
    auto_raw=$(extract_auto_invoke "$skill_file")
    [ -z "$auto_raw" ] && continue
    echo "$auto_raw" | tr '|' '\n' | while read -r action; do
        action="$(echo "$action" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        [ -z "$action" ] && continue
        printf "%s\t%s\n" "$action" "$name" >> "$ROWS_FILE"
    done
done < <(find "$SKILLS_DIR" -mindepth 2 -maxdepth 2 -name SKILL.md -print | sort)

# Build skills table markdown
SKILLS_TABLE="## Available Skills

| Skill | Description | File |
|-------|-------------|------|"
while IFS=$'\t' read -r name desc path; do
    SKILLS_TABLE="$SKILLS_TABLE
| \`$name\` | $desc | [SKILL.md]($path) |"
done < <(LC_ALL=C sort "$SKILLS_TABLE_FILE")

# Build auto-invoke table markdown
AUTO_INVOKE="## Auto-Invoke Skills

When performing these actions, ALWAYS load the corresponding skill FIRST:

| Action | Skill |
|--------|-------|"
while IFS=$'\t' read -r action name; do
    [ -z "$action" ] && continue
    AUTO_INVOKE="$AUTO_INVOKE
| $action | \`$name\` |"
done < <(LC_ALL=C sort -t $'\t' -k1,1 -k2,2 "$ROWS_FILE")

# ── update each detected context file ────────────────────────────────────────

update_section() {
    local file="$1" section_header="$2" new_content="$3"
    local section_file
    section_file=$(mktemp)
    echo "$new_content" > "$section_file"

    if grep -q "^$section_header" "$file"; then
        awk -v hdr="$section_header" -v sec="$section_file" '
            $0 ~ "^" hdr {
                while ((getline line < sec) > 0) print line
                close(sec); skip = 1; next
            }
            skip && /^## / { skip = 0; print ""; print; next }
            !skip { print }
        ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
    else
        echo "" >> "$file"
        cat "$section_file" >> "$file"
    fi
    rm -f "$section_file"
}

for t in "${TARGETS[@]}"; do
    label="${t%%|*}"; file="${t##*|}"
    rel="${file#$ROOT/}"

    echo -e "${CYAN}── $label  ($rel) ──────────────────────────────────────────${NC}"

    if $DRY_RUN; then
        echo -e "${YELLOW}[DRY RUN] Would update Available Skills and Auto-Invoke Skills sections${NC}"
        echo ""
        continue
    fi

    update_section "$file" "## Available Skills"    "$SKILLS_TABLE"
    echo -e "  ${GREEN}✓${NC} Available Skills updated"

    update_section "$file" "## Auto-Invoke Skills"  "$AUTO_INVOKE"
    echo -e "  ${GREEN}✓${NC} Auto-Invoke Skills updated"
    echo ""
done

# ── verify directory symlinks ────────────────────────────────────────────────
# setup-agent creates .claude/skills and .kiro/skills as directory symlinks
# pointing to .agents/skills/. New skills appear automatically — no per-skill
# symlinks are needed. This block warns if those symlinks are missing.

echo -e "${BLUE}Symlink health:${NC}"
_symlink_ok() {
    local link="$ROOT/$1" target="../.agents/skills"
    if [ -L "$link" ]; then
        echo -e "  ${GREEN}✓${NC} $1 → .agents/skills/"
    else
        echo -e "  ${YELLOW}⚠${NC}  $1 symlink missing — run: java -jar target/agent.jar setup-agent"
    fi
}
_symlink_ok ".claude/skills"
_symlink_ok ".kiro/skills"
echo ""

# ── report skills missing metadata ───────────────────────────────────────────

echo -e "${BLUE}Skills missing sync metadata:${NC}"
missing=0
while IFS= read -r skill_file; do
    [ -f "$skill_file" ] || continue
    name=$(extract_field "$skill_file" "name")
    auto=$(extract_auto_invoke "$skill_file")
    if [ -z "$auto" ]; then
        echo -e "  ${YELLOW}$name${NC} — missing auto_invoke in metadata"
        missing=$((missing + 1))
    fi
done < <(find "$SKILLS_DIR" -mindepth 2 -maxdepth 2 -name SKILL.md -print | sort)
[ $missing -eq 0 ] && echo -e "  ${GREEN}All skills have sync metadata${NC}"
echo ""

# ── sync skills to Chroma ────────────────────────────────────────────────────

echo -e "${BLUE}Chroma skill index:${NC}"
if $DRY_RUN; then
    echo -e "  ${YELLOW}[DRY RUN] Would re-index skills in Chroma${NC}"
elif python3 -c "import chromadb" &>/dev/null && [ -f "$SCRIPT_DIR/sync-skills-to-chroma.py" ]; then
    python3 "$SCRIPT_DIR/sync-skills-to-chroma.py" 2>/dev/null && \
        echo -e "  ${GREEN}✓${NC} Skills re-indexed in Chroma" || \
        echo -e "  ${YELLOW}⚠${NC}  Chroma indexing failed"
else
    echo -e "  ${YELLOW}⚠${NC}  chromadb not installed — skipping. Run: pip install chromadb"
fi
echo ""
echo -e "${GREEN}Done.${NC}"
