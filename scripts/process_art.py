#!/usr/bin/env python3
"""把交接目录的流金剑原稿整理成正式 Fabric 资源。"""

from collections import Counter
from pathlib import Path
import argparse

from PIL import Image, ImageDraw


SCROLL_SIZE = 32
SCROLL_INSET = 30
ICON_SIZE = 32
ICON_LARGE = 64
PALETTE_COLORS = 32
MAGENTA_RED_MIN = 130
MAGENTA_BLUE_MIN = 130
MAGENTA_GREEN_MAX = 140
MAGENTA_CHANNEL_GAP = 60
ICON_BACKGROUND = (45, 36, 29)
ICON_OUTER_RIM = (32, 24, 19)
ICON_INNER_RIM = (132, 100, 55)
BACKGROUND_MAX_CHANNEL = 70
BACKGROUND_COLOR_DISTANCE = 20
LARGE_RIM_WIDTH = 2


def read_raw(path: Path) -> Image.Image:
    """读取原稿并统一为 RGBA，便于检查 alpha 和抠图边界。"""
    if not path.is_file():
        raise FileNotFoundError(f"缺少原稿：{path}")
    return Image.open(path).convert("RGBA")


def save_new(image: Image.Image, target: Path) -> None:
    """拒绝覆盖正式资源，避免不同原稿悄悄改变已验收文件。"""
    if target.exists():
        raise FileExistsError(f"拒绝覆盖已有资源：{target}")
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, format="PNG", optimize=True)


def remove_magenta(image: Image.Image) -> Image.Image:
    """将提示词指定的纯品红背景变成透明，保留主体颜色。"""
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            red, green, blue, alpha = pixels[x, y]
            is_magenta = (red >= MAGENTA_RED_MIN and blue >= MAGENTA_BLUE_MIN
                          and green <= MAGENTA_GREEN_MAX
                          and red - green >= MAGENTA_CHANNEL_GAP
                          and blue - green >= MAGENTA_CHANNEL_GAP)
            pixels[x, y] = (red, green, blue, 0) if is_magenta else (red, green, blue, 255 if alpha else 0)
    return image


def output_background(raw_dir: Path, resource_dir: Path, volume: str) -> None:
    """保留阅读底图的全幅 RGB 画布，正文区域由运行时绘制。"""
    source = read_raw(raw_dir / f"{volume}_background.png")
    expected = (1536, 1024)
    if source.size != expected:
        raise ValueError(f"背景尺寸是 {source.size}，预期 {expected}")
    background = source.convert("RGB")
    if volume == "lower":
        background = background.resize((768, 512), Image.Resampling.BOX)
    save_new(background, resource_dir / f"gui/scroll/{volume}.png")


def output_scroll(raw_dir: Path, resource_dir: Path, volume: str) -> None:
    """去除品红底并把卷轴居中为原版物品格尺寸。"""
    source = remove_magenta(read_raw(raw_dir / f"{volume}_scroll.png"))
    bounds = source.getbbox()
    if bounds is None:
        raise ValueError("卷轴没有可见主体")
    object_image = source.crop(bounds)
    object_image.thumbnail((SCROLL_INSET, SCROLL_INSET), Image.Resampling.NEAREST)
    object_alpha = object_image.getchannel("A")
    color_image = Image.new("RGB", object_image.size, (0, 0, 0))
    color_image.paste(object_image.convert("RGB"), mask=object_alpha)
    quantized = color_image.quantize(colors=PALETTE_COLORS, dither=Image.Dither.NONE).convert("RGBA")
    quantized.putalpha(object_alpha)
    canvas = Image.new("RGBA", (SCROLL_SIZE, SCROLL_SIZE), (0, 0, 0, 0))
    left = (SCROLL_SIZE - quantized.width) // 2
    top = (SCROLL_SIZE - quantized.height) // 2
    canvas.alpha_composite(quantized, (left, top))
    alpha = canvas.getchannel("A").point(lambda value: 255 if value >= 128 else 0)
    canvas.putalpha(alpha)
    save_new(canvas, resource_dir / f"item/{volume}_scroll.png")


def normalize_icon(image: Image.Image) -> Image.Image:
    """统一模型暗底和两圈边框，保留原稿动作主体。"""
    inner = image.crop((4, 4, ICON_LARGE - 4, ICON_LARGE - 4))
    dark_colors = Counter(color for color in inner.get_flattened_data() if max(color) <= BACKGROUND_MAX_CHANNEL)
    if not dark_colors:
        raise ValueError("式图标缺少暗色底")
    background = dark_colors.most_common(1)[0][0]
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            color = pixels[x, y]
            distance = sum((channel - base) ** 2 for channel, base in zip(color, background))
            if distance <= BACKGROUND_COLOR_DISTANCE ** 2:
                pixels[x, y] = ICON_BACKGROUND
    limited = image.quantize(colors=PALETTE_COLORS, dither=Image.Dither.NONE).convert("RGB")
    frame = ImageDraw.Draw(limited)
    frame.rectangle((0, 0, ICON_LARGE - 1, ICON_LARGE - 1), outline=ICON_OUTER_RIM, width=LARGE_RIM_WIDTH)
    frame.rectangle((LARGE_RIM_WIDTH, LARGE_RIM_WIDTH, ICON_LARGE - LARGE_RIM_WIDTH - 1,
                     ICON_LARGE - LARGE_RIM_WIDTH - 1), outline=ICON_INNER_RIM, width=LARGE_RIM_WIDTH)
    return limited


def output_icon(raw_dir: Path, icon_dir: Path, name: str) -> None:
    """把式或效果原稿输出为 32 和 64 像素图标。"""
    source = read_raw(raw_dir / f"{name}.png").convert("RGB")
    if source.width != source.height:
        raise ValueError(f"{name} 图标不是方图：{source.size}")
    sampled = source.resize((ICON_LARGE, ICON_LARGE), Image.Resampling.NEAREST)
    icon_64 = normalize_icon(sampled)
    save_new(icon_64.resize((ICON_SIZE, ICON_SIZE), Image.Resampling.NEAREST),
             icon_dir / f"{name}.png")
    save_new(icon_64, icon_dir / f"{name}_64.png")


def main() -> None:
    """按固定资源清单生成正式文件，并在文件已存在时立即失败。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_dir", type=Path)
    parser.add_argument("--volume", choices=("upper", "lower"), default="upper")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    resource_dir = root / "fabric/src/main/resources/assets/meridian_piglin_sword/textures"
    output_background(args.raw_dir, resource_dir, args.volume)
    output_scroll(args.raw_dir, resource_dir, args.volume)
    if args.volume == "lower":
        techniques = ("coil", "molten_flow")
        for name in ("molten", "quenched"):
            output_icon(args.raw_dir, resource_dir / "mob_effect", name)
    else:
        techniques = ("wind_around", "quiver", "spring_back")
    for name in techniques:
        output_icon(args.raw_dir, resource_dir / "gui/technique", name)


if __name__ == "__main__":
    main()
