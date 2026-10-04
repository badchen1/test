# -*- coding: utf-8 -*-
"""从一张源图生成 Android 全套启动图标。

用法：
    python gen_icons.py <源图路径> <res 目录> [背景色]

产出：
    mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png           传统方形图标（API < 26）
    mipmap-{...}dpi/ic_launcher_round.png                 传统圆形图标（API < 26）
    mipmap-{...}dpi/ic_launcher_foreground.png            自适应图标前景（108dp 画布）

自适应图标的安全区是中心 66/108，圆形遮罩直径约 66.7%，
因此前景按画布的 68% 缩放居中，保证在任意遮罩下都铺满且不露白边。
"""

import os
import sys

from PIL import Image, ImageDraw

# 每档密度：(传统图标边长, 自适应前景边长)
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

# 前景图在 108dp 画布中的占比
FOREGROUND_RATIO = 0.68


def cover(img: Image.Image, size: int) -> Image.Image:
    """按短边等比放大后居中裁剪，铺满 size x size。"""
    w, h = img.size
    scale = max(size / w, size / h)
    nw, nh = max(size, int(round(w * scale))), max(size, int(round(h * scale)))
    resized = img.resize((nw, nh), Image.LANCZOS)
    left = (nw - size) // 2
    top = (nh - size) // 2
    return resized.crop((left, top, left + size, top + size))


def contain(img: Image.Image, box: int) -> Image.Image:
    """等比缩放到最长边等于 box，保持完整画面，返回 RGBA。"""
    w, h = img.size
    scale = box / max(w, h)
    resized = img.resize((max(1, int(round(w * scale))), max(1, int(round(h * scale)))), Image.LANCZOS)
    return resized.convert("RGBA")


def circular(img: Image.Image, size: int) -> Image.Image:
    """把方形图裁成圆形，圆外透明。"""
    base = cover(img.convert("RGB"), size).convert("RGBA")
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    base.putalpha(mask)
    return base


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2

    src_path, res_dir = sys.argv[1], sys.argv[2]
    bg_hex = sys.argv[3] if len(sys.argv) > 3 else "#EDE0F9"
    bg_rgb = tuple(int(bg_hex[i : i + 2], 16) for i in (1, 3, 5))

    src = Image.open(src_path).convert("RGB")

    for density, (legacy_size, adaptive_size) in DENSITIES.items():
        out_dir = os.path.join(res_dir, "mipmap-" + density)
        os.makedirs(out_dir, exist_ok=True)

        # 传统方形图标：整图铺满
        cover(src, legacy_size).convert("RGBA").save(os.path.join(out_dir, "ic_launcher.png"))

        # 传统圆形图标
        circular(src, legacy_size).save(os.path.join(out_dir, "ic_launcher_round.png"))

        # 自适应前景：透明画布 + 居中缩放图，尺寸按 0.68 处理避免被遮罩切到主体
        canvas = Image.new("RGBA", (adaptive_size, adaptive_size), (0, 0, 0, 0))
        art = contain(src, int(round(adaptive_size * FOREGROUND_RATIO)))
        canvas.alpha_composite(
            art,
            ((adaptive_size - art.width) // 2, (adaptive_size - art.height) // 2),
        )
        canvas.save(os.path.join(out_dir, "ic_launcher_foreground.png"))

        print("生成 mipmap-%-8s legacy=%-4d adaptive=%-4d" % (density, legacy_size, adaptive_size))

    # 供 adaptive-icon 使用的纯色背景
    print("背景色 #%02X%02X%02X" % bg_rgb)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
