#!/bin/bash
# PostToolUse hook: formats the edited Kotlin file with ktlint. Never fails the edit.
path=$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("file_path",""))' 2>/dev/null)
case "$path" in *.kt|*.kts) ;; *) exit 0 ;; esac
[ -f "$path" ] || exit 0
command -v java >/dev/null 2>&1 || exit 0
KT="${KTLINT_BIN:-$HOME/.cache/ktlint/ktlint}"
if [ ! -x "$KT" ]; then
  mkdir -p "$(dirname "$KT")"
  curl -fsSL -o "$KT" https://github.com/pinterest/ktlint/releases/latest/download/ktlint >/dev/null 2>&1 && chmod +x "$KT" || { rm -f "$KT"; exit 0; }
fi
"$KT" --format "$path" >/dev/null 2>&1 || true
exit 0
