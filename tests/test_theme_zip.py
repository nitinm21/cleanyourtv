"""Host-only ZIP transport checks. Run with unittest discovery from the repository root."""
import importlib.util
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "tools" / "theme_zip.py"
spec = importlib.util.spec_from_file_location("theme_zip", TOOL)
theme_zip = importlib.util.module_from_spec(spec)
spec.loader.exec_module(theme_zip)


class ThemeZipTest(unittest.TestCase):
    def setUp(self):
        self.base = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.base, ignore_errors=True)

    def archive(self, name="theme.zip", entries=None, compression=zipfile.ZIP_DEFLATED):
        path = self.base / name
        entries = {"config.json": b'{"columns": 5}'} if entries is None else entries
        with zipfile.ZipFile(path, "w", compression=compression) as out:
            for filename, data in entries.items():
                out.writestr(filename, data)
        return path

    def error(self, callable_, message):
        with self.assertRaises(theme_zip.ThemeZipError) as caught:
            callable_()
        self.assertIn(message, str(caught.exception))

    def test_real_themes_round_trip_the_deployment_payload(self):
        for source in sorted(path for path in (ROOT / "themes").iterdir() if path.is_dir()):
            with self.subTest(theme=source.name):
                archive = self.base / (source.name + ".zip")
                destination = self.base / (source.name + "-out")
                theme_zip.pack(source, archive)
                theme_zip.unpack(archive, destination)
                for relative in ("config.json", "wallpaper.jpg", "wallpapers/captions.txt"):
                    original, unpacked = source / relative, destination / relative
                    self.assertEqual(original.exists(), unpacked.exists(), relative)
                    if original.exists():
                        self.assertEqual(original.read_bytes(), unpacked.read_bytes(), relative)
                for original in source.glob("wallpapers/*"):
                    if original.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
                        self.assertEqual(original.read_bytes(), (destination / "wallpapers" / original.name).read_bytes())

    def test_deterministic_pack_and_ruska_generator_is_reported(self):
        first, second = self.base / "a.zip", self.base / "b.zip"
        theme_zip.pack(ROOT / "themes" / "ruska", first)
        theme_zip.pack(ROOT / "themes" / "ruska", second)
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertNotIn("make_wallpaper.py", zipfile.ZipFile(first).namelist())

    def test_rejects_unsafe_names_duplicates_and_case_collisions(self):
        cases = [
            ({"../config.json": b"{}"}, "non-canonical"),
            ({"config.json": b"{}", "CONFIG.JSON": b"{}"}, "case-colliding"),
            ({"config.json": b"{}", "wallpapers/../x.jpg": b"x"}, "non-canonical"),
            ({"config.json": b"{}", "other/x.txt": b"x"}, "unsupported file"),
        ]
        for index, (entries, expected) in enumerate(cases):
            with self.subTest(entries=entries):
                self.error(lambda: theme_zip.validate_archive(self.archive(f"bad-{index}.zip", entries)), expected)
        nul = self.base / "nul.zip"
        with zipfile.ZipFile(nul, "w") as out:
            info = zipfile.ZipInfo("config.json_")
            out.writestr(info, b"{}")
        raw = nul.read_bytes().replace(b"config.json_", b"config.json\x00")
        nul.write_bytes(raw)
        self.error(lambda: theme_zip.validate_archive(nul), "NUL")

    def test_rejects_symlink_encryption_and_unsupported_compression(self):
        symlink = self.archive("symlink.zip")
        with zipfile.ZipFile(symlink, "a") as out:
            link = zipfile.ZipInfo("wallpaper.jpg")
            link.external_attr = (0o120777 << 16)
            out.writestr(link, b"target")
        self.error(lambda: theme_zip.validate_archive(symlink), "symlink or special")

        encrypted = self.archive("encrypted.zip")
        raw = bytearray(encrypted.read_bytes())
        for marker, offset in ((b"PK\x03\x04", 6), (b"PK\x01\x02", 8)):
            start = raw.index(marker)
            flags = int.from_bytes(raw[start + offset:start + offset + 2], "little") | 1
            raw[start + offset:start + offset + 2] = flags.to_bytes(2, "little")
        encrypted.write_bytes(raw)
        self.error(lambda: theme_zip.validate_archive(encrypted), "encrypted")

        bzip = self.archive("bzip.zip", compression=zipfile.ZIP_BZIP2)
        self.error(lambda: theme_zip.validate_archive(bzip), "unsupported ZIP compression")
        future = self.archive("future.zip")
        raw = bytearray(future.read_bytes())
        struct.pack_into("<H", raw, raw.index(b"PK\x01\x02") + 6, 99)
        future.write_bytes(raw)
        self.error(lambda: theme_zip.validate_archive(future), "not a readable ZIP")

    def test_crc_truncation_and_json_rules_are_checked(self):
        crc = self.archive("crc.zip", compression=zipfile.ZIP_STORED)
        with zipfile.ZipFile(crc) as archive:
            info = archive.getinfo("config.json")
        raw = bytearray(crc.read_bytes())
        offset = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
        raw[offset] ^= 1
        crc.write_bytes(raw)
        self.error(lambda: theme_zip.validate_archive(crc), "CRC")

        truncated = self.archive("truncated.zip")
        truncated.write_bytes(truncated.read_bytes()[:-12])
        self.error(lambda: theme_zip.validate_archive(truncated), "not a readable ZIP")
        for index, config in enumerate((b"[1]", b'{"x": NaN}', b'{"x": 1, "x": 2}', b'\xef\xbb\xbf{"x": 1}',
                                        b"[" * 2000 + b"0" + b"]" * 2000)):
            self.error(lambda config=config: theme_zip.validate_archive(self.archive(f"json-{index}.zip", {"config.json": config})),
                       "config.json")

    def test_metadata_budget_and_conventional_directory_entry(self):
        oversized = self.archive("metadata.zip")
        raw = bytearray(oversized.read_bytes())
        end = raw.rfind(b"PK\x05\x06")
        raw[end + 12:end + 16] = (theme_zip.MAX_METADATA + 1).to_bytes(4, "little")
        oversized.write_bytes(raw)
        self.error(lambda: theme_zip.validate_archive(oversized), "metadata budget")

        directory = self.base / "directory.zip"
        with zipfile.ZipFile(directory, "w", compression=zipfile.ZIP_DEFLATED) as out:
            entry = zipfile.ZipInfo("wallpapers/")
            entry.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(entry, b"")
            out.writestr("config.json", b"{}")
        destination = self.base / "directory-out"
        theme_zip.unpack(directory, destination)
        self.assertTrue((destination / "wallpapers").is_dir())

    def test_unpack_never_clobbers_or_publishes_partial_output(self):
        archive = self.archive()
        destination = self.base / "destination"
        destination.mkdir()
        (destination / "keep").write_text("keep")
        self.error(lambda: theme_zip.unpack(archive, destination), "already exists")
        self.assertEqual((destination / "keep").read_text(), "keep")
        bad = self.archive("bad.zip", {"config.json": b"{}", "bad.txt": b"no"})
        new_destination = self.base / "new-destination"
        self.error(lambda: theme_zip.unpack(bad, new_destination), "unsupported file")
        self.assertFalse(new_destination.exists())

    def test_lying_expanded_size_does_not_hide_payload_bytes(self):
        for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            with self.subTest(compression=compression):
                path = self.archive(f"lying-{compression}.zip",
                                    {"config.json": b"{}" + b"X" * 100000}, compression)
                raw = bytearray(path.read_bytes())
                central = raw.index(b"PK\x01\x02")
                for crc_at, size_at in ((14, 22), (central + 16, central + 24)):
                    struct.pack_into("<I", raw, crc_at, zlib.crc32(b"{}"))
                    struct.pack_into("<I", raw, size_at, 2)
                path.write_bytes(raw)
                destination = self.base / f"lying-{compression}"
                self.error(lambda: theme_zip.unpack(path, destination), "size mismatch")
                self.assertFalse(destination.exists())

    def test_streaming_limits_and_both_compression_methods(self):
        for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            path = self.archive(f"stream-{compression}.zip",
                                {"config.json": b"{}", "wallpaper.jpg": b"x" * 1000000}, compression)
            theme_zip.validate_archive(path)
            with mock.patch.object(theme_zip, "MAX_EXPANDED", 1000001):
                self.error(lambda: theme_zip.validate_archive(path), "expands beyond")
            with mock.patch.object(theme_zip, "MAX_IMAGE", 999999):
                self.error(lambda: theme_zip.validate_archive(path), "10 MiB")
        with mock.patch.object(theme_zip, "MAX_ARCHIVE", 1):
            self.error(lambda: theme_zip.validate_archive(path), "128 MiB")

    def test_deflate_drains_internal_output_at_chunk_boundaries(self):
        for level in (1, 6, 9):
            for size in (65535, 65536, 65537, 131073, 196609, 1048577):
                with self.subTest(level=level, size=size):
                    archive = self.base / f"boundary-{level}-{size}.zip"
                    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED,
                                         compresslevel=level) as output:
                        output.writestr("config.json", b"{}")
                        output.writestr("wallpaper.jpg", b"x" * size)
                    theme_zip.validate_archive(archive)

    def test_member_budget_is_checked_before_zipinfo_allocation(self):
        path = self.archive("many.zip", {"config.json": b"{}", "README.md": b"readme"})
        with mock.patch.object(theme_zip, "MAX_MEMBERS", 1), \
                mock.patch.object(theme_zip.zipfile, "ZipFile", side_effect=AssertionError("allocated")):
            self.error(lambda: theme_zip.validate_archive(path), "members")
            raw = bytearray(path.read_bytes())
            end = raw.rfind(b"PK\x05\x06")
            struct.pack_into("<HH", raw, end + 8, 1, 1)
            path.write_bytes(raw)
            self.error(lambda: theme_zip.validate_archive(path), "members")

    def test_racing_destination_is_kept_and_publication_failure_is_cleaned(self):
        archive = self.archive()
        destination = self.base / "racing"
        mkdir = Path.mkdir

        def race(path, *args, **kwargs):
            if path == destination:
                mkdir(path)
                raise FileExistsError("another caller created the directory")
            return mkdir(path, *args, **kwargs)

        with mock.patch.object(Path, "mkdir", race):
            self.error(lambda: theme_zip.unpack(archive, destination), "already exists")
        self.assertTrue(destination.is_dir())
        self.assertFalse(list(self.base.glob(".racing.theme-zip-*")))
        destination.rmdir()
        with mock.patch.object(theme_zip.os, "replace", side_effect=OSError("injected disk failure")):
            self.error(lambda: theme_zip.unpack(archive, destination), "injected disk failure")
        self.assertFalse(destination.exists())
        self.assertFalse(list(self.base.glob(".racing.theme-zip-*")))

    def test_unreadable_source_directory_is_not_silently_dropped(self):
        source = self.base / "unreadable"
        source.mkdir()
        (source / "config.json").write_text("{}")

        def failed_walk(*args, **kwargs):
            kwargs["onerror"](PermissionError("unreadable wallpapers"))
            return iter(())

        output = self.base / "unreadable.zip"
        with mock.patch.object(theme_zip.os, "walk", failed_walk):
            self.error(lambda: theme_zip.pack(source, output), "cannot read theme directory")
        self.assertFalse(output.exists())

    def test_cli_failure_is_nonzero_and_pack_preserves_existing_file(self):
        bad = self.archive("bad-json.zip", {"config.json": b"not JSON"})
        result = subprocess.run([sys.executable, str(TOOL), "inspect", str(bad)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("error:", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        existing = self.base / "existing.zip"
        existing.write_bytes(b"keep")
        self.error(lambda: theme_zip.pack(ROOT / "themes/default", existing), "already exists")
        self.assertEqual(existing.read_bytes(), b"keep")

    def test_source_rejects_symlinks_fork_assets_and_limits(self):
        source = self.base / "source"; source.mkdir()
        (source / "config.json").write_text("{}")
        (source / "manifest.json").write_text("{}")
        self.error(lambda: theme_zip.pack(source, self.base / "out.zip"), "fork-only")
        (source / "manifest.json").unlink()
        (source / "linked.jpg").symlink_to(source / "config.json")
        self.error(lambda: theme_zip.pack(source, self.base / "out.zip"), "symlink")
        (source / "linked.jpg").unlink()
        (source / "unsupported-empty-dir").mkdir()
        self.error(lambda: theme_zip.pack(source, self.base / "out.zip"), "unsupported source directory")

    def test_source_size_and_windows_wallpaper_names_are_rejected_before_reading(self):
        source = self.base / "large-source"; source.mkdir()
        (source / "config.json").write_bytes(b" " * (theme_zip.MAX_SMALL + 1))
        self.error(lambda: theme_zip.pack(source, self.base / "large.zip"), "64 KiB")
        (source / "config.json").write_text("{}")
        wallpapers = source / "wallpapers"; wallpapers.mkdir()
        (wallpapers / "CON.jpg").write_bytes(b"image")
        self.error(lambda: theme_zip.pack(source, self.base / "reserved.zip"), "reserved on Windows")
        for name in ("CON.foo.jpg", "AUX.any.png", "COM1.photo.webp"):
            path = self.archive(name + ".zip", {"config.json": b"{}", "wallpapers/" + name: b"x"})
            self.error(lambda: theme_zip.validate_archive(path), "reserved on Windows")


if __name__ == "__main__":
    unittest.main()
