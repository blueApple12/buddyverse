"""Makes every app icon from the logo: PC app (window, tray, installer .ico), web app (favicon, PWA icons), download
page and README. The Android launcher icon is vector layers drawn from the same shapes
(androidApp/src/main/res/drawable/ic_launcher_*.xml); `--android-png` replaces them with bitmaps of the PNG instead.

    python tools/set-logo.py                    # render branding/logo.svg -> branding/logo.png (Chrome or Edge), then all icons
    python tools/set-logo.py path/to/logo.png   # use a square PNG instead (copied to branding/logo.png), then all icons
    python tools/set-logo.py --android-png      # also make the Android launcher icon from branding/logo.png

Needs Pillow (`pip install pillow`). A PNG should be square, 1024 px or more, with a transparent or full-bleed background.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SVG = ROOT / "branding" / "logo.svg"
LOGO = ROOT / "branding" / "logo.png"
RES = ROOT / "androidApp" / "src" / "main" / "res"
WEB = ROOT / "shared" / "src" / "wasmJsMain" / "resources"
SITE = ROOT / "site"
BG = (10, 10, 16, 255)  # the logo's own background color (#0A0A10): maskable / full-bleed icons
BROWSERS = [r"C:\Program Files\Google\Chrome\Application\chrome.exe", r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
            r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
            "/usr/bin/google-chrome", "/usr/bin/chromium", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"]
LANCZOS = Image.Resampling.LANCZOS


def render_svg(size=1024):
    """branding/logo.svg -> branding/logo.png with a transparent background, through a headless Chrome/Edge."""
    browser = next((b for b in BROWSERS if os.path.isfile(b)), None) or shutil.which("chrome") or shutil.which("msedge")
    if not browser:
        raise SystemExit("no Chrome or Edge found to render branding/logo.svg; pass a PNG instead")
    tmp = Path(tempfile.mkdtemp(prefix="bv-logo-"))
    try:
        html = tmp / "r.html"
        html.write_text('<html><body style="margin:0;background:transparent">' + SVG.read_text(encoding="utf-8") + "</body></html>",
                        encoding="utf-8")
        out = tmp / "logo.png"
        subprocess.run([browser, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
                        f"--user-data-dir={tmp / 'profile'}", f"--window-size={size},{size}", f"--screenshot={out}",
                        html.resolve().as_uri()], capture_output=True, timeout=120)
        if not out.is_file():
            raise SystemExit("the browser did not render branding/logo.svg")
        shutil.copyfile(out, LOGO)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def full_bleed(img, size, scale=1.0):
    """[img] on the logo's background color, no transparent corners (maskable PWA icons, iPhone, Android bitmaps)."""
    bg = Image.new("RGBA", (size, size), BG)
    inner = img.resize((round(size * scale),) * 2, LANCZOS)
    o = (size - inner.width) // 2
    bg.alpha_composite(inner, (o, o))
    return bg


def main(argv):
    android_png = "--android-png" in argv
    args = [a for a in argv if not a.startswith("--")]
    LOGO.parent.mkdir(parents=True, exist_ok=True)
    if args:
        if Path(args[0]).resolve() != LOGO.resolve():
            shutil.copyfile(args[0], LOGO)
    elif SVG.is_file():
        render_svg()
    logo = Image.open(LOGO).convert("RGBA")
    if logo.width != logo.height:
        raise SystemExit(f"{LOGO} must be square (it is {logo.width}x{logo.height})")
    written = [LOGO]

    def save(img, path, **kw):
        path.parent.mkdir(parents=True, exist_ok=True)
        img.save(path, **kw)
        written.append(path)

    # PC app: window + tray icon, the EXE / installer / shortcut icon
    save(logo.resize((256, 256), LANCZOS), ROOT / "desktopApp" / "src" / "jvmMain" / "resources" / "icon.png")
    save(logo.resize((256, 256), LANCZOS), ROOT / "desktopApp" / "icon.ico",
         sizes=[(16, 16), (20, 20), (24, 24), (32, 32), (40, 40), (48, 48), (64, 64), (128, 128), (256, 256)])

    # Web app (PWA, served by the user's own PC): maskable icons keep the art inside the safe zone
    save(full_bleed(logo, 192, 0.9), WEB / "icon-192.png")
    save(full_bleed(logo, 512, 0.9), WEB / "icon-512.png")
    save(full_bleed(logo, 180).convert("RGB"), WEB / "apple-touch-icon.png")

    # Download page + README header
    save(logo.resize((256, 256), LANCZOS), SITE / "icon.png")
    save(full_bleed(logo, 180).convert("RGB"), SITE / "apple-touch-icon.png")
    save(logo.resize((64, 64), LANCZOS), SITE / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64)])
    if SVG.is_file() and not args:
        shutil.copyfile(SVG, SITE / "logo.svg")
        written.append(SITE / "logo.svg")

    if android_png:
        for name, k in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
            # 108dp layer, the logo on the visible 72dp
            save(full_bleed(logo, round(108 * k), 72 / 108), RES / f"mipmap-{name}" / "ic_launcher_foreground.png")
        xml = RES / "mipmap-anydpi-v26" / "ic_launcher.xml"
        xml.write_text('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                       '    <background android:drawable="@color/icon_bg" />\n    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                       '</adaptive-icon>\n', encoding="utf-8")
        written.append(xml)
    else:
        print("Android launcher icon: vector layers in androidApp/src/main/res/drawable (edit them with logo.svg, or use --android-png)")
    print("\n".join("  wrote " + p.relative_to(ROOT).as_posix() for p in written))


if __name__ == "__main__":
    main(sys.argv[1:])
