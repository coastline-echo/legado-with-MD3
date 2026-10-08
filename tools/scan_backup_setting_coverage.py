#!/usr/bin/env python3
"""扫描设置控件与备份出口的静态覆盖关系。

该工具只读源码，不执行应用逻辑。它用于发现设置页面中的 key 是否能在当前
备份出口中找到对应路径，不能替代真机恢复验收。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app" / "src" / "main"


def source_files(suffix: str):
    yield from APP.rglob(f"*{suffix}")


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def add_entry(entries: dict[str, dict], key: str, source: str, line: int, kind: str):
    item = entries.setdefault(key, {"实际键": key, "来源": [], "来源类型": []})
    item["来源"].append(f"{source}:{line}")
    if kind not in item["来源类型"]:
        item["来源类型"].append(kind)


def prefer_key_values() -> dict[str, str]:
    path = APP / "java" / "io" / "legado" / "app" / "constant" / "PreferKey.kt"
    return dict(re.findall(r'const val\s+(\w+)\s*=\s*"([^"]+)"', path.read_text(encoding="utf-8")))


def scan_xml(entries: dict[str, dict]):
    pattern = re.compile(r"(?:android:|app:)?key\s*=\s*\"([^\"]+)\"")
    for path in source_files(".xml"):
        if path.parent.name != "xml" or not path.name.startswith("pref"):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for match in pattern.finditer(text):
            add_entry(entries, match.group(1), path.relative_to(ROOT).as_posix(), line_number(text, match.start()), "Preference XML")


def scan_kotlin(entries: dict[str, dict]):
    pattern = re.compile(r"PreferKey\.([A-Za-z0-9_]+)")
    values = prefer_key_values()
    for path in source_files(".kt"):
        # UI 中的 PreferKey 仍只是控件线索，不能把业务层所有引用都算成设置控件。
        if not ({"ui", "feature"} & set(path.parts)):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for match in pattern.finditer(text):
            key = values.get(match.group(1))
            if key is not None:
                add_entry(entries, key, path.relative_to(ROOT).as_posix(), line_number(text, match.start()), "UI PreferKey 引用（不等于已绑定控件）")


def scan_compose_controls() -> list[dict[str, str]]:
    # 控件本身可能只绑定 UiState 字段；没有可证明的键值链路时必须列为未映射。
    pattern = re.compile(
        r"\b(InputSettingItem|SwitchSettingItem|ClickableSettingItem|DropdownListSettingItem|"
        r"SliderSettingItem|ListSettingItem)\s*\("
    )
    controls = []
    for path in source_files(".kt"):
        if not ({"ui", "feature"} & set(path.parts)) or "widget" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for match in pattern.finditer(text):
            controls.append({
                "控件": match.group(1),
                "源码": f"{path.relative_to(ROOT).as_posix()}:{line_number(text, match.start())}",
                "状态": "控件→UiState→实际存储键链路未自动证实",
            })
    return controls


def backup_context() -> str:
    path = APP / "java" / "io" / "legado" / "app" / "help" / "storage" / "Backup.kt"
    return path.read_text(encoding="utf-8", errors="ignore")


def classify(entries: dict[str, dict], backup_text: str):
    # 通用出口只能证明存在处理路径，不能证明某个控件实际使用该存储。
    generic_config_backup = "AppConfigStore.preferences.asMap()" in backup_text and "config.xml" in backup_text
    restore_path = APP / "java" / "io" / "legado" / "app" / "help" / "storage" / "Restore.kt"
    restore_text = restore_path.read_text(encoding="utf-8")
    generic_restore = "applyConfigMap(" in restore_text and "config.xml" in restore_text
    for item in entries.values():
        item["存储位置"] = "AppConfigStore（候选，需追踪控件写入）" if generic_config_backup else "未确认"
        item["备份出口"] = "config.xml（候选）" if generic_config_backup else "未确认"
        item["恢复入口"] = "Restore.applyConfigMap（候选）" if generic_restore else "未确认"
        item["状态"] = "源码线索，未证明实际写入或恢复"


def inspect_zip(entries: dict[str, dict], zip_path: Path) -> dict[str, int]:
    # 只读取文件名和 XML 键名，不输出配置值或敏感原文。
    with zipfile.ZipFile(zip_path) as archive:
        names = set(archive.namelist())
        def locate(name: str) -> str | None:
            if name in names:
                return name
            matches = [entry for entry in names if entry.endswith(f"/{name}")]
            return matches[0] if len(matches) == 1 else None

        config_name = locate("config.xml")
        manifest_name = locate("manifest.json")
        config_keys: set[str] = set()
        if config_name is not None:
            root = ET.fromstring(archive.read(config_name))
            config_keys = {node.attrib["name"] for node in root if "name" in node.attrib}
        manifest_items = {}
        if manifest_name is not None:
            manifest = json.loads(archive.read(manifest_name).decode("utf-8"))
            manifest_items = {item["key"]: item["status"] for item in manifest.get("configItems", [])}
    for key, item in entries.items():
        item["ZIP config.xml"] = "已写入" if key in config_keys else "未找到"
        item["ZIP 清单状态"] = manifest_items.get(key, "未登记")
        if key in config_keys and manifest_items.get(key) == "已写入":
            item["状态"] = "已在 ZIP 中核对写入；恢复后应用读取仍待验证"
        elif manifest_items.get(key) == "已写入":
            item["状态"] = "清单与 config.xml 不一致"
    return {"ZIP 配置键": len(config_keys), "清单配置项": len(manifest_items)}


def main():
    # Windows 终端默认编码可能导致中文清单显示乱码，统一使用 UTF-8 输出。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="扫描 Legado 设置控件到备份出口的静态覆盖关系")
    parser.add_argument("--output", type=Path, help="输出 JSON 文件；未指定时输出到标准输出")
    parser.add_argument("--zip", type=Path, help="可选：核对已生成 ZIP 的 config.xml 和 manifest.json 键名")
    args = parser.parse_args()

    entries: dict[str, dict] = {}
    scan_xml(entries)
    scan_kotlin(entries)
    controls = scan_compose_controls()
    classify(entries, backup_context())
    zip_stats = inspect_zip(entries, args.zip) if args.zip else {}
    result = {
        "说明": "源码线索不等于控件绑定或恢复成功；--zip 只证明键名写入备份，不证明恢复后应用读取",
        "统计": {
            "发现设置键": len(entries),
            "源码线索": len(entries),
            "待映射Compose控件": len(controls),
            **zip_stats,
        },
        "设置": dict(sorted(entries.items())),
        "待映射Compose控件": controls,
    }
    payload = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload, end="")


if __name__ == "__main__":
    main()
