#!/usr/bin/env python3
"""Validate the installable release APK before it is published."""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import zipfile
from pathlib import Path


def tool(name: str) -> str:
    direct = shutil.which(name)
    if direct:
        return direct
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    candidates = sorted(sdk.glob(f"build-tools/*/{name}*"), reverse=True)
    if candidates:
        return str(candidates[0])
    raise SystemExit(f"{name} was not found in PATH or Android SDK build-tools")


def run(command: list[str]) -> str:
    try:
        return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as exc:
        raise SystemExit(f"command failed: {' '.join(command)}\n{exc.output}") from exc


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--package", default="com.sibirskyspeak")
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--certificate-sha256", required=True)
    args = parser.parse_args()
    if not args.apk.is_file() or args.apk.stat().st_size == 0:
        raise SystemExit(f"missing or empty APK: {args.apk}")

    badging = run([tool("aapt2"), "dump", "badging", str(args.apk)])
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package:
        raise SystemExit("aapt2 did not report package metadata")
    actual_package, actual_code, actual_name = package.groups()
    if (actual_package, actual_name, int(actual_code)) != (args.package, args.version_name, args.version_code):
        raise SystemExit(
            f"unexpected APK metadata: package={actual_package}, versionCode={actual_code}, versionName={actual_name}"
        )
    if "debuggable" in badging:
        raise SystemExit("release APK is debuggable")
    with zipfile.ZipFile(args.apk) as archive:
        if "assets/curriculum_contract.json" not in archive.namelist():
            raise SystemExit("release APK is missing curriculum_contract.json")
    permissions = run([tool("aapt2"), "dump", "permissions", str(args.apk)])
    for required in ("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"):
        if required not in permissions:
            raise SystemExit(f"release APK is missing required permission: {required}")
    if "android.permission.INTERNET" in permissions:
        raise SystemExit("release APK requests INTERNET; SibirskySpeak is required to remain offline-first")

    certs = run([tool("apksigner"), "verify", "--verbose", "--print-certs", str(args.apk)])
    if "Verified using v1 scheme" not in certs and "Verified using v2 scheme" not in certs:
        raise SystemExit("apksigner did not verify a supported signature scheme")
    expected = args.certificate_sha256.replace(":", "").lower()
    actual = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)", certs)
    if not actual or actual.group(1).replace(":", "").lower() != expected:
        raise SystemExit("release certificate fingerprint does not match RELEASE_CERT_SHA256")
    print(f"validated {args.apk}: {actual_package} {actual_name} ({actual_code}), production certificate")


if __name__ == "__main__":
    main()
