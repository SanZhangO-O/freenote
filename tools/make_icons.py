"""生成 FreeNote 图标资源（Windows ico/png + Android 旧版 mipmap webp）。

设计：主题紫渐变圆角底 + 白色便签纸（右下折角 + 三条紫色文字线）。
Android 自适应图标的前景/背景是手写矢量（`../android/app/src/main/res/drawable/*.xml`），
本脚本只产出栅格版本，几何与矢量保持一致（同一套 108 视图坐标）。

    python tools/make_icons.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
WINDOWS_ASSETS = ROOT / "windows" / "assets"
ANDROID_RES = ROOT / "android" / "app" / "src" / "main" / "res"

BG_TOP = "#7C68B8"
BG_BOTTOM = "#5A4691"
PAPER = "#FFFFFF"
FOLD = "#CFC2F0"
LINE = "#6750A4"
CORNER_RATIO = 0.22
SS = 8

# 108 视图坐标（与 Android 矢量前景一致）
PAPER_L, PAPER_T, PAPER_R, PAPER_B, PAPER_RADIUS = 36, 28, 72, 80, 6
FOLD_A, FOLD_B, FOLD_C = (72, 68), (72, 80), (60, 80)
LINES = (((45, 43), (63, 43)), ((45, 54), (63, 54)), ((45, 65), (57, 65)))
LINE_WIDTH = 4.0
VIEW = 78.0  # 栅格化时把 108 视图中的这 78 个单位铺满画布（脱掉自适应安全区）
CENTER = 54.0  # 108 视图的几何中心，映射到画布中心

ICO_SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def _rgb(color: str) -> tuple[int, int, int]:
    color = color.lstrip("#")
    return tuple(int(color[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def _gradient(size: int) -> Image.Image:
    """自上而下的主题紫渐变。"""
    top = Image.new("RGB", (size, size), _rgb(BG_TOP))
    bottom = Image.new("RGB", (size, size), _rgb(BG_BOTTOM))
    mask = ImageOps.invert(Image.linear_gradient("L").resize((size, size)))
    return Image.composite(top, bottom, mask)


def _paint(img: Image.Image, mask: Image.Image, color: str) -> None:
    img.paste(Image.new("RGBA", img.size, _rgb(color) + (255,)), (0, 0), mask)


def render(size: int, shape: str = "rounded") -> Image.Image:
    """渲染一张 size×size 图标；shape 为 rounded / circle / square。"""
    s = size * SS
    u = s / VIEW
    half = s / 2
    scaled = lambda value: (value - CENTER) * u + half  # noqa: E731
    length = lambda value: value * u  # noqa: E731
    box = lambda points: [(scaled(x), scaled(y)) for x, y in points]  # noqa: E731

    bg_mask = Image.new("L", (s, s), 255)
    if shape == "circle":
        bg_mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(bg_mask).ellipse((0, 0, s - 1, s - 1), fill=255)
    elif shape == "rounded":
        bg_mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(bg_mask).rounded_rectangle(
            (0, 0, s - 1, s - 1), radius=s * CORNER_RATIO, fill=255
        )

    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    img.paste(_gradient(s), (0, 0), bg_mask)

    # 便签纸：圆角矩形挖掉右下折角
    paper = Image.new("L", (s, s), 0)
    paper_draw = ImageDraw.Draw(paper)
    paper_draw.rounded_rectangle(
        (scaled(PAPER_L), scaled(PAPER_T), scaled(PAPER_R), scaled(PAPER_B)),
        radius=length(PAPER_RADIUS),
        fill=255,
    )
    paper_draw.polygon(box((FOLD_A, FOLD_B, FOLD_C)), fill=0)
    _paint(img, paper, PAPER)

    # 折角
    fold = Image.new("L", (s, s), 0)
    ImageDraw.Draw(fold).polygon(box((FOLD_A, FOLD_B, FOLD_C)), fill=255)
    _paint(img, fold, FOLD)

    # 文字线（圆头）
    lines = Image.new("L", (s, s), 0)
    lines_draw = ImageDraw.Draw(lines)
    width = max(2, round(length(LINE_WIDTH)))
    for (x1, y1), (x2, y2) in LINES:
        lines_draw.line([(scaled(x1), scaled(y1)), (scaled(x2), scaled(y2))], fill=255, width=width)
        for cx, cy in ((scaled(x1), scaled(y1)), (scaled(x2), scaled(y2))):
            r = width / 2
            lines_draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    _paint(img, lines, LINE)

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    WINDOWS_ASSETS.mkdir(parents=True, exist_ok=True)
    render(512, "rounded").save(WINDOWS_ASSETS / "freenote.png")
    render(256, "rounded").save(
        WINDOWS_ASSETS / "freenote.ico", format="ICO", sizes=ICO_SIZES
    )

    for name, px in DENSITIES.items():
        out = ANDROID_RES / f"mipmap-{name}"
        out.mkdir(parents=True, exist_ok=True)
        render(px, "rounded").save(out / "ic_launcher.webp", format="WEBP", lossless=True)
        render(px, "circle").save(
            out / "ic_launcher_round.webp", format="WEBP", lossless=True
        )

    print(f"已生成 {WINDOWS_ASSETS / 'freenote.ico'} 与 {ANDROID_RES}/mipmap-*/ic_launcher*.webp")


if __name__ == "__main__":
    main()
