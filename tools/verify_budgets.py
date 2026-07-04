#!/usr/bin/env python3
"""Hard packaging budgets; exits non-zero before oversized content ships."""
import os, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
assets=ROOT/'app/src/main/assets'
asset_bytes=sum(p.stat().st_size for p in assets.rglob('*') if p.is_file())
mb=asset_bytes/1024/1024
print(f'assets: {mb:.1f} MB')
if mb>150: raise SystemExit('asset hard limit exceeded (150 MB)')
if mb>120: print('warning: assets exceed 120 MB')
apk=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
baseline=float(os.environ.get('APK_BASELINE_MB','0') or 0)
if apk.exists():
    apk_mb=apk.stat().st_size/1024/1024
    print(f'apk: {apk_mb:.1f} MB')
    if baseline and apk_mb>baseline*1.10: raise SystemExit('APK grew more than 10% over baseline')
