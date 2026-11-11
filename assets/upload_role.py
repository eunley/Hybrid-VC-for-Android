#!/usr/bin/env python3
"""Resumable role ZIP uploader; Python 3 standard library only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CHUNK = 8 * 1024 * 1024

class ApiError(Exception):
    def __init__(self, code, body):
        self.code = code
        super().__init__("HTTP %s: %s" % (code, body))

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", required=True, help="server URL, optionally ending /api/v1")
    ap.add_argument("--token", default=os.environ.get("HTTP_TOKEN", ""), help="Bearer token (default: HTTP_TOKEN; empty disables auth)")
    ap.add_argument("--name", help="role name (required except with --cancel)")
    ap.add_argument("--zip", required=True, type=Path, dest="archive")
    ap.add_argument("--sha256", action="store_true", help="compute and send ZIP SHA-256")
    ap.add_argument("--session", type=Path, help="default: ZIP path + .session.json")
    ap.add_argument("--cancel", action="store_true", help="explicitly DELETE saved upload")
    ap.add_argument("--retry-delay", type=float, default=2)
    ap.add_argument("--retries", type=int, default=10, help="retries per request")
    ap.add_argument("--timeout", type=float, default=60)
    a = ap.parse_args()
    # Redact a server-echoed token from progress, errors and completion output.
    if a.token:
        class RedactedOutput:
            def __init__(self, stream):
                self.stream = stream
            def write(self, text):
                return self.stream.write(text.replace(a.token, "[REDACTED]"))
            def flush(self):
                self.stream.flush()
        sys.stdout = RedactedOutput(sys.stdout)
        sys.stderr = RedactedOutput(sys.stderr)
    if len(a.token) > 256 or any(not 33 <= ord(c) <= 126 for c in a.token):
        ap.error("invalid HTTP token: use up to 256 visible ASCII characters without spaces")
    if a.retries < 0 or a.retry_delay <= 0 or a.timeout <= 0:
        ap.error("retries must be nonnegative; delay and timeout must be positive")
    if not a.cancel and not a.name:
        ap.error("--name is required")
    base = a.url.rstrip("/")
    if not base.endswith("/api/v1"):
        base += "/api/v1"
    if urllib.parse.urlsplit(base).scheme not in ("http", "https"):
        ap.error("--url must use http or https")
    archive = a.archive.expanduser().resolve()
    session = a.session or Path(str(archive) + ".session.json")

    def request(method, route, value=None, raw=None):
        body = raw if raw is not None else (json.dumps(value).encode() if value is not None else None)
        headers = {"Accept": "application/json"}
        if a.token:
            headers["Authorization"] = "Bearer " + a.token
        if body is not None:
            headers["Content-Type"] = "application/octet-stream" if raw is not None else "application/json"
        for attempt in range(a.retries + 1):
            try:
                req = urllib.request.Request(base + route, data=body, headers=headers, method=method)
                with urllib.request.urlopen(req, timeout=a.timeout) as r:
                    text = r.read()
                    return r.status, json.loads(text) if text.strip() else {}
            except urllib.error.HTTPError as e:
                text = e.read().decode("utf-8", "replace")
                # Only complete's Busy conflict is automatically retryable.
                busy = e.code == 409 and route.endswith("/complete") and "busy" in text.lower()
                if not (busy or e.code in (429, 500, 502, 503, 504)) or attempt == a.retries:
                    raise ApiError(e.code, text)
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
                if attempt == a.retries:
                    raise
            print("Request interrupted/busy; retrying...", file=sys.stderr)
            time.sleep(a.retry_delay)

    saved = json.loads(session.read_text()) if session.exists() else None
    if saved and saved.get("url") != base:
        raise ValueError("saved session belongs to another URL; use --session")
    if a.cancel:
        if not saved:
            raise ValueError("no saved session to cancel")
        request("DELETE", "/uploads/" + urllib.parse.quote(str(saved["id"]), safe=""))
        session.unlink()
        print("Upload cancelled")
        return
    st = archive.stat()
    identity = {"path": str(archive), "size": st.st_size, "mtime_ns": st.st_mtime_ns}
    if saved and (saved.get("file") != identity or saved.get("name") != a.name):
        raise ValueError("ZIP or role name changed; use another --session or explicitly cancel")
    digest = None
    if a.sha256:
        with archive.open("rb") as f:
            digest = hashlib.file_digest(f, "sha256").hexdigest()
        if archive.stat().st_mtime_ns != st.st_mtime_ns or archive.stat().st_size != st.st_size:
            raise ValueError("ZIP changed during hashing")
        if saved and saved.get("sha256") != digest:
            raise ValueError("saved session was created without this hash; use another --session")
    if not saved:
        payload = {"filename": archive.name, "total_size": st.st_size}
        if digest:
            payload["sha256"] = digest
        _, info = request("POST", "/uploads", payload)
        uid = info.get("id", info.get("upload_id"))
        if uid is None:
            raise ValueError("create response has no id/upload_id: %r" % info)
        saved = {"url": base, "id": uid, "name": a.name, "file": identity, "sha256": digest}
        tmp = session.with_name(session.name + ".tmp")
        with tmp.open("w") as f:
            json.dump(saved, f, indent=2)
            f.flush()
            os.fsync(f.fileno())
        tmp.replace(session)
    route = "/uploads/" + urllib.parse.quote(str(saved["id"]), safe="")

    def state(info):
        return str(info.get("state", info.get("status", ""))).lower()

    def terminal(info):
        s = state(info)
        if s in ("failed", "cancelled", "canceled", "expired"):
            raise RuntimeError("upload %s: %s" % (s, info.get("error", info)))
        return s in ("succeeded", "completed", "complete", "done", "imported")

    _, info = request("GET", route)
    if not terminal(info) and state(info) not in ("queued", "running", "completing", "importing", "processing"):
        missing = info.get("missing", info.get("missing_chunks"))
        if not isinstance(missing, list):
            raise ValueError("GET upload must contain missing or missing_chunks array: %r" % info)
        count = (st.st_size + CHUNK - 1) // CHUNK
        with archive.open("rb") as f:
            for index in missing:
                if type(index) is not int or not 0 <= index < count:
                    raise ValueError("invalid missing chunk index: %r" % index)
                now = archive.stat()
                if now.st_size != st.st_size or now.st_mtime_ns != st.st_mtime_ns:
                    raise ValueError("ZIP changed during upload")
                f.seek(index * CHUNK)
                data = f.read(min(CHUNK, st.st_size - index * CHUNK))
                if len(data) != min(CHUNK, st.st_size - index * CHUNK):
                    raise ValueError("ZIP truncated during upload")
                request("PUT", route + "/chunks/" + str(index), raw=data)
                print("Uploaded chunk %d/%d" % (index + 1, count), flush=True)
        code, info = request("POST", route + "/complete", {"name": a.name})
        if code != 202 and not terminal(info):
            # Synchronous 200/201 completion is also accepted.
            if code in (200, 201) and not state(info):
                print(json.dumps(info, ensure_ascii=False))
                session.unlink(missing_ok=True)
                return
    while not terminal(info):
        time.sleep(a.retry_delay)
        _, info = request("GET", route)
    session.unlink(missing_ok=True)
    print(json.dumps(info, ensure_ascii=False))

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("Interrupted; saved session retained. Re-run to resume; --cancel deletes it.", file=sys.stderr)
        sys.exit(130)
    except Exception as exc:
        print("Error: %s\nSaved session retained when available; re-run to resume." % exc, file=sys.stderr)
        sys.exit(1)
