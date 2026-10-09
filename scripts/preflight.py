#!/usr/bin/env python3
"""Validate deployment facts and key lengths without printing credential values."""
import argparse, base64, os, re, shlex, stat
from pathlib import Path
from urllib.parse import urlsplit
parser=argparse.ArgumentParser()
parser.add_argument('--env-file',default='.env.production')
args=parser.parse_args()
path=Path(args.env_file)
if not path.is_file(): parser.exit(1,'Missing production env file. Copy .env.production.example and configure it.\n')
values={}
for line in path.read_text().splitlines():
    if not line.strip() or line.lstrip().startswith('#'): continue
    key,sep,value=line.partition('=')
    if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*',key.strip()): parser.exit(1,'Invalid dotenv entry.\n')
    tokens=shlex.split(value,comments=True)
    values[key.strip()]=' '.join(tokens)
values.update({key:os.environ[key] for key in values if key in os.environ})
errors=[]
if stat.S_IMODE(path.stat().st_mode)&0o077: errors.append('Protect the env file with chmod 600.')
def placeholder(value):
    return not value or any(x in value.lower() for x in ('replace','demo','.example','.invalid','.test','.localhost','example.com','example.org','example.net'))
for key in ('PUBLIC_ORIGIN','PUBLIC_DOMAIN','OPERATOR_NAME','OPERATOR_ADDRESS','SUPPORT_EMAIL','PRIVACY_EMAIL','HOSTING_REGION','DATA_PROCESSORS'):
    if placeholder(values.get(key,'')): errors.append(f'Configure a real {key}.')
u=urlsplit(values.get('PUBLIC_ORIGIN',''))
if u.scheme!='https' or not u.hostname or u.username or u.password or u.path not in ('','/') or u.query or u.fragment or u.port not in (None,443) or u.hostname!=values.get('PUBLIC_DOMAIN'):
    errors.append('PUBLIC_ORIGIN must be the HTTPS origin for PUBLIC_DOMAIN (standard port 443).')
for key in ('SUPPORT_EMAIL','PRIVACY_EMAIL'):
    if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',values.get(key,'')): errors.append(f'Invalid {key}.')
if values.get('PUBLIC_SITE_MOCK','false')!='false': errors.append('PUBLIC_SITE_MOCK must be false.')
if len(values.get('DATABASE_PASSWORD',''))<24: errors.append('DATABASE_PASSWORD must have at least 24 characters.')
def secret(key):
    try: return len(base64.b64decode(values.get(key,''),validate=True))
    except Exception: return 0
for key in ('MYSQL_ROOT_PASSWORD','RUNTIME_DATABASE_PASSWORD','SMTP_PASSWORD'):
    if len(values.get(key,''))< (1 if key=='SMTP_PASSWORD' else 24): errors.append(f'Configure {key}.')
if len({values.get(k) for k in ('MYSQL_ROOT_PASSWORD','DATABASE_PASSWORD','RUNTIME_DATABASE_PASSWORD')})!=3: errors.append('Use different runtime and migration database passwords.')
for key in ('RECOVERY_FROM','SMTP_HOST','SMTP_USERNAME'):
    if placeholder(values.get(key,'')): errors.append(f'Configure a real {key}.')
if not re.fullmatch(r'[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+',values.get('RECOVERY_FROM','')): errors.append('Invalid RECOVERY_FROM.')
if values.get('SMTP_STARTTLS','true')!='true' and values.get('SMTP_SSL','false')!='true': errors.append('SMTP must use STARTTLS or TLS.')
try: smtp_port=int(values.get('SMTP_PORT','587'))
except ValueError: smtp_port=0
if not 1<=smtp_port<=65535: errors.append('Invalid SMTP_PORT.')
if secret('JWT_SECRET')<32: errors.append('JWT_SECRET must decode to at least 32 random bytes.')
try: retention=int(values.get('BACKUP_RETENTION_DAYS','30'))
except ValueError: retention=0
if not 1<=retention<=90: errors.append('BACKUP_RETENTION_DAYS must be 1–90.')
if values.get('APPLE_ENABLED','false')=='true':
    if secret('APPLE_ENCRYPTION_KEY')!=32: errors.append('APPLE_ENCRYPTION_KEY must decode to exactly 32 random bytes.')
    for key in ('APPLE_CLIENT_ID','APPLE_TEAM_ID','APPLE_KEY_ID'):
        if not values.get(key): errors.append(f'Configure {key}.')
    if not Path('secrets/AuthKey.p8').is_file(): errors.append('Provide secrets/AuthKey.p8, readable by container UID 10001.')
if values.get('APPLE_WEB_CLIENT_ID'):
    if values.get('APPLE_ENABLED')!='true' or values.get('APPLE_WEB_REDIRECT_URI')!=values.get('PUBLIC_ORIGIN','').rstrip('/')+'/delete-account':
        errors.append('Apple web login needs enabled native Apple credentials and the exact HTTPS /delete-account redirect.')
if values.get('ALLOW_ANONYMOUS_SHARING','false') not in ('true','false'): errors.append('Invalid ALLOW_ANONYMOUS_SHARING.')
if errors:
    for error in errors: print(error)
    parser.exit(1)
print('Production configuration passes preflight. No credentials printed.')
