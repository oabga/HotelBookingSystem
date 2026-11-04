#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
bash "$SCRIPT_DIR/00-up.sh"
bash "$SCRIPT_DIR/10-check-db.sh"
bash "$SCRIPT_DIR/20-api-workflow.sh"
bash "$SCRIPT_DIR/10-check-db.sh"

echo
echo 'Backend Docker workflow completed successfully.'
