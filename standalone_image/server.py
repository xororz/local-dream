"""Local Dream Image — standalone, localhost-only image generation webapp.

Runs on the phone inside Debian/Termux. Unrelated to the Android APK: the
inference engine is the portable stable-diffusion.cpp (the upstream project
that local-webdream forks for its DiT work), driven through its native sdcpp
HTTP API. Python stays stdlib-only; no pip, no npm, no build step here.

Run:  python3 standalone_image/server.py
Open: http://127.0.0.1:8090/
"""
import base64
import hashlib
import json
import os
import queue
import re
import shutil
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WEB = ROOT / "web"
MODELS = ROOT / "models"
RESULTS = ROOT / "results"
STATE = ROOT / "state"

PORT = int(os.environ.get("LD_WEB_PORT", "8090"))
ENGINE_HOST = "127.0.0.1"
ENGINE_PORT = int(os.environ.get("LD_ENGINE_PORT", "8092"))
ENGINE_BASE = "http://%s:%d" % (ENGINE_HOST, ENGINE_PORT)
START_TIMEOUT = 90.0
TERMINAL = ("completed", "failed", "cancelled")

CATALOG = [
    {
        "id": "sd15-q8",
        "name": "SD 1.5 Q8 (GGUF)",
        "file": "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
        "url": "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF"
               "/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
        "source": "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF",
        "size_mb": 1100,
        "min_ram_mb": 2500,
        "license": "CreativeML OpenRAIL-M",
        "size": 256,
        "steps": 10,
        "sha256": None,
        "notes": "Best quality/size on phone CPU. Start here.",
    },
    {
        "id": "sd15-q4km",
        "name": "SD 1.5 Q4_K_M (GGUF)",
        "file": "stable-diffusion-v1-5-pruned-emaonly-Q4_K_M.gguf",
        "url": "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF"
               "/resolve/main/stable-diffusion-v1-5-pruned-emaonly-Q4_K_M.gguf",
        "source": "https://huggingface.co/second-state/stable-diffusion-v1-5-GGUF",
        "size_mb": 700,
        "min_ram_mb": 1800,
        "license": "CreativeML OpenRAIL-M",
        "size": 256,
        "steps": 10,
        "sha256": None,
        "notes": "Smaller fallback when free RAM is low.",
    },
]
ALLOWED_SIZES = (128, 256, 384, 512)

_lock = threading.RLock()
_gen_lock = threading.Lock()
_dl_lock = threading.Lock()


# ---------------------------------------------------------------------------
# helpers

def _now():
    return datetime.now(timezone.utc).isoformat()


def _write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def _read(path, default=None):
    try:
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        pass
    return default if default is not None else {}


def _engine_state():
    return _read(STATE / "engine.json",
                 {"running": False, "pid": None, "model_id": None, "job_id": None})


def _save_engine(data):
    data["updated"] = _now()
    _write(STATE / "engine.json", data)


def _pid_alive(pid):
    if not pid:
        return False
    try:
        os.kill(int(pid), 0)
        return True
    except (OSError, ValueError):
        return False


def _free_ram_mb():
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    except Exception:
        pass
    return None


def _get(path, timeout=2.0):
    req = urllib.request.Request(
        ENGINE_BASE + path, headers={"User-Agent": "LocalDreamImage/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8", "replace"))


