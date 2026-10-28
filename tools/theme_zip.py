#!/usr/bin/env python3
"""Package, inspect and safely unpack portable cleanyourtv theme archives."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import sys
import tempfile
import zipfile
import zlib


MAX_ARCHIVE = 128 * 1024 * 1024
MAX_METADATA = 1024 * 1024
MAX_MEMBERS = 128
MAX_EXPANDED = 128 * 1024 * 1024
MAX_SMALL = 64 * 1024
MAX_IMAGE = 10 * 1024 * 1024
MAX_SCREENSHOT = 300 * 1024
MAX_WALLPAPERS = 100
CHUNK = 64 * 1024
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
IMAGE_RE = re.compile(r"^wallpapers/[A-Za-z0-9_.-]+\.(?:jpg|jpeg|png|webp)$")
DRIVE_RE = re.compile(r"^[A-Za-z]:")
HOST_FILES = {"screenshot.jpg", "README.md", "CREDITS.md", "LICENSE", "LICENSE.txt"}
ALLOWED_FILES = {"config.json", "wallpaper.jpg", "wallpapers/captions.txt"} | HOST_FILES
WINDOWS_RESERVED = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)),
                    *(f"LPT{i}" for i in range(1, 10))}


class ThemeZipError(Exception):
    pass


def _reject_constant(value):
    raise ValueError("NaN and Infinity are not JSON")


def _object_no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key: " + key)
        result[key] = value
    return result


def validate_config(data):
    if len(data) > MAX_SMALL:
        raise ThemeZipError("config.json exceeds 64 KiB")
    try:
        value = json.loads(data.decode("utf-8"), parse_constant=_reject_constant,
                           object_pairs_hook=_object_no_duplicates)
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError, RecursionError) as exc:
        raise ThemeZipError("config.json must be UTF-8 JSON without duplicate keys or NaN/Infinity: " + str(exc))
    if not isinstance(value, dict):
        raise ThemeZipError("config.json must contain a JSON object")


def validate_utf8(name, data):
    if len(data) > MAX_SMALL:
        raise ThemeZipError(f"{name} exceeds 64 KiB")
    try:
        data.decode("utf-8")
    except (UnicodeDecodeError, RecursionError) as exc:
        raise ThemeZipError(f"{name} must be UTF-8: {exc}")


def canonical_name(name):
    if not isinstance(name, str) or not name or "\x00" in name:
        raise ThemeZipError("member name is empty or contains NUL")
    if "\\" in name or name.startswith("/") or DRIVE_RE.match(name):
        raise ThemeZipError(f"unsafe member path: {name!r}")
    parts = name.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ThemeZipError(f"non-canonical member path: {name!r}")
    return name


def classify(name, is_dir=False):
    if is_dir:
        if name == "wallpapers/":
            return "directory"
        raise ThemeZipError(f"unsupported directory in archive: {name}")
    if name == "config.json":
        return "config"
    if name == "wallpaper.jpg":
        return "image"
    if IMAGE_RE.fullmatch(name):
        stem = Path(name).name.split(".", 1)[0].rstrip(" ").upper()
        if stem in WINDOWS_RESERVED:
            raise ThemeZipError(f"wallpaper filename is reserved on Windows: {name}")
        return "image"
    if name == "wallpapers/captions.txt":
        return "captions"
    if name == "screenshot.jpg":
        return "screenshot"
    if name in {"README.md", "CREDITS.md", "LICENSE", "LICENSE.txt"}:
        return "text"
    raise ThemeZipError(f"unsupported file in archive: {name}; allowed files are config.json, wallpaper.jpg, "
                        "wallpapers/<name>.jpg|jpeg|png|webp, wallpapers/captions.txt, screenshot.jpg and "
                        "README.md/CREDITS.md/LICENSE/LICENSE.txt")


def _is_special(info):
    mode = (info.external_attr >> 16) & 0o177777
    return mode and stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)


def member_chunks(archive, info):
    """Check the entire stored/deflate stream, without ZipExtFile's size truncation."""
    try:
        # Let zipfile check the local name, flags and overlapping member offsets.
        with archive.open(info):
            offset = archive.fp.tell()
        archive.fp.seek(info.header_offset)
        header = archive.fp.read(30)
        if (len(header) != 30 or header[:4] != b"PK\x03\x04"
                or struct.unpack_from("<HH", header, 6) != (info.flag_bits, info.compress_type)):
            raise ThemeZipError(f"inconsistent local header: {info.filename}")
        archive.fp.seek(offset)
        remaining, actual, crc = info.compress_size, 0, 0
        decoder = zlib.decompressobj(-15) if info.compress_type == zipfile.ZIP_DEFLATED else None
        if decoder is None and remaining != info.file_size:
            raise ThemeZipError(f"stored size mismatch: {info.filename}")
        while remaining:
            compressed = archive.fp.read(min(CHUNK, remaining))
            if not compressed:
                raise ThemeZipError(f"truncated compressed member: {info.filename}")
            remaining -= len(compressed)
            while compressed:
                if decoder is None:
                    block, compressed = compressed, b""
                else:
                    block = decoder.decompress(compressed, CHUNK)
                    compressed = decoder.unconsumed_tail
                    if decoder.unused_data:
                        raise ThemeZipError(f"trailing compressed data: {info.filename}")
                actual += len(block)
                if actual > info.file_size:
                    raise ThemeZipError(f"expanded size mismatch: {info.filename}")
                crc = zlib.crc32(block, crc)
                yield block
        # max_length can leave output buffered even after all compressed input was consumed.
        # Drain with the same bound; flush() does not impose an output-memory limit.
        while decoder is not None and not decoder.eof:
            block = decoder.decompress(b"", CHUNK)
            if not block:
                break
            actual += len(block)
            if actual > info.file_size:
                raise ThemeZipError(f"expanded size mismatch: {info.filename}")
            crc = zlib.crc32(block, crc)
            yield block
        if decoder is not None and not decoder.eof:
            raise ThemeZipError(f"incomplete deflate stream: {info.filename}")
        if actual != info.file_size or crc != info.CRC:
            raise ThemeZipError(f"size or CRC mismatch: {info.filename}")
    except (OSError, RuntimeError, EOFError, zipfile.BadZipFile, zlib.error) as exc:
        raise ThemeZipError(f"cannot fully validate {info.filename}: {exc}") from exc


