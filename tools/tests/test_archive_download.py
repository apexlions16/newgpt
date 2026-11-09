"""Regression tests for G-Flix's authorized archive pipeline (no network)."""
import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gflix.archive_download import ArchiveQueue, archive_item, file_base, md5_file, require_public_https
from gflix.catalog import live_stream
from gflix.yayin import Sources, live_catalog


class ArchivePipelineTest(unittest.TestCase):
    def test_public_source_validation(self):
        self.assertEqual(require_public_https("https://example.org/video.m3u8"), "https://example.org/video.m3u8")
        for value in ("http://example.org", "https://localhost/x", "https://127.0.0.1/", "https://192.168.1.1/v",
                      "file:///etc/passwd", "https://10.0.0.1/video", "https://test.local/"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                require_public_https(value)

    def test_archive_item_and_names(self):
        self.assertEqual(archive_item("gflix-test", "movie-example", None), "gflix-test-movie-example")
        self.assertEqual(archive_item("gflix-test", "series-example", 2), "gflix-test-series-example-s02")
        self.assertEqual(file_base("series-example", 2, 7), "s02e07")
        self.assertEqual(file_base("movie-example", None, None), "example")
        with self.assertRaises(ValueError):
            archive_item("../secret", "movie-example", None)

    def test_queue_is_deduplicated_and_persistent(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            args = dict(enabled=True, access="fake", secret="fake", prefix="gflix-test",
                        resolve_media=lambda x: None, completed=lambda *a: None, log=lambda *a: None)
            q = ArchiveQueue(base, **args)
            jid = q.enqueue("movie-example", "https://example.org/video", "Example")
            self.assertEqual(jid, q.enqueue("movie-example", "https://example.org/video", "Example"))
            self.assertEqual(len(q.jobs), 1)
            self.assertEqual(q.status(jid)["state"], "queued")
            again = ArchiveQueue(base, **args)
            self.assertEqual(again.status(jid)["state"], "queued")
            with self.assertRaises(ValueError):
                again.enqueue("movie-example", "http://localhost", "Bad")
            self.assertEqual(len(again.jobs), 1)

    def test_disabled_queue_cannot_publish(self):
        with tempfile.TemporaryDirectory() as tmp:
            q = ArchiveQueue(Path(tmp), enabled=False, access="fake", secret="fake", prefix="gflix-test",
                             resolve_media=lambda x: None, completed=lambda *a: None)
            with self.assertRaises(RuntimeError):
                q.enqueue("movie-example", "https://example.org/video", "Example")

    def test_job_completes_only_after_callback(self):
        with tempfile.TemporaryDirectory() as tmp:
            finished = []
            q = ArchiveQueue(Path(tmp), enabled=True, access="fake", secret="fake", prefix="gflix-test",
                             resolve_media=lambda x: None, completed=lambda *a: None, log=lambda *a: None)
            jid = q.enqueue("movie-example", "https://example.org/film", "Film")
            def fake_process(key, job):
                finished.append(job["title"])
            q._process = fake_process
            q.start()
            for _ in range(50):
                if q.status(jid)["state"] == "done":
                    break
                time.sleep(.05)
            q.stop()
            self.assertEqual(finished, ["Film"])
            self.assertEqual(q.status(jid)["state"], "done")

    def test_catalog_retains_verified_archive_media(self):
        direct = {"direct": {"video": {"parts": ["https://archive.org/download/x/a.mkv"], "sizes": [9]},
                             "audio": [], "videoAudio": ["tr"]}, "subtitles": []}
        self.assertEqual(live_stream(direct), direct)
        with tempfile.TemporaryDirectory() as tmp:
            sources = Sources(Path(tmp) / "sources.json")
            sources.set("movie-sample", "https://example.org/play")
            catalog = {"titles": [{"id": "movie-sample", "type": "movie", "stream": dict(direct)}]}
            result = live_catalog(catalog, sources)
            self.assertEqual(result["titles"][0]["stream"], direct)

    def test_md5(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "test"
            f.write_bytes(b"abc")
            self.assertEqual(md5_file(f), "900150983cd24fb0d6963f7d28e17f72")


if __name__ == "__main__":
    unittest.main()
