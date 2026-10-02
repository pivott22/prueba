"""Restore the reader's signing key from a repository Actions secret."""
import base64
import json
import os
from pathlib import Path
import re

config = json.loads(os.environ.get('READER_SIGNING_CONFIG', '{}'))
password = config.get('password', '')
if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', password) or not config.get('keystore'):
    raise SystemExit('Configure the READER_SIGNING_CONFIG repository secret before building the reader in CI.')
print('::add-mask::' + password)
target = Path(os.environ['RUNNER_TEMP']) / 'reader-signing.p12'
target.write_bytes(base64.b64decode(config['keystore'], validate=True))
target.chmod(0o600)
with open(os.environ['GITHUB_ENV'], 'a', encoding='utf-8') as env:
    env.write('READER_SIGNING_FILE=' + str(target) + '\n')
    env.write('READER_SIGNING_PASSWORD=' + password + '\n')
print('Reader signing identity restored.')