def validate_open_archive(archive):
    """Read every payload to EOF (including CRC) and return safe file records."""
    try:
        infos = archive.infolist()
        if len(infos) > MAX_MEMBERS:
            raise ThemeZipError("archive has more than 128 members")
        names, folds, records, total, wallpapers = set(), set(), [], 0, 0
        config_seen = False
        for info in infos:
            original = getattr(info, "orig_filename", info.filename)
            if "\x00" in original:
                raise ThemeZipError("member name contains NUL")
            raw = info.filename
            is_dir = raw.endswith("/")
            if is_dir:
                if raw != "wallpapers/":
                    raise ThemeZipError(f"unsupported directory in archive: {raw}")
            else:
                canonical_name(raw)
            if info.flag_bits & 1:
                raise ThemeZipError(f"encrypted member is not supported: {raw}")
            if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                raise ThemeZipError(f"unsupported ZIP compression for {raw}")
            if _is_special(info):
                raise ThemeZipError(f"symlink or special member is not allowed: {raw}")
            if raw in names or raw.casefold() in folds:
                raise ThemeZipError(f"duplicate or case-colliding member: {raw}")
            names.add(raw); folds.add(raw.casefold())
            kind = classify(raw, is_dir)
            if kind == "directory" and info.file_size:
                raise ThemeZipError(f"directory entry carries data: {raw}")
            if info.file_size > MAX_EXPANDED:
                raise ThemeZipError(f"member is too large: {raw}")
            if kind in ("config", "captions", "text") and info.file_size > MAX_SMALL:
                raise ThemeZipError(f"{raw} exceeds 64 KiB")
            if kind == "image" and info.file_size > MAX_IMAGE:
                raise ThemeZipError(f"{raw} exceeds 10 MiB")
            if kind == "screenshot" and info.file_size > MAX_SCREENSHOT:
                raise ThemeZipError("screenshot.jpg exceeds 300 KiB")
            if kind == "image" and raw.startswith("wallpapers/"):
                wallpapers += 1
            if kind == "config":
                config_seen = True
            records.append((info, raw, kind))
        if not config_seen:
            raise ThemeZipError("archive is missing root config.json")
        if wallpapers > MAX_WALLPAPERS:
            raise ThemeZipError("archive has more than 100 wallpapers")
        payloads = {}
        for info, name, kind in records:
            data = bytearray() if kind in ("config", "captions", "text") else None
            for block in member_chunks(archive, info):
                total += len(block)
                if total > MAX_EXPANDED:
                    raise ThemeZipError("archive expands beyond 128 MiB")
                if data is not None:
                    data.extend(block)
            if data is not None:
                payloads[name] = bytes(data)
        validate_config(payloads["config.json"])
        if "wallpapers/captions.txt" in payloads:
            validate_utf8("wallpapers/captions.txt", payloads["wallpapers/captions.txt"])
        for name in HOST_FILES & payloads.keys():
            validate_utf8(name, payloads[name])
        return records
    except (zipfile.BadZipFile, RuntimeError, EOFError, zlib.error) as exc:
        raise ThemeZipError(f"not a readable ZIP archive: {exc}")