def _request(method, path, payload=None, timeout=15.0):
    data = None
    headers = {"User-Agent": "LocalDreamImage/1.0"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(
        ENGINE_BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw.decode("utf-8", "replace"))
                                 if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            body = json.loads(raw)
            msg = body.get("error") or body.get("message") or raw
        except Exception:
            msg = raw
        raise ValueError("engine %s: %s" % (e.code, str(msg)[:300]))


def _catalog_entry(model_id):
    for entry in CATALOG:
        if entry["id"] == model_id:
            return dict(entry)
    raise ValueError("Unknown model id: %s" % model_id)


def _model_path(model_id):
    return MODELS / _catalog_entry(model_id)["file"]


def _find_engine():
    configured = os.environ.get("LD_ENGINE")
    if configured:
        return Path(configured)
    for path in (ROOT / "bin" / "sd-server",
                 Path.home() / ".local" / "opt" / "localdream-image" /
                 "bin" / "sd-server"):
        if path.is_file():
            return path
    found = shutil.which("sd-server")
    return Path(found) if found else None


# ---------------------------------------------------------------------------
# engine supervision

def engine_running():
    st = _engine_state()
    if not st.get("running") or not _pid_alive(st.get("pid")):
        return False
    try:
        status, _ = _get("/sdcpp/v1/capabilities", timeout=1.5)
        return 200 <= status < 300
    except Exception:
        return False


def engine_status():
    st = _engine_state()
    running = engine_running()
    return {"running": running,
            "port": ENGINE_PORT if running else None,
            "pid": st.get("pid") if running else None,
            "loaded_id": st.get("model_id") if running else None,
            "job_id": st.get("job_id") if running else None}


def model_list():
    running = engine_running()
    resident = _engine_state().get("model_id") if running else None
    out = []
    for entry in CATALOG:
        path = MODELS / entry["file"]
        installed = path.is_file()
        out.append({
            "id": entry["id"], "name": entry["name"],
            "installed": installed, "size_mb": entry["size_mb"],
            "license": entry["license"], "notes": entry["notes"],
            "source": entry["source"], "size": entry["size"],
            "steps": entry["steps"],
            "status": "loaded" if running and resident == entry["id"] else (
                "installed" if installed else "not_installed"),
        })
    return {"engine": engine_status(), "models": out}


def _kill(pid, timeout=5):
    try:
        os.kill(int(pid), signal.SIGTERM)
    except OSError:
        return
    end = time.time() + timeout
    while time.time() < end:
        if not _pid_alive(pid):
            return
        time.sleep(0.1)
    try:
        os.kill(int(pid), signal.SIGKILL)
    except OSError:
        pass


def _log_tail(n=30):
    try:
        return "\n".join((STATE / "engine.log").read_text(
            encoding="utf-8", errors="replace").splitlines()[-n:])
    except Exception:
        return ""


def load_model(model_id):
    entry = _catalog_entry(model_id)
    path = _model_path(model_id)
    if not path.is_file():
        raise ValueError("Model not installed: %s" % entry["file"])
    free = _free_ram_mb()
    need = entry["min_ram_mb"]
    if free is not None and free < need - 150:
        raise ValueError("Need %d MB free RAM, have %d MB" % (need, free))
    with _lock:
        st = _engine_state()
        if st.get("model_id") == model_id and engine_running():
            return {"status": "already_loaded", "model_id": model_id}
        if _pid_alive(st.get("pid")):
            _kill(int(st["pid"]))
            time.sleep(0.4)
        engine = _find_engine()
        if engine is None:
            raise ValueError("sd-server missing; run bin/install-engine.sh")
        argv = [str(engine), "--listen-ip", ENGINE_HOST,
                "--listen-port", str(ENGINE_PORT),
                "--model-dir", str(path.parent), "-m", str(path)]
        STATE.mkdir(parents=True, exist_ok=True)
        logf = open(str(STATE / "engine.log"), "ab", buffering=0)
        try:
            logf.write(("\n=== start %s ===\n" % _now()).encode())
            proc = subprocess.Popen(
                argv, stdin=subprocess.DEVNULL, stdout=logf,
                stderr=subprocess.STDOUT, start_new_session=True, cwd=str(ROOT))
        except Exception as e:
            raise ValueError("Cannot start engine: %s" % e)
        finally:
            logf.close()
        end = time.time() + START_TIMEOUT
        while time.time() < end:
            if proc.poll() is not None:
                raise ValueError("Engine exited: %s" % _log_tail()[-300:])
            try:
                status, payload = _get("/sdcpp/v1/models/status", timeout=1.5)
                if 200 <= status < 300 and payload.get("state") == "ready":
                    _save_engine({"running": True, "pid": proc.pid,
                                  "model_id": model_id, "job_id": None,
                                  "started": _now()})
                    return {"status": "loaded", "model_id": model_id,
                            "pid": proc.pid}
            except Exception:
                pass
            time.sleep(0.5)
        try:
            os.kill(proc.pid, signal.SIGKILL)
        except Exception:
            pass
        raise ValueError("Engine did not load the model: %s" % _log_tail()[-300:])


def unload_model(model_id=None):
    with _lock:
        st = _engine_state()
        resident = st.get("model_id")
        if model_id and resident and model_id != resident:
            return {"status": "not_loaded", "resident": resident}
        killed = False
        if _pid_alive(st.get("pid")):
            _kill(int(st["pid"]))
            killed = True
        time.sleep(0.2)
        _save_engine({"running": False, "pid": None, "model_id": None,
                      "job_id": None, "stopped": _now()})
        return {"status": "unloaded", "killed": killed}


def set_job(job_id):
    st = _engine_state()
    st["job_id"] = job_id
    _save_engine(st)


# ---------------------------------------------------------------------------
# generation adapter (only module section that knows sdcpp field names)

def _generation_payload(body):
    prompt = str(body.get("prompt") or "").strip()
    if not prompt:
        raise ValueError("Prompt is required")
    size = int(body.get("size") or 256)
    if size not in ALLOWED_SIZES:
        raise ValueError("Unsupported size")
    steps = int(body.get("steps") or 10)
    if steps < 1 or steps > 30:
        raise ValueError("Steps must be 1..30")
    seed = int(body.get("seed") if body.get("seed") is not None else -1)
    return {
        "prompt": prompt,
        "negative_prompt": str(body.get("negative_prompt") or ""),
        "width": size,
        "height": size,
        "seed": seed,
        "batch_count": 1,
        "preview": "none",
        "output_format": "png",
        "vae_tiling_params": {"enabled": size >= 512},
        "sample_params": {"sample_steps": steps, "guidance": {"txt_cfg": 7.0}},
    }


def generate(body, on_progress=None):
    payload = _generation_payload(body)
    status, submitted = _request("POST", "/sdcpp/v1/img_gen", payload)
    if status not in (200, 202) or not submitted.get("id"):
        raise ValueError("Engine refused the generation job")
    job_id = submitted["id"]
    set_job(job_id)
    end = time.time() + 3600.0
    try:
        while time.time() < end:
            _, job = _request("GET", "/sdcpp/v1/jobs/" + job_id)
            progress = job.get("progress") or {}
            if on_progress and progress:
                try:
                    on_progress(dict(progress))
                except Exception:
                    pass
            state = job.get("status")
            if state in TERMINAL:
                if state == "completed":
                    result = job.get("result") or {}
                    images = result.get("images") or []
                    if not images or not images[0].get("b64_json"):
                        raise ValueError("Engine returned no image")
                    return {"b64": images[0]["b64_json"],
                            "ms": int(job.get("completed") or 0)
                            - int(job.get("started") or 0)}
                if state == "cancelled":
                    raise ValueError("Generation cancelled")
                error = job.get("error") or {}
                raise ValueError(str(error.get("message") or "Generation failed"))
            time.sleep(0.5)
        raise ValueError("Generation timed out")
    finally:
        set_job(None)


def cancel_generation():
    job_id = _engine_state().get("job_id")
    if not job_id:
        return {"status": "no_job"}
    _, result = _request(
        "POST", "/sdcpp/v1/jobs/" + job_id + "/cancel", {})
    return result


# ---------------------------------------------------------------------------
# download + search

def _download_worker(entry):
    target = MODELS / entry["file"]
    part = target.with_suffix(target.suffix + ".part")
    state = {"id": entry["id"], "status": "downloading",
             "downloaded_mb": 0, "total_mb": 0, "percent": 0}
    _write(STATE / "download.json", state)
    try:
        MODELS.mkdir(parents=True, exist_ok=True)
        head = urllib.request.Request(entry["url"], method="HEAD",
                                      headers={"User-Agent": "LocalDreamImage/1.0"})
        with urllib.request.urlopen(head, timeout=20) as resp:
            total = int(resp.headers.get("Content-Length") or 0)
        if total and total > 6 * 1024 * 1024 * 1024:
            raise ValueError("Model exceeds 6 GB limit")
        state["total_mb"] = total // (1024 * 1024)
        req = urllib.request.Request(
            entry["url"], headers={"User-Agent": "LocalDreamImage/1.0"})
        done = 0
        last = 0.0
        with urllib.request.urlopen(req, timeout=30) as resp, open(str(part), "wb") as out:
            while True:
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                now = time.monotonic()
                if now - last > 0.5:
                    state["downloaded_mb"] = done // (1024 * 1024)
                    state["percent"] = int(done * 100 / total) if total else 0
                    _write(STATE / "download.json", state)
                    last = now
        if total and done != total:
            raise ValueError("Size mismatch (%d of %d)" % (done, total))
        if entry.get("sha256"):
            digest = hashlib.sha256()
            with open(str(part), "rb") as src:
                for block in iter(lambda: src.read(1024 * 1024), b""):
                    digest.update(block)
            if digest.hexdigest() != entry["sha256"].lower():
                raise ValueError("Checksum mismatch")
        part.replace(target)
        state.update({"status": "completed", "percent": 100})
    except Exception as e:
        try:
            if part.exists():
                part.unlink()
        except Exception:
            pass
        state.update({"status": "error", "error": str(e)})
    _write(STATE / "download.json", state)


def start_download(model_id):
    entry = _catalog_entry(model_id)
    if (MODELS / entry["file"]).is_file():
        return {"status": "already_installed"}
    current = _read(STATE / "download.json", {})
    if current.get("status") == "downloading":
        return {"status": "in_progress"}
    with _dl_lock:
        threading.Thread(target=_download_worker, args=(entry,),
                         daemon=True).start()
    return {"status": "started", "id": model_id}


def search_hf(query):
    clean = re.sub(r"[^A-Za-z0-9._\- ]", "", str(query or ""))[:64].strip()
    if not clean:
        raise ValueError("Enter a search term")
    url = ("https://huggingface.co/api/models?search="
           + urllib.parse.quote(clean) + "&limit=8&full=false")
    req = urllib.request.Request(url, headers={"User-Agent": "LocalDreamImage/1.0"})
    with urllib.request.urlopen(req, timeout=12) as resp:
        results = json.loads(resp.read().decode("utf-8", "replace"))
    out = []
    for item in results if isinstance(results, list) else []:
        repo = item.get("id")
        if not repo:
            continue
        files = [s.get("rfilename", "") for s in item.get("siblings", [])
                 if isinstance(s, dict)]
        ggufs = [f for f in files if f.lower().endswith(".gguf")]
        if not ggufs:
            continue
        chosen = next((f for f in ggufs if "q8" in f.lower()), None) or \
                 next((f for f in ggufs if "q4_k_m" in f.lower()), None) or ggufs[0]
        out.append({"repo": repo, "file": chosen,
                    "source": "https://huggingface.co/" + repo,
                    "downloads": item.get("downloads", 0)})
    return out


# ---------------------------------------------------------------------------
# HTTP server

def ok(data):
    return {"success": True, "data": data}


def fail(message):
    return {"success": False, "error": str(message)}


class Handler(BaseHTTPRequestHandler):
    server_version = "LocalDreamImage/1.0"

    def log_message(self, fmt, *args):
        pass

    def _json(self, payload, status=200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.end_headers()
        self.wfile.write(body)

    def _file(self, path):
        try:
            data = path.read_bytes()
        except Exception:
            return self._json(fail("Not found"), 404)
        ctype = "text/html; charset=utf-8"
        if path.suffix == ".css":
            ctype = "text/css; charset=utf-8"
        elif path.suffix == ".js":
            ctype = "text/javascript; charset=utf-8"
        elif path.suffix == ".png":
            ctype = "image/png"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            "script-src 'self'; connect-src 'self'")
        self.end_headers()
        self.wfile.write(data)

    def _host_ok(self):
        name = (self.headers.get("Host") or "").strip().lower().rsplit(":", 1)[0]
        return name in ("127.0.0.1", "localhost", "::1")

    def _origin_ok(self):
        origin = (self.headers.get("Origin") or "").strip().lower()
        if not origin:
            return True
        return urllib.parse.urlparse(origin).netloc == \
            (self.headers.get("Host") or "").strip().lower()

    def _body(self, limit=4096):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > limit:
            raise ValueError("Bad request")
        value = json.loads(self.rfile.read(length).decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Bad request")
        return value

    def do_POST(self):
        if not self._host_ok() or not self._origin_ok():
            return self._json(fail("Forbidden"), 403)
        path = urllib.parse.urlparse(self.path).path.rstrip("/")
        if path == "/api/generate":
            try:
                body = self._body()
                _generation_payload(body)
            except ValueError as e:
                return self._json(fail(e), 200)
            if not _gen_lock.acquire(blocking=False):
                return self._json(fail("Generation already running"), 200)
            events = queue.Queue()
            payload = body

            def worker():
                try:
                    def progress(p):
                        events.put({"t": "progress", "stage": p.get("stage", ""),
                                    "completed": p.get("completed"),
                                    "total": p.get("total"),
                                    "unit": p.get("unit")})

                    result = generate(payload, on_progress=progress)
                    raw = base64.b64decode(result["b64"])
                    if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
                        raise ValueError("Engine returned a non-PNG image")
                    image_id = uuid.uuid4().hex
                    (RESULTS / "images").mkdir(parents=True, exist_ok=True)
                    (RESULTS / "images" / (image_id + ".png")).write_bytes(raw)
                    events.put({"t": "done", "id": image_id,
                                "path": "/api/image/" + image_id + ".png",
                                "ms": result["ms"]})
                except Exception as e:
                    events.put({"t": "error", "e": str(e)})
                finally:
                    events.put(None)

            threading.Thread(target=worker, daemon=True).start()
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            try:
                while True:
                    event = events.get()
                    if event is None:
                        break
                    self.wfile.write(json.dumps(event).encode("utf-8") + b"\n")
                    self.wfile.flush()
            except Exception:
                pass
            finally:
                _gen_lock.release()
            return
        try:
            body = self._body(1024)
            if path == "/api/load":
                return self._json(ok(load_model(body.get("id"))))
            if path == "/api/unload":
                return self._json(ok(unload_model(body.get("id"))))
            if path == "/api/download":
                return self._json(ok(start_download(body.get("id"))))
            if path == "/api/cancel":
                return self._json(ok(cancel_generation()))
        except ValueError as e:
            return self._json(fail(e), 200)
        except Exception as e:
            return self._json(fail("Internal error: %s" % e), 500)
        return self._json(fail("Not found"), 404)

    def do_GET(self):
        if not self._host_ok():
            return self._json(fail("Forbidden"), 403)
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        try:
            if path == "/api/health":
                return self._json(ok({"status": "ok"}))
            if path == "/api/models":
                return self._json(ok(model_list()))
            if path == "/api/status":
                return self._json(ok(engine_status()))
            if path == "/api/download/status":
                return self._json(ok(_read(STATE / "download.json",
                                           {"status": "idle"})))
            if path == "/api/search":
                query = urllib.parse.parse_qs(parsed.query).get("q", [""])[0]
                return self._json(ok(search_hf(query)))
            if path.startswith("/api/image/"):
                raw = path[len("/api/image/"):]
                image_id = raw[:-4] if raw.endswith(".png") else raw
                if not re.fullmatch(r"[0-9a-f]{32}", image_id):
                    raise ValueError("Invalid image id")
                target = RESULTS / "images" / (image_id + ".png")
                if not target.is_file():
                    return self._json(fail("Image not found"), 404)
                return self._file(target)
        except ValueError as e:
            return self._json(fail(e), 200)
        except Exception as e:
            return self._json(fail("Internal error: %s" % e), 500)

        rel = parsed.path.lstrip("/")
        if path == "/" or path == "":
            rel = "index.html"
        if path.startswith("/web/"):
            rel = parsed.path.lstrip("/")
        target = (WEB / rel).resolve()
        try:
            target.relative_to(WEB.resolve())
        except ValueError:
            return self._json(fail("Not found"), 404)
        if not target.is_file():
            return self._json(fail("Not found"), 404)
        return self._file(target)


def main():
    print("Local Dream Image on http://127.0.0.1:%d/" % PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
