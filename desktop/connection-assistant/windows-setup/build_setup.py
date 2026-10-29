#!/usr/bin/env python3
"""Stage and compile the PadNote Connection Assistant Windows x64 installer."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
STAGE = ROOT / "build" / "stage"
CACHE = ROOT / "build" / "downloads"
DOWNLOAD_CACHE = CACHE
PYTHON_VERSION = "3.13.15"
ARTIFACTS = [
    (f"https://www.python.org/ftp/python/{PYTHON_VERSION}/python-{PYTHON_VERSION}-embed-amd64.zip",
     "python-embed.zip", "d1f04d990aee1253d8569e8e5104e30fa9f5fa830899f14843448872d936a2cf"),
    ("https://files.pythonhosted.org/packages/99/89/87ef49ffe383ef4e147d27b7bf2088fb0b54ea409dd87b5a89442e5828a5/cryptography-50.0.1-cp39-abi3-win_amd64.whl",
     "cryptography.whl", "55d16b1ef3ee0958d893a977b19777887e546c9954ea81b200c3301a864013f2"),
    ("https://files.pythonhosted.org/packages/60/a6/8b149b2c3f2e11aaa1618ef64500b45f50f22c57a977a4dff1aff1f91042/cffi-2.1.1-cp313-cp313-win_amd64.whl",
     "cffi.whl", "1aa5645c30469b09530c4ebca77ebf8f17618293c58f8549cb1a543a50236e7d"),
    ("https://files.pythonhosted.org/packages/0c/c3/44f3fbbfa403ea2a7c779186dc20772604442dde72947e7d01069cbe98e3/pycparser-3.0-py3-none-any.whl",
     "pycparser.whl", "b727414169a36b7d524c1c3e31839a521725078d7b2ff038656844266160a992"),
]

def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def fetch(url: str, name: str, expected: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    remote_name = url.rsplit("/", 1)[-1]
    for directory in (DOWNLOAD_CACHE, CACHE):
        for filename in (name, remote_name):
            candidate = directory / filename
            if candidate.is_file() and digest(candidate) == expected:
                return candidate
    target = CACHE / name
    temp = target.with_suffix(target.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "PadNote-Connection-Assistant-Packager/1"})
    with urllib.request.urlopen(request, timeout=60) as response, temp.open("wb") as output:
        if response.status != 200:
            raise RuntimeError(f"Unexpected HTTP status for {name}: {response.status}")
        shutil.copyfileobj(response, output)
    actual = digest(temp)
    if actual != expected:
        temp.unlink(missing_ok=True)
        raise RuntimeError(f"SHA-256 mismatch for {name}: {actual}")
    temp.replace(target)
    return target

def safe_extract(archive: zipfile.ZipFile, destination: Path) -> None:
    base = destination.resolve()
    for member in archive.infolist():
        path = PurePosixPath(member.filename)
        if (path.is_absolute() or "\\" in member.filename or "\x00" in member.filename
                or any(part in ("..", "") for part in path.parts)
                or path.parts and ":" in path.parts[0]):
            raise RuntimeError(f"Unsafe archive path: {member.filename}")
        target = (destination / Path(*path.parts)).resolve()
        if target != base and base not in target.parents:
            raise RuntimeError(f"Unsafe archive path: {member.filename}")
    archive.extractall(destination)

def install_wheel(archive: Path, target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as wheel:
        for item in wheel.infolist():
            name = item.filename
            wheel_path = PurePosixPath(name)
            if (wheel_path.is_absolute() or "\\" in name or "\x00" in name
                    or any(part in ("..", "") for part in wheel_path.parts)
                    or wheel_path.parts and ":" in wheel_path.parts[0]):
                raise RuntimeError(f"Unsafe wheel path: {name}")
            if name.endswith("/"):
                continue
            parts = wheel_path.parts
            if parts[0].endswith(".data"):
                if len(parts) < 3 or parts[1] not in ("purelib", "platlib"):
                    continue
                relative = Path(*parts[2:])
            else:
                relative = Path(*parts)
            dest = (target / relative).resolve()
            if target.resolve() not in dest.parents:
                raise RuntimeError(f"Unsafe wheel path: {name}")
            dest.parent.mkdir(parents=True, exist_ok=True)
            with wheel.open(item) as source, dest.open("wb") as output:
                shutil.copyfileobj(source, output)

def write_uninstall_macro() -> None:
    files = sorted(path.relative_to(STAGE).as_posix() for path in STAGE.rglob("*") if path.is_file())
    for generated in ("PACKAGE-INVENTORY.json", "installer-payload.nsh"):
        if generated not in files:
            files.append(generated)
    directories = sorted((path.relative_to(STAGE).as_posix() for path in STAGE.rglob("*")
                         if path.is_dir()), key=lambda value: (value.count("/"), value), reverse=True)
    lines = ["!macro UNINSTALL_PAYLOAD\n"]
    for relative in files:
        lines.append(f'  Delete "$INSTDIR\\{relative.replace("/", chr(92))}"\n')
    for relative in directories:
        lines.append(f'  RMDir "$INSTDIR\\{relative.replace("/", chr(92))}"\n')
    lines.append("!macroend\n")
    (STAGE / "installer-payload.nsh").write_text("".join(lines), encoding="utf-8")

def prepare(assistant_root: Path, project_root: Path) -> None:
    if STAGE.exists():
        shutil.rmtree(STAGE)
    STAGE.mkdir(parents=True)
    runtime = STAGE / "runtime"
    runtime.mkdir()
    python_zip = fetch(*ARTIFACTS[0])
    with zipfile.ZipFile(python_zip) as archive:
        safe_extract(archive, runtime)
    pth = next(runtime.glob("python*._pth"))
    pth.write_text("python313.zip\n.\n..\nLib\\site-packages\nimport site\n", encoding="ascii")
    site_packages = runtime / "Lib" / "site-packages"
    for artifact in ARTIFACTS[1:]:
        install_wheel(fetch(*artifact), site_packages)
    sources = [(ROOT / "launcher.py", STAGE / "launcher.py"),
               (ROOT / "launcher_support.py", STAGE / "launcher_support.py"),
               (ROOT / "verify_windows_startup.py", STAGE / "verify_windows_startup.py"),
               (ROOT / "PadNote.ico", STAGE / "PadNote.ico"),
               (assistant_root / "start.py", STAGE / "start.py"),
               (assistant_root / "padnote_assistant", STAGE / "padnote_assistant"),
               (assistant_root / "vendor", STAGE / "vendor")]
    for source, destination in sources:
        if source.is_dir():
            shutil.copytree(source, destination, ignore=shutil.ignore_patterns(
                "__pycache__", "*.pyc", "tests", ".env", ".env.*", ".npmrc"))
        else:
            shutil.copy2(source, destination)
    for name in ("README-WINDOWS.md", "THIRD_PARTY_NOTICES.md"):
        shutil.copy2(ROOT / name, STAGE / name)
    shutil.copy2(assistant_root / "requirements-win.lock", STAGE / "requirements-win.lock")
    for packaged, source_name in (("PROJECT_LICENSE.md", "LICENSE"),
                                  ("PROJECT_LICENSING.md", "LICENSING.md"),
                                  ("PROJECT_THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")):
        source = project_root / source_name
        if not source.is_file():
            source = ROOT / packaged
        shutil.copy2(source, STAGE / packaged)
    licenses = STAGE / "licenses"
    licenses.mkdir(exist_ok=True)
    for license_file in runtime.glob("LICENSE*"):
        if license_file.is_file():
            shutil.copy2(license_file, licenses / ("CPYTHON-" + license_file.name))
    write_uninstall_macro()
    inventory = []
    for path in sorted(STAGE.rglob("*")):
        if path.is_file():
            inventory.append({"path": path.relative_to(STAGE).as_posix(), "size": path.stat().st_size,
                              "sha256": digest(path)})
    (STAGE / "PACKAGE-INVENTORY.json").write_text(json.dumps({"files": inventory}, indent=2) + "\n",
                                                 encoding="utf-8")

def compile_installer(output: Path, explicit_compiler: Path | None = None) -> None:
    compiler = str(explicit_compiler) if explicit_compiler else shutil.which("makensis")
    if not compiler:
        raise RuntimeError("makensis is required. Install the official NSIS compiler or pass --stage-only.")
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([compiler, f"-DSTAGE={STAGE}", f"-DOUTFILE={output.resolve()}", str(ROOT / "installer.nsi")],
                   check=True)

def main() -> int:
    global DOWNLOAD_CACHE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage-only", action="store_true", help="prepare files without invoking NSIS")
    parser.add_argument("--download-cache", type=Path, help="read verified pinned archives from this directory first")
    parser.add_argument("--makensis", type=Path, help="use this NSIS compiler executable")
    parser.add_argument("--assistant-root", type=Path, default=ROOT,
                        help="connection-assistant source directory (for integration, pass its parent)")
    parser.add_argument("--project-root", type=Path, default=ROOT,
                        help="project root containing LICENSE, LICENSING.md, and THIRD_PARTY_NOTICES.md")
    parser.add_argument("--output", type=Path, default=ROOT / "build" / "PadNote-Connection-Assistant-0.18.0-beta.32-Setup-x64.exe")
    args = parser.parse_args()
    if args.download_cache:
        DOWNLOAD_CACHE = args.download_cache.resolve()
    prepare(args.assistant_root.resolve(), args.project_root.resolve())
    if not args.stage_only:
        compile_installer(args.output, args.makensis)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