def check_metadata_budget(source):
    """Bound central-directory allocation before ZipFile parses attacker-controlled metadata."""
    size = os.fstat(source.fileno()).st_size
    if size > MAX_ARCHIVE:
        raise ThemeZipError("archive exceeds 128 MiB")
    source.seek(max(0, size - 65557))
    tail = source.read(65557)
    marker = tail.rfind(b"PK\x05\x06")
    if marker < 0 or marker + 22 > len(tail):
        raise ThemeZipError("not a readable ZIP archive: missing end record")
    disk, central_disk, disk_count, count, central_size, central_offset, comment_size = struct.unpack_from(
        "<4H2IH", tail, marker + 4)
    if disk or central_disk or disk_count != count or count == 0xFFFF:
        raise ThemeZipError("multi-disk and ZIP64 archives are not supported")
    if count > MAX_MEMBERS:
        raise ThemeZipError("archive has more than 128 members")
    if central_size > MAX_METADATA or central_offset + central_size > size:
        raise ThemeZipError("ZIP central directory exceeds metadata budget")
    if (marker + 22 + comment_size != len(tail)
            or central_offset + central_size != max(0, size - 65557) + marker):
        raise ThemeZipError("not a readable ZIP archive: inconsistent end record")
    # Count records ourselves before ZipFile allocates ZipInfo objects. Reject NUL before its
    # filename normalization, and do not trust a forged small count in the end record.
    source.seek(central_offset)
    end = central_offset + central_size
    actual_count = 0
    while source.tell() < end:
        fixed = source.read(46)
        if len(fixed) != 46 or fixed[:4] != b"PK\x01\x02":
            raise ThemeZipError("malformed ZIP central directory")
        name_len, extra_len, comment_len = struct.unpack_from("<HHH", fixed, 28)
        if source.tell() + name_len + extra_len + comment_len > end:
            raise ThemeZipError("truncated ZIP central-directory record")
        if b"\x00" in source.read(name_len):
            raise ThemeZipError("member name contains NUL")
        source.seek(extra_len + comment_len, 1)
        actual_count += 1
        if actual_count > MAX_MEMBERS:
            raise ThemeZipError("archive has more than 128 members")
    if actual_count != count:
        raise ThemeZipError("inconsistent ZIP member count")
    source.seek(0)


@contextmanager
def open_archive(path):
    try:
        with Path(path).open("rb") as source:
            check_metadata_budget(source)
            with zipfile.ZipFile(source) as archive:
                yield archive
    except (OSError, UnicodeError, NotImplementedError, zipfile.BadZipFile) as exc:
        raise ThemeZipError(f"not a readable ZIP archive: {exc}")


def validate_archive(path):
    with open_archive(path) as archive:
        return validate_open_archive(archive)


def source_files(source):
    source = Path(source)
    if source.is_symlink() or not source.is_dir():
        raise ThemeZipError(f"source is not a directory: {source}")
    files, ignored = [], []
    def walk_error(exc):
        raise ThemeZipError(f"cannot read theme directory: {exc}") from exc

    for root, dirs, names in os.walk(source, followlinks=False, onerror=walk_error):
        root_path = Path(root)
        for directory in dirs:
            relative_dir = (root_path / directory).relative_to(source).as_posix()
            if (root_path / directory).is_symlink():
                raise ThemeZipError(f"source symlink is not allowed: {root_path / directory}")
            if relative_dir != "wallpapers":
                raise ThemeZipError(f"unsupported source directory: {relative_dir}")
        for filename in names:
            file_path = root_path / filename
            relative = file_path.relative_to(source).as_posix()
            if file_path.is_symlink() or not file_path.is_file():
                raise ThemeZipError(f"source symlink or non-regular file is not allowed: {relative}")
            if relative in ("manifest.json",) or relative.startswith(("banners/", "icons/", "fonts/")):
                raise ThemeZipError(f"fork-only asset is not supported: {relative}")
            if relative in ALLOWED_FILES or IMAGE_RE.fullmatch(relative):
                files.append((relative, file_path))
            elif "/" not in relative and relative in {"make_wallpaper.py", "make_wallpapers.py"}:
                ignored.append(relative)
            else:
                raise ThemeZipError(f"unsupported source file: {relative}; remove it or use a supported theme file")
    if not any(name == "config.json" for name, _ in files):
        raise ThemeZipError("source is missing root config.json")
    return sorted(files), sorted(ignored)


