#!/usr/bin/env python3
"""Host-side release updater. See update-launcher.md. Default: report latest local tag, no TV changes."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import io
import tarfile

PACKAGE = "com.gathin.tvhome"
TOOLS = Path(__file__).resolve().parent


def run(args, **kwargs):
    return subprocess.check_output([str(a) for a in args], text=True, **kwargs).strip()


def release_tag(value):
    if not re.fullmatch(r"v\d+\.\d+\.\d+", value):
        raise ValueError("expected a stable release tag such as v2.7.0")
    return value


def latest(checkout):
    tags = run(["git", "-C", checkout, "tag", "--list"]).splitlines()
    stable = [tag for tag in tags if re.fullmatch(r"v\d+\.\d+\.\d+", tag)]
    if not stable:
        raise ValueError("no stable release tags in local checkout; fetch tags yourself first")
    return max(stable, key=lambda tag: tuple(map(int, tag[1:].split("."))))


def local_commit(checkout, tag):
    return run(["git", "-C", checkout, "rev-parse", "--verify", "refs/tags/" + tag + "^{commit}"])


def export_source(checkout, commit, destination):
    # Archive the pinned local commit: no fetch, clone, checkout hooks or working-tree edits.
    archive = subprocess.check_output(["git", "-C", str(checkout), "archive", "--format=tar", commit])
    destination.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as files:
        for member in files:
            relative = Path(member.name)
            if relative.is_absolute() or ".." in relative.parts or not (member.isdir() or member.isfile()):
                raise ValueError("unsupported path or link in release archive")
            target = destination / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with files.extractfile(member) as src:
                    target.write_bytes(src.read())
                target.chmod(member.mode & 0o777)


def apk_info(aapt, apk):
    badging = run([aapt, "dump", "badging", apk])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or not sdk:
        raise ValueError("cannot inspect APK package/version/minSdk")
    permissions = run([aapt, "dump", "permissions", apk])
    if re.search(r"name='android.permission.INTERNET'", permissions):
        raise ValueError("refusing an APK with INTERNET permission")
    return package[1], int(package[2]), int(sdk[1])


def certificates(apksigner, apk):
    output = run([apksigner, "verify", "--print-certs", apk])
    values = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)$", output, re.M)
    if not values:
        raise ValueError("cannot verify APK signing certificate")
    return sorted(v.lower() for v in values)


def validate_update(candidate, installed, sdk, candidate_keys, installed_keys):
    if candidate[0] != PACKAGE or installed[0] != PACKAGE:
        raise ValueError("this tool updates only an already installed com.gathin.tvhome")
    if candidate[1] < installed[1]:
        raise ValueError("release is older than the installed version; no downgrade attempted")
    if sdk < candidate[2]:
        raise ValueError("TV Android version is below the APK minSdk")
    if candidate_keys != installed_keys:
        raise ValueError("signing key differs: use the original build key; never uninstall to work around it")


def remote_apk(output):
    paths = [line[8:] for line in output.splitlines() if line.startswith("package:")]
    if len(paths) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9_~=/+.-]+\.apk", paths[0]) or ".." in paths[0]:
        raise ValueError("expected one installed base APK; split APK installs are not supported")
    return paths[0]


def install(dev, apk, backup, info, signer, aapt):
    installed_path = remote_apk(dev.shell("pm path " + PACKAGE))
    previous = backup / "previous.apk"
    dev.pull(installed_path, str(previous), transport_timeout_s=300, read_timeout_s=300)
    old = apk_info(aapt, previous)
    sdk = int(dev.shell("getprop ro.build.version.sdk").strip())
    validate_update(info, old, sdk, certificates(signer, apk), certificates(signer, previous))
    # Data and preferences stay in place. Back up external config when it exists; do not rewrite it.
    config = "/sdcard/Android/data/" + PACKAGE + "/files/config.json"
    config_exists = dev.shell("test -f " + config + " && echo yes").strip() == "yes"
    if config_exists:
        dev.pull(config, str(backup / "config.json"))
    before = {
        "accessibility": dev.shell("settings get secure enabled_accessibility_services").strip(),
        "screensaver": dev.shell("settings get secure screensaver_components").strip(),
    }
    (backup / "settings-before.json").write_text(json.dumps(before, indent=2), encoding="utf-8")
    remote = "/data/local/tmp/cleanyourtv-release-update.apk"
    env = dict(os.environ)
    # Existing uploader handles old adbd payload limits and verifies the transfer before renaming it.
    subprocess.run([sys.executable, "-I", str(TOOLS / "b64push.py"), str(apk), remote], env=env, check=True)
    try:
        result = dev.shell("pm install -r " + remote, read_timeout_s=180).strip()
        if "Success" not in result.splitlines():
            raise RuntimeError("install refused; no uninstall/reset attempted: " + result)
    finally:
        dev.shell("rm -f " + remote)
    current = dev.shell("dumpsys package " + PACKAGE)
    match = re.search(r"versionCode=(\d+)", current)
    if not match or int(match[1]) != info[1]:
        raise RuntimeError("installation version could not be verified; backup retained")
    if config_exists:
        dev.pull(config, str(backup / "config-after.json"))
        if (backup / "config.json").read_bytes() != (backup / "config-after.json").read_bytes():
            raise RuntimeError("external configuration changed; review saved copies before continuing")
    after = {
        "accessibility": dev.shell("settings get secure enabled_accessibility_services").strip(),
        "screensaver": dev.shell("settings get secure screensaver_components").strip(),
    }
    if after != before:
        print("Settings changed during install; compare with settings-before.json. No settings were rewritten.")
    print("Installed versionCode", info[1], "without clearing data or changing Home. Backup:", backup)
    print("Press Home and test your physical remote helpers. Close debugging when finished.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=release_tag, help="pin a stable tag already present in the local checkout")
    parser.add_argument("--install", action="store_true", help="build on this computer, back up and update the existing TV app")
    parser.add_argument("--work-dir", type=Path, help="new, empty output directory (source, APK and private backup)")
    parser.add_argument("--patch", type=Path, action="append", default=[], help="trusted local customization patch; conflicts stop before installation")
    parser.add_argument("--checkout", type=Path, default=TOOLS.parent, help="local source checkout (default: this repository)")
    args = parser.parse_args(argv)
    checkout = args.checkout.resolve()
    tag = args.release or latest(checkout)
    commit = local_commit(checkout, tag)
    print("Release:", tag)
    if not args.install:
        print("Read-only check. Use --install to build and update. No TV connection was made.")
        return 0
    if not os.environ.get("TV_HOST"):
        parser.error("--install needs TV_HOST")
    if not os.environ.get("JAVA_HOME") or not (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")):
        parser.error("set JAVA_HOME (JDK 17) and ANDROID_HOME (SDK 34/build-tools 34.0.0)")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    suffix = ".bat" if os.name == "nt" else ""
    aapt = sdk / "build-tools/34.0.0" / ("aapt.exe" if os.name == "nt" else "aapt")
    signer = sdk / "build-tools/34.0.0" / ("apksigner" + suffix)
    if not aapt.is_file() or not signer.is_file():
        parser.error("Android build-tools 34.0.0 are required")
    workspace = args.work_dir or Path.home() / ".cleanyourtv/updates" / datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    workspace = workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=False, mode=0o700)
    source = workspace / "source"
    export_source(checkout, commit, source)
    for patch in args.patch:
        subprocess.run(["git", "-C", str(source), "apply", "--no-index", "--check", str(patch.resolve())], check=True)
        subprocess.run(["git", "-C", str(source), "apply", "--no-index", str(patch.resolve())], check=True)
    launcher = source / "launcher"
    # Invoke the cached distribution directly: gradlew could download it before honoring --offline.
    properties = (launcher / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    distribution = re.search(r"/((gradle-[0-9.]+)-(?:bin|all))\.zip", properties)
    if not distribution:
        raise ValueError("unsupported Gradle wrapper distribution")
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    executables = list((gradle_home / "wrapper/dists" / distribution[1]).glob(
        "*/" + distribution[2] + "/bin/" + ("gradle.bat" if os.name == "nt" else "gradle")))
    if len(executables) != 1:
        raise ValueError("cache the release's Gradle distribution separately before running this offline tool")
    subprocess.run([str(executables[0]), "--offline", "--no-daemon", "-p", str(launcher), "testReleaseUnitTest", "lintRelease", "assembleRelease"], check=True)
    apk = launcher / "app/build/outputs/apk/release/app-release.apk"
    info = apk_info(aapt, apk)
    (workspace / "build.json").write_text(json.dumps({"release": tag, "commit": commit, "patch_sha256": [hashlib.sha256(p.read_bytes()).hexdigest() for p in args.patch], "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}, indent=2), encoding="utf-8")
    backup = workspace / "backup"
    backup.mkdir(mode=0o700)
    sys.path.insert(0, str(TOOLS))
    import tv
    dev = tv.connect()
    try:
        install(dev, apk, backup, info, signer, aapt)
    finally:
        dev.close()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
