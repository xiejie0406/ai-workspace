"""真实文本切分/向量排序的离线算法测试，不代表 embedding Provider 或知识任务已接通。"""
import json
import tempfile
import unittest
from pathlib import Path

from knowledge.index_store import DerivedIndexStore
from knowledge.processor import extract, split
from worker.errors import WorkerError


class KnowledgeAlgorithmsTest(unittest.TestCase):
    def test_html_extract_excludes_script_and_has_source_offsets(self):
        text = extract(b"<h1>Header</h1><script>secret-script</script><p>A &amp; B</p>", "text/html")
        self.assertIn("A & B", text)
        self.assertNotIn("secret-script", text)
        chunks = split(text + "\n" + "Chinese:" * 12, "document-1", "hash-1", size=32, overlap=4)
        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertEqual(chunk.text, (text + "\n" + "Chinese:" * 12)[chunk.start:chunk.end])
            self.assertEqual(chunk.document_hash, "hash-1")

    def test_binary_unsupported_files_and_invalid_cutting_refused(self):
        for content, mime in ((b"a\x00b", "text/plain"), (b"%PDF", "application/pdf"), (b"\xff", "text/plain")):
            with self.subTest(mime=mime), self.assertRaises(WorkerError):
                extract(content, mime)
        with self.assertRaises(WorkerError):
            split("text", "doc", "hash", size=20, overlap=20)

    def test_real_cosine_sorting_filters_retracted_document_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DerivedIndexStore(Path(directory))
            chunks = split("first text", "doc-1", "hash-1") + split("second text", "doc-2", "hash-2")
            manifest = store.write("index-1", "version-1", chunks, [[1, 0], [.8, .6]], "embedding-version-1")
            results = store.search("index-1", "version-1", [1, 0], allowed_document_versions={"doc-1": "hash-1", "doc-2": "hash-2"}, expected_hash=manifest["hash"])
            self.assertEqual([value["document_id"] for value in results], ["doc-1", "doc-2"])
            allowed = store.search("index-1", "version-1", [1, 0], allowed_document_versions={"doc-2": "hash-2"})
            self.assertEqual([value["document_id"] for value in allowed], ["doc-2"])
            self.assertEqual(store.search("index-1", "version-1", [1, 0], allowed_document_versions={"doc-1": "new-hash"}), [])

    def test_immutable_index_version_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DerivedIndexStore(Path(directory))
            chunks = split("original text", "doc-1", "hash-1")
            first = store.write("index", "v1", chunks, [[1, 0]], "model-v1")
            self.assertEqual(first, store.write("index", "v1", chunks, [[1, 0]], "model-v1"))
            with self.assertRaises(WorkerError):
                store.write("index", "v1", chunks, [[0, 1]], "model-v1")

    def test_traversal_invalid_vectors_and_corrupt_index_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DerivedIndexStore(Path(directory))
            chunks = split("original text", "doc-1", "hash-1")
            for index, vectors in (("../escape", [[1, 0]]), ("index", [[0, 0]]), ("index", [[float("nan"), 1]])):
                with self.assertRaises(WorkerError):
                    store.write(index, "v1", chunks, vectors, "model-v1")
            manifest = store.write("index", "v1", chunks, [[1, 0]], "model-v1")
            path = Path(directory) / "index--v1.json"
            body = json.loads(path.read_text())
            body["vectors"] = []
            path.write_text(json.dumps(body))
            with self.assertRaises(WorkerError):
                store.search("index", "v1", [1, 0], allowed_document_versions={"doc-1": "hash-1"}, expected_hash=manifest["hash"])
            with self.assertRaises(WorkerError):
                store.search("index", "v1", [1, 0], allowed_document_versions={"doc-1": "hash-1"})


if __name__ == "__main__":
    unittest.main()