def validate_source(files):
    image_count = total = 0
    for name, path in files:
        size = path.stat().st_size
        total += size
        if total > MAX_EXPANDED:
            raise ThemeZipError("source expands beyond 128 MiB")
        if name == "config.json":
            if size > MAX_SMALL:
                raise ThemeZipError("config.json exceeds 64 KiB")
            with path.open("rb") as source:
                validate_config(source.read(MAX_SMALL + 1))
        elif name == "wallpapers/captions.txt":
            if size > MAX_SMALL:
                raise ThemeZipError(f"{name} exceeds 64 KiB")
            with path.open("rb") as source:
                validate_utf8(name, source.read(MAX_SMALL + 1))
        elif name == "screenshot.jpg" and size > MAX_SCREENSHOT:
            raise ThemeZipError("screenshot.jpg exceeds 300 KiB")
        elif name in HOST_FILES - {"screenshot.jpg"}:
            if size > MAX_SMALL:
                raise ThemeZipError(f"{name} exceeds 64 KiB")
            with path.open("rb") as source:
                validate_utf8(name, source.read(MAX_SMALL + 1))
        elif (name == "wallpaper.jpg" or name.startswith("wallpapers/")) and size > MAX_IMAGE:
            raise ThemeZipError(f"{name} exceeds 10 MiB")
        if name.startswith("wallpapers/") and IMAGE_RE.fullmatch(name):
            image_count += 1
    if image_count > MAX_WALLPAPERS:
        raise ThemeZipError("source has more than 100 wallpapers")
    if len(files) > MAX_MEMBERS:
        raise ThemeZipError("source has more than 128 files")


def zip_info(name):
    info = zipfile.ZipInfo(name, FIXED_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (stat.S_IFREG | 0o644) << 16
    info.create_system = 3
    return info


def pack(source, output):
    files, ignored = source_files(source)
    validate_source(files)
    output = Path(output)
    if output.exists():
        raise ThemeZipError(f"output already exists: {output}")
    created = False
    try:
        with open(output, "xb") as handle:
            created = True
            with zipfile.ZipFile(handle, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9,
                                 strict_timestamps=True) as archive:
                for name, path in files:
                    with path.open("rb") as source_file, archive.open(zip_info(name), "w") as target:
                        shutil.copyfileobj(source_file, target, CHUNK)
            if handle.tell() > MAX_ARCHIVE:
                raise ThemeZipError("packed archive exceeds 128 MiB")
        validate_archive(output)
    except Exception:
        if created:
            try:
                output.unlink()
            except FileNotFoundError:
                pass
            except OSError as exc:
                raise ThemeZipError(f"pack failed and could not remove incomplete output {output}: {exc}")
        raise
    print(f"packed {len(files)} files: {output}")
    if ignored:
        print("ignored host generator files: " + ", ".join(ignored))


def inspect(path):
    records = validate_archive(path)
    print(f"valid theme archive: {path}")
    for info, name, kind in records:
        print(f"{name}\t{info.file_size}\t{kind}")


def unpack(path, destination):
    destination = Path(destination)
    if destination.exists():
        raise ThemeZipError(f"destination already exists: {destination}")
    parent = destination.parent
    if not parent.is_dir():
        raise ThemeZipError(f"destination parent does not exist: {parent}")
    with open_archive(path) as archive:
        records = validate_open_archive(archive)
        stage = Path(tempfile.mkdtemp(prefix=f".{destination.name}.theme-zip-", dir=parent))
        destination_owned = False
        try:
            for info, name, kind in records:
                target = stage / name
                if kind == "directory":
                    target.mkdir(exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as sink:
                    for block in member_chunks(archive, info):
                        sink.write(block)
                os.chmod(target, 0o644)
            # Reserve the name only after a complete staged extraction. Moving only into our own
            # exclusive directory avoids overwriting an interloper on every supported host platform.
            try:
                destination.mkdir(mode=0o700)
                destination_owned = True
            except FileExistsError:
                raise ThemeZipError(f"destination already exists: {destination}")
            for child in stage.iterdir():
                os.replace(child, destination / child.name)
            stage.rmdir()
        except Exception:
            try:
                if stage.exists():
                    shutil.rmtree(stage)
                if destination_owned and destination.exists():
                    shutil.rmtree(destination)
            except OSError as cleanup:
                raise ThemeZipError(f"unpack failed and could not remove private staging directory {stage}: {cleanup}")
            raise
    print(f"unpacked {len(records)} files: {destination}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pack_parser = commands.add_parser("pack", help="pack SOURCE into a new OUTPUT.zip")
    pack_parser.add_argument("source"); pack_parser.add_argument("output")
    inspect_parser = commands.add_parser("inspect", help="fully validate INPUT.zip")
    inspect_parser.add_argument("input")
    unpack_parser = commands.add_parser("unpack", help="validate INPUT.zip then publish NEW_DEST")
    unpack_parser.add_argument("input"); unpack_parser.add_argument("destination")
    args = parser.parse_args(argv)
    try:
        if args.command == "pack": pack(args.source, args.output)
        elif args.command == "inspect": inspect(args.input)
        else: unpack(args.input, args.destination)
    except ThemeZipError as exc:
        print("error: " + str(exc), file=sys.stderr)
        return 2
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile) as exc:
        print("error: " + str(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
