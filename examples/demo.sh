#!/usr/bin/env bash
set -euo pipefail
BASE="${1:-http://127.0.0.1:8080}"

echo "== ping =="
curl -s "$BASE/ping"; echo

echo "== upload =="
printf 'demo-image-bytes' > /tmp/jfs-demo.bin
curl -s -X PUT --data-binary @/tmp/jfs-demo.bin \
  -H 'Content-Type: application/octet-stream' \
  "$BASE/demo/sample.bin"; echo

echo "== download =="
curl -s "$BASE/demo/sample.bin"; echo

echo "== meta =="
curl -s "$BASE/get?bucket=demo&filename=sample.bin&meta=1"; echo

echo "== stats =="
curl -s "$BASE/stats"; echo

echo "== overview =="
curl -s "$BASE/overview"; echo

echo "== admin =="
echo "open $BASE/admin"
