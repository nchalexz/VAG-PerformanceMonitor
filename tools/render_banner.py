#!/usr/bin/env python3
"""Render the English README banner from the real app screenshots.

The banner is a composition, not a mockup of the UI: the panel is the actual
1920x720 Demo Mode dashboard screenshot already used in the README. Only the
background, the soft shadow and the typography are drawn here.

Usage:
    python3 tools/render_banner.py

Requires Pillow and NumPy (both ship with the harness Python).

Output:
    docs/assets/banner-en.png (1600x600)
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "docs" / "assets"
FONTS = ROOT / "app" / "src" / "main" / "res" / "font"

W, H = 1600, 600
S = 2  # supersampling factor

BG = (8, 10, 12)
TITLE = (255, 255, 255)
ACCENT = (56, 189, 248)  # #38BDF8, the app primary
GREEN = (74, 222, 128)  # #4ADE80, the app ok colour
MUTED = (139, 149, 159)
DIM = (110, 120, 131)
CHIP_TEXT = (185, 194, 204)

FONT_SEMIBOLD = FONTS / "titillium_web_semibold.ttf"
FONT_REGULAR = FONTS / "titillium_web_regular.ttf"
FONT_LIGHT = FONTS / "titillium_web_light.ttf"

SCREEN = ASSETS / "screen-driving.png"
OUT = ASSETS / "banner-en.png"


def font(path: Path, size: float) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(path), round(size * S))


def px(value: float) -> int:
    return round(value * S)


def baseline(draw: ImageDraw.ImageDraw, xy, text, fnt, fill, tracking=0.0):
    """Draw text with optional letter tracking, anchored on the baseline."""
    x, y = xy
    if tracking == 0:
        draw.text((px(x), px(y)), text, font=fnt, fill=fill, anchor="ls")
        return
    for ch in text:
        draw.text((px(x), px(y)), ch, font=fnt, fill=fill, anchor="ls")
        x += fnt.getlength(ch) / S + tracking


def tracked_width(text: str, fnt: ImageFont.FreeTypeFont, tracking: float) -> float:
    if not text:
        return 0.0
    return (sum(fnt.getlength(ch) for ch in text) + tracking * S * (len(text) - 1)) / S


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size[0] - 1, size[1] - 1), radius=px(radius), fill=255
    )
    return mask


def add_window(canvas, path, x, y, w, radius=16):
    """Place the screenshot as a quiet window: rounded, no outline."""
    src = Image.open(path).convert("RGB")
    h = round(w * src.height / src.width)
    window = src.resize((px(w), px(h)), Image.LANCZOS).convert("RGBA")
    window.putalpha(rounded_mask(window.size, radius))

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        (px(x), px(y + 12), px(x + w), px(y + h + 12)),
        radius=px(radius),
        fill=(0, 0, 0, 150),
    )
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(px(22))))
    canvas.alpha_composite(window, (px(x), px(y)))
    return h


def draw_logo(canvas, x, y, w=44):
    """The launcher icon: a diagnostic connector with four pins and a green tab."""
    h = round(w * 28 / 44)
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle(
        (px(x), px(y), px(x + w), px(y + h)),
        radius=px(5),
        fill=ACCENT,
    )
    pin_w, pin_h = 5.6, 5.6
    pin_y = y + 8.6
    for i in range(4):
        pin_x = x + 6.4 + i * 8.9
        d.rectangle(
            (px(pin_x), px(pin_y), px(pin_x + pin_w), px(pin_y + pin_h)),
            fill=(15, 18, 22),
        )
    tab_w, tab_h = 8.0, 6.0
    d.rectangle(
        (px(x + w / 2 - tab_w / 2), px(y + h), px(x + w / 2 + tab_w / 2), px(y + h + tab_h)),
        fill=GREEN,
    )


def draw_chip(canvas, x, y, label):
    fnt = font(FONT_SEMIBOLD, 12.5)
    tracking = 1.3
    text_w = tracked_width(label, fnt, tracking)
    w = text_w + 32
    h = 34
    d = ImageDraw.Draw(canvas)
    d.rounded_rectangle(
        (px(x), px(y), px(x + w), px(y + h)),
        radius=px(h / 2),
        fill=(19, 25, 31),
    )
    baseline(d, (x + 16, y + h / 2 + 4.4), label, fnt, CHIP_TEXT, tracking)
    return w


def background() -> Image.Image:
    canvas = Image.new("RGBA", (W * S, H * S), BG + (255,))

    # Fine grid, barely visible: a bench instrument, not a poster.
    grid = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    for x in range(0, W + 1, 40):
        gd.line((px(x), 0, px(x), canvas.size[1]), fill=(255, 255, 255, 7), width=1)
    for y in range(0, H + 1, 40):
        gd.line((0, px(y), canvas.size[0], px(y)), fill=(255, 255, 255, 7), width=1)
    canvas.alpha_composite(grid)

    # Teal glow behind the panels, computed small and upscaled.
    gw, gh = W // 4, H // 4
    yy, xx = np.mgrid[0:gh, 0:gw]
    r = np.sqrt((xx - 1150 / 4) ** 2 + (yy - 330 / 4) ** 2) / (520 / 4)
    falloff = np.clip(1 - r, 0, 1) ** 2.2
    glow = np.zeros((gh, gw, 4), dtype=np.uint8)
    glow[..., 0] = ACCENT[0]
    glow[..., 1] = ACCENT[1]
    glow[..., 2] = ACCENT[2]
    glow[..., 3] = (falloff * 26).astype(np.uint8)
    canvas.alpha_composite(Image.fromarray(glow, "RGBA").resize(canvas.size, Image.BILINEAR))

    # Slight vertical lift at the top, like a lit panel.
    top = Image.new("RGBA", (1, canvas.size[1]))
    for y in range(canvas.size[1]):
        a = round(10 * (1 - y / canvas.size[1]) ** 2)
        top.putpixel((0, y), (255, 255, 255, a))
    canvas.alpha_composite(top.resize(canvas.size, Image.BILINEAR))
    return canvas


def draw_left(canvas):
    d = ImageDraw.Draw(canvas)
    draw_logo(canvas, 72, 58, 44)

    over_font = font(FONT_SEMIBOLD, 12)
    baseline(d, (130, 80), "OPEN SOURCE · VAG TELEMETRY · ANDROID", over_font, MUTED, 3.2)

    title_font = font(FONT_SEMIBOLD, 68)
    baseline(d, (72, 226), "VAG Performance", title_font, TITLE, -1.4)
    baseline(d, (72, 294), "Monitor", title_font, ACCENT, -1.4)

    sub_font = font(FONT_LIGHT, 21)
    baseline(d, (74, 342), "Extended telemetry through an ordinary ELM327.", sub_font, MUTED)

    brand_font = font(FONT_SEMIBOLD, 11.5)
    baseline(
        d,
        (74, 376),
        "VOLKSWAGEN · AUDI · ŠKODA · SEAT · CUPRA",
        brand_font,
        DIM,
        2.3,
    )

    x = 74
    for label in ("MQB", "OBD-II", "NO GMS", "ANDROID 7+"):
        x += draw_chip(canvas, x, 412, label) + 10

    rule_y = 488
    rule = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(rule).line(
        (px(74), px(rule_y), px(600), px(rule_y)),
        fill=(255, 255, 255, 24),
        width=max(1, S),
    )
    canvas.alpha_composite(rule)

    foot_font = font(FONT_REGULAR, 14)
    baseline(d, (74, 524), "Dashboard · home-screen widget · car screen", foot_font, MUTED)
    baseline(d, (74, 550), "One ELM327 connection. No account. No cloud.", foot_font, DIM)


def main() -> None:
    canvas = background()
    draw_left(canvas)

    window_h = add_window(canvas, SCREEN, 628, 140, 900)

    d = ImageDraw.Draw(canvas)
    cap_font = font(FONT_SEMIBOLD, 11.5)
    caption = "REAL APP SCREENSHOT · ANDROID 15 EMULATOR · DEMO MODE"
    cap_w = tracked_width(caption, cap_font, 1.6)
    baseline(d, (1528 - cap_w, 515), caption, cap_font, DIM, 1.6)

    canvas.convert("RGB").resize((W, H), Image.LANCZOS).save(OUT, "PNG", optimize=True)
    print(f"wrote {OUT.relative_to(ROOT)} ({OUT.stat().st_size} bytes, {W}x{H})")
    print(f"window: {900}x{window_h}")


if __name__ == "__main__":
    main()
