"""Assert-based self-check for Local Dream Image (stdlib only).

Run: python3 standalone_image/selfcheck.py
"""
import base64
import io
import json
import sys
import threading
import unittest
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server  # noqa: E402

PNG = b"\x89PNG\r\n\x1a\n" + b"fake"


class CoreTests(unittest.TestCase):
    def test_catalog(self):
        self.assertEqual(len(server.CATALOG), 2)
        for item in server.CATALOG:
            self.assertTrue(item["url"].startswith("https://huggingface.co/"))
            self.assertTrue(item["url"].endswith(".gguf"))

    def test_payload_validation(self):
        body = server._generation_payload({"prompt": "a cat", "size": 256})
        self.assertEqual(body["width"], 256)
        self.assertEqual(body["sample_params"]["sample_steps"], 10)
        with self.assertRaises(ValueError):
            server._generation_payload({"prompt": ""})
        with self.assertRaises(ValueError):
            server._generation_payload({"prompt": "x", "size": 999})

    def test_generate_job(self):
        done = {"id": "j", "status": "completed", "started": 1, "completed": 3,
                "progress": {"stage": "sampling"},
                "result": {"images": [{"b64_json": "QUJD"}]}}
        responses = [(202, {"id": "j"}),
                     (200, {"status": "generating", "progress": {"stage": "sampling"}}),
                     (200, done)]
        seen = []
        with patch.object(server, "_request", side_effect=responses), \
             patch.object(server, "set_job"), patch.object(server.time, "sleep"):
            result = server.generate({"prompt": "x"}, on_progress=seen.append)
        self.assertEqual(result["b64"], "QUJD")
        self.assertEqual(result["ms"], 2)
        self.assertEqual(seen[0]["stage"], "sampling")

    def test_unknown_model(self):
        with self.assertRaises(ValueError):
            server._catalog_entry("evil")

    def test_search_sanitised(self):
        payload = json.dumps([{"id": "owner/repo", "downloads": 1,
                               "siblings": [{"rfilename": "sd-Q8_0.gguf"}]}]).encode()
        with patch.object(server.urllib.request, "urlopen",
                          return_value=io.BytesIO(payload)) as call:
            rows = server.search_hf("sd<script>")
        self.assertIn("search=sdscript", call.call_args[0][0].full_url)
        self.assertEqual(rows[0]["file"], "sd-Q8_0.gguf")


class HttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.port = cls.httpd.server_address[1]
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()

    def _get(self, path):
        with urllib.request.urlopen(
                "http://127.0.0.1:%d%s" % (self.port, path), timeout=5) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")

    def test_health_and_models(self):
        status, body = self._get("/api/health")
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["success"])
        status, body = self._get("/api/models")
        self.assertEqual(status, 200)
        self.assertEqual(len(json.loads(body)["data"]["models"]), 2)

    def test_webapp_files(self):
        for path in ("/", "/style.css", "/app.js"):
            status, body = self._get(path)
            self.assertEqual(status, 200, path)
            self.assertTrue(body)

    def test_bad_image_id(self):
        status, body = self._get("/api/image/not-a-uuid.png")
        self.assertEqual(status, 200)
        self.assertFalse(json.loads(body)["success"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
