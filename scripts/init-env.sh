#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  echo "Existing .env preserved."
  exit 0
fi
umask 077
python3 - <<'ENV'
from pathlib import Path
import base64,secrets
text=Path('.env.example').read_text()
values={'DATABASE_PASSWORD':secrets.token_hex(24),'JWT_SECRET':base64.b64encode(secrets.token_bytes(32)).decode(),'APPLE_ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode()}
for key,value in values.items():
    text=text.replace(key+'='+chr(10),key+'='+value+chr(10))
Path('.env').write_text(text)
print('Created .env with private local secrets.')
ENV
mkdir -p secrets
