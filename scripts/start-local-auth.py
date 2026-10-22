#!/usr/bin/env python3
"""Start local Google OAuth without logging credentials or private signing keys."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
env = os.environ.copy()
if (ROOT / '.env').exists():
    for raw in (ROOT / '.env').read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        pieces = shlex.split(value, comments=True)
        if key.strip() not in os.environ:
            env[key.strip()] = ' '.join(pieces)
for key in ('GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET'):
    if not env.get(key):
        sys.exit(f'Preencha {key} em {ROOT / ".env"} antes de iniciar o Google OAuth.')
cli = ['npx', '--yes', 'supabase@2.121.0']
local = ROOT / '.local'
local.mkdir(exist_ok=True, mode=0o700)
keys = ROOT / 'supabase/signing_keys.json'
if not keys.exists():
    result = subprocess.run(cli + ['gen', 'signing-key', '--algorithm', 'ES256', '--workdir', '/tmp', '--output', 'json'], env=env, capture_output=True, text=True, check=True)
    key = json.loads(result.stdout)
    if key.get('kty') != 'EC':
        sys.exit('Não foi possível gerar a chave ES256 local.')
    fd = os.open(keys, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump([key], output)
config = ROOT / 'supabase/config.toml'
text = config.read_text().replace('[auth.external.google]\nenabled = false', '[auth.external.google]\nenabled = true')
config.write_text(text)
with (local / 'auth-start.log').open('w') as log:
    existing = subprocess.run(['docker', 'container', 'inspect', 'supabase_db_glicnote-local'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if existing.returncode == 0:
        subprocess.run(cli + ['stop'], env=env, stdout=log, stderr=log, check=True)
    subprocess.run(cli + ['start', '--exclude', 'realtime,storage-api,imgproxy,mailpit,postgres-meta,studio,edge-runtime,logflare,vector,supavisor'], env=env, stdout=log, stderr=log, check=True)
result = subprocess.run(cli + ['status', '--output', 'json'], env=env, capture_output=True, text=True, check=True)
status = json.loads(result.stdout)
key = status.get('PUBLISHABLE_KEY') or status.get('ANON_KEY')
if not key:
    sys.exit('O Supabase não retornou uma chave pública para o cliente.')
client = {'AUTH_ENABLED': 'true', 'SUPABASE_URL': 'http://127.0.0.1:54321', 'SUPABASE_PUBLISHABLE_KEY': key,
          'API_BASE_URL': f'http://127.0.0.1:{env.get("API_PORT", "8080")}', 'AUTH_REDIRECT_URL': 'com.glicnote.app://login-callback'}
(local / 'flutter-auth.json').write_text(json.dumps(client, indent=2) + '\n')
subprocess.run(['docker', 'compose', '-f', 'compose.yaml', '-f', 'compose.auth.yaml', 'up', '-d', '--build', '--wait'], env=env, check=True)
print('Auth local: http://127.0.0.1:54321')
print('Configuração pública do app: .local/flutter-auth.json')
print('Google Client Secret permanece somente no ambiente local do servidor.')
