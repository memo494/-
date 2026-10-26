#!/bin/bash
# PreToolUse hook: blocks edits to signing keys, local secrets and real test screenshots.
path=$(python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("file_path",""))' 2>/dev/null)
case "$path" in
  *.jks|*.p12|*.keystore|*/local.properties|*.env|*/.env.*)
    echo "Blocked: $path holds keys or secrets and must not be edited by an agent." >&2; exit 2 ;;
  */app/src/test/resources/*.png|*/app/src/test/resources/*.jpg|*/app/src/test/resources/*.jpeg|*/app/src/test/resources/*.webp)
    echo "Blocked: $path is a real test screenshot; tests must use synthetic images (AGENTS.md rule 4-5)." >&2; exit 2 ;;
esac
exit 0
