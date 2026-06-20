#!/usr/bin/env bash
# Full memory bootstrap: replay git history directly into Chroma.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT"

REQUIREMENTS="$SCRIPT_DIR/requirements.txt"

# ── Check python3 ──────────────────────────────────────────────────────────
if ! command -v python3 &>/dev/null; then
    echo "[init] ERROR: python3 not found. Install Python 3.9+ and try again."
    exit 1
fi

# ── Check & install Python dependencies ────────────────────────────────────
if ! python3 -c "import chromadb" &>/dev/null; then
    echo "[init] Missing Python dependencies (chromadb)."
    if [ -t 0 ]; then
        printf "[init] Install them now? (pip install -r requirements.txt) [Y/n]: "
        read -r answer
        answer="${answer:-Y}"
        if [[ ! "$answer" =~ ^[Yy]$ ]]; then
            echo "[init] Skipped. Install manually: pip install -r $REQUIREMENTS"
            exit 1
        fi
    else
        echo "[init] Non-interactive mode — installing automatically."
    fi

    if ! pip install -r "$REQUIREMENTS"; then
        echo "[init] ERROR: pip install failed. Try: pip install -r $REQUIREMENTS"
        exit 1
    fi
    echo "[init] Dependencies installed."
fi

# ── Memory bootstrap ──────────────────────────────────────────────────────
echo "=== Memory Bootstrap ==="

echo "[1/2] Replaying git history into Chroma..."
bash "$SCRIPT_DIR/scan-history.sh"

echo "[2/2] Indexing skills into Chroma..."
python3 "$SCRIPT_DIR/sync-skills-to-chroma.py"

echo "=== Done ==="
