"""Every TV list starts with a header comment that names the set, and uses Unix line endings.

tools/disable.sh strips a BOM and CRLF defensively (#208), but a list committed with Windows line endings
still diffs badly and confuses `git blame`; the header line is what the examples index and the README card
are built from, so a list without one is a list nobody can find.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
EXAMPLES = ROOT / "examples"


class ListHeadersTest(unittest.TestCase):
    def test_header_names_the_set(self):
        for path in sorted(EXAMPLES.glob("*/debloat.txt")):
            with self.subTest(folder=path.parent.name):
                first = path.read_text(encoding="utf-8").split("\n", 1)[0]
                self.assertTrue(first.startswith("#"), "first line must be a comment")
                self.assertGreaterEqual(len(first.lstrip("# ").strip()), 8, "header should name the TV model")

    def test_unix_line_endings(self):
        for path in sorted(EXAMPLES.glob("*/debloat.txt")):
            with self.subTest(folder=path.parent.name):
                self.assertNotIn(b"\r\n", path.read_bytes(), "use LF line endings")


if __name__ == "__main__":
    unittest.main()
