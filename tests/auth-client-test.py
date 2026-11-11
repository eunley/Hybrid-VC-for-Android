#!/usr/bin/env python3
"""Exercise the actual CLI against a local HTTP server, including retries/cancel."""
import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading

client = Path(__file__).resolve().parents[1] / 'assets/upload_role.py'
seen = []
expected = ''
fail_once = False
secret = 'Test-secret-0123456789'
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def handle_request(self):
        global fail_once
        seen.append((self.command, self.path, self.headers.get('Authorization')))
        assert self.headers.get('Authorization') == expected, seen[-1]
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        code = 200
        if self.command == 'DELETE':
            value = {}
        elif self.path.endswith('/complete'):
            if fail_once:
                fail_once = False
                code, value = 409, {'error': 'busy ' + secret}
            else:
                code, value = 202, {'state': 'succeeded', 'echo': (expected or '')[7:]}
        elif self.path.endswith('/uploads'):
            value = {'id': 'test'}
        else:
            value = {'state': 'receiving', 'missing': [0]}
        data = json.dumps(value).encode()
        self.send_response(code)
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    do_POST = do_PUT = do_GET = do_DELETE = handle_request

with tempfile.TemporaryDirectory(prefix='auth-client-') as tmp, http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    archive = Path(tmp) / 'role.zip'
    archive.write_bytes(b'role-data')
    env = dict(os.environ, HTTP_TOKEN=secret)
    base = [sys.executable, str(client), '--url', 'http://127.0.0.1:%d' % server.server_port, '--zip', str(archive), '--retry-delay', '.01']
    def run(extra):
        result = subprocess.run(base + extra, env=env, capture_output=True, text=True, timeout=10)
        assert result.returncode == 0, result.stderr
        assert not expected or expected[7:] not in result.stdout + result.stderr, 'token logged'
    try:
        expected = 'Bearer ' + secret
        fail_once = True
        run(['--name', 'alice'])
        assert [m for m, _, _ in seen] == ['POST', 'GET', 'PUT', 'POST', 'POST'], seen
        assert not Path(str(archive) + '.session.json').exists()
        # Explicit override and absence of auth when explicitly empty.
        expected = 'Bearer override-key'
        run(['--name', 'alice', '--token', 'override-key'])
        expected = None
        run(['--name', 'alice', '--token', ''])
        # Cancellation uses the same authenticated request helper.
        expected = 'Bearer ' + secret
        Path(str(archive) + '.session.json').write_text(json.dumps({'url': base[3] + '/api/v1', 'id': 'test'}))
        run(['--cancel'])
        assert seen[-1][0] == 'DELETE'
        result = subprocess.run(base + ['--name', 'alice', '--token', 'invalid key'], env=env, capture_output=True, text=True)
        assert result.returncode != 0 and 'invalid key' not in result.stderr
    finally:
        server.shutdown()
        thread.join()
print('PASS auth client: env/override/empty/create/status/chunk/complete/retry/cancel/redaction')
