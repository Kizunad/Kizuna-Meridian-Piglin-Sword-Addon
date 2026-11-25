"""检查流金剑模组清单与纯数据边界；协议语义由 Java 验证负责。"""

from pathlib import Path
import json
import struct
import sys


def check_lower_resources(assets: Path) -> None:
    textures = assets / "textures"
    sizes = {"gui/scroll/lower": (768, 512), "item/lower_scroll": (32, 32)}
    keys = {"volume.meridian_piglin_sword.lower", "item.meridian_piglin_sword.lower_scroll",
            "technique.meridian_piglin_sword.sword_required", "technique.meridian_piglin_sword.blade_too_hard"}
    keys.update(("volume.meridian_piglin_sword.lower.note", "codex.power.meridian_piglin_sword.lower"))
    for directory, section, suffix, names in (
            ("gui/technique", "technique", "note", ("coil", "molten_flow")),
            ("mob_effect", "effect", "note", ("molten", "quenched", "coiled", "coiling"))):
        for name in names:
            sizes[f"{directory}/{name}"] = (32, 32)
            sizes[f"{directory}/{name}_64"] = (64, 64)
            key = f"{section}.meridian_piglin_sword.{name}"
            keys.update((key, f"{key}.{suffix}"))
    for name, size in sizes.items():
        path = textures / f"{name}.png"
        with path.open("rb") as image:
            header = image.read(24)
        assert header.startswith(b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR"), f"PNG 文件头无效：{path}"
        assert struct.unpack(">II", header[16:24]) == size, f"贴图尺寸应为 {size}：{path}"
    for locale in ("zh_cn", "en_us"):
        language = json.loads((assets / f"lang/{locale}.json").read_text(encoding="utf-8"))
        for key in sorted(keys):
            assert language.get(key, "").strip(), f"{locale} 缺少文案：{key}"


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    resources = root / "fabric/src/main/resources"
    metadata = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
    catalog = "data/meridian_piglin_sword/technique_catalog.txtpb"
    assert metadata["environment"] == "*", "客户端也须加载图标资源"
    assert not metadata.get("entrypoints") and not metadata.get("mixins"), "正式包必须是纯数据"
    assert metadata["custom"]["meridian_technique:catalogs"] == catalog
    for dependency in ("meridian_technique", "meridian_human_host"):
        assert dependency in metadata["depends"], f"缺少硬依赖 {dependency}"
    assert list((root / "shared/src/main/resources").rglob("*.txtpb")) == [
        root / "shared/src/main/resources" / catalog
    ], "目录只能有一份维护源"
    for path in resources.rglob("*.json"):
        json.loads(path.read_text(encoding="utf-8"))
    assert not list((root / "fabric/src").rglob("*.java")), "正式包不得有 Java"
    check_lower_resources(resources / "assets/meridian_piglin_sword")
    print("数据检查通过：纯数据清单、双端资源、下卷贴图与中英文文案、唯一目录源。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
