#!/usr/bin/env bash
# CLI smoke against a running jfs-server.
set -euo pipefail
URL="${1:-http://127.0.0.1:8080}"
TOKEN="${2:-${JFS_TOKEN:-}}"
CLI=(java -jar jfs-cli/target/jfs-cli.jar --url "$URL")
if [[ -n "$TOKEN" ]]; then
  CLI+=(--token "$TOKEN")
fi

echo "== ping =="
"${CLI[@]}" ping

tmp="$(mktemp)"
printf 'cli-demo-bytes' > "$tmp"
echo "== upload =="
"${CLI[@]}" put "$tmp" demo/cli.bin --mime application/octet-stream

echo "== list =="
"${CLI[@]}" ls -b demo

echo "== get =="
"${CLI[@]}" cat demo/cli.bin; echo

echo "== meta =="
"${CLI[@]}" meta demo/cli.bin --auth 0

echo "== rm =="
"${CLI[@]}" rm demo/cli.bin
rm -f "$tmp"
