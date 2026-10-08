import io
import json
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import scan_backup_setting_coverage as scanner


class ScanBackupSettingCoverageTest(unittest.TestCase):
    def test_prefer_key_symbol_resolves_to_actual_storage_key(self):
        self.assertEqual("web_dav_url", scanner.prefer_key_values()["webDavUrl"])

    def test_zip_check_only_reads_key_names(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("config.xml", '<map><string name="web_dav_url">secret</string></map>')
            archive.writestr(
                "manifest.json",
                json.dumps({"configItems": [{"key": "web_dav_url", "status": "已写入"}]}),
            )
        stream.seek(0)
        entries = {"web_dav_url": {"实际键": "web_dav_url"}}
        stats = scanner.inspect_zip(entries, stream)
        self.assertEqual(1, stats["ZIP 配置键"])
        self.assertEqual("已在 ZIP 中核对写入；恢复后应用读取仍待验证", entries["web_dav_url"]["状态"])
        self.assertNotIn("secret", json.dumps(entries))

    def test_main_zip_may_have_one_outer_directory_without_manifest(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("backup/config.xml", '<map><string name="language">zh</string></map>')
        stream.seek(0)
        entries = {"language": {"实际键": "language"}}
        stats = scanner.inspect_zip(entries, stream)
        self.assertEqual(1, stats["ZIP 配置键"])
        self.assertEqual("未登记", entries["language"]["ZIP 清单状态"])


if __name__ == "__main__":
    unittest.main()
