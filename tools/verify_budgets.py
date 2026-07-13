#!/usr/bin/env python3
"""Hard packaging budgets; exits non-zero before oversized content ships."""
import argparse, os, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
assets=ROOT/'app/src/main/assets'
asset_bytes=sum(p.stat().st_size for p in assets.rglob('*') if p.is_file())
mb=asset_bytes/1024/1024
print(f'assets: {mb:.1f} MB')
if mb>150: raise SystemExit('asset hard limit exceeded (150 MB)')
if mb>120: print('warning: assets exceed 120 MB')
parser = argparse.ArgumentParser()
parser.add_argument('--apk', type=Path, help='APK to enforce the size limit against')
args = parser.parse_args()
apk = args.apk or next(
    (candidate for candidate in (
        ROOT/'app/build/outputs/apk/release/app-release.apk',
        ROOT/'app/build/outputs/apk/debug/app-debug.apk',
    ) if candidate.exists()),
    None,
)
baseline=float(os.environ.get('APK_BASELINE_MB','0') or 0)
if apk is not None and apk.exists():
    apk_mb=apk.stat().st_size/1024/1024
    print(f'apk ({apk}): {apk_mb:.1f} MB')
    # Keep the local default aligned with the rolling/release CI gate. Debug
    # APKs carry Compose tooling and are larger than the production artifact;
    # callers can still tighten this with APK_MAX_MB for a stricter channel.
    max_mb=float(os.environ.get('APK_MAX_MB', '60'))
    if apk_mb > max_mb: raise SystemExit(f'APK exceeds absolute limit ({max_mb:.1f} MB)')
    if baseline and apk_mb>baseline*1.10: raise SystemExit('APK grew more than 10% over baseline')
else:
    raise SystemExit('no APK found; build the artifact before checking its budget')
