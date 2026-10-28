"""tools/manage.py reads the add-ons, themes and switches from the repo's own READMEs; check it finds them all, and
that every add-on has a way in: its own install script or the steps in addons/README.md."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import manage  # noqa: E402


class ManageTest(unittest.TestCase):
    def test_every_addon_folder_is_listed_with_its_package(self):
        listed = {a["id"]: a for a in manage.addons()}
        folders = {p.name for p in (ROOT / "addons").iterdir() if p.is_dir()}
        self.assertEqual(folders, set(listed), "addons/README.md rows and addons/ folders")
        for a in listed.values():
            with self.subTest(addon=a["id"]):
                self.assertTrue(a["package"], "applicationId found in app/build.gradle(.kts)")
                if a["script"]:
                    self.assertTrue((ROOT / "addons" / a["id"] / a["script"]).is_file())

    def test_every_theme_is_listed_with_its_screenshot(self):
        for t in manage.themes():
            with self.subTest(theme=t["id"]):
                self.assertTrue((ROOT / "themes" / t["id"] / "config.json").is_file())
                self.assertTrue(t["screenshot"] and Path(t["screenshot"]).is_file())

    def test_switches_are_the_documented_keys(self):
        keys = {s["id"] for s in manage.switches()}
        self.assertIn("columns", keys)
        self.assertIn("dock_apps", keys)
        self.assertGreater(len(keys), 40)

    def test_find_by_folder_or_name(self):
        self.assertEqual("weather", manage.find("Weather")["id"])
        self.assertEqual("theme", manage.find("aurora/")["kind"])
        self.assertEqual("switch", manage.find("columns")["kind"])
        self.assertIsNone(manage.find("no-such-thing"))

    def test_set_refuses_the_projects_own_themes(self):
        with self.assertRaises(SystemExit):
            manage.cmd_set("columns", "6", str(ROOT / "themes" / "aurora"))


if __name__ == "__main__":
    unittest.main()
