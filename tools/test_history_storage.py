#!/usr/bin/env python3
"""Compile production C functions with NOR/NVS test doubles; requires a native C compiler."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--cc", required=True)
parser.add_argument("--work-dir", type=Path)
parser.add_argument("--compare-old-head", action="store_true", help="Also compile the specified immutable old scan as regression control")
parser.add_argument("--old-head-ref", default="1231ad7b5f9e19993c86b72468b7dda70e4793e1", help="Old implementation commit/ref for --compare-old-head")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
main = (root / "src/main.c").read_text(encoding="utf-8")
common = (root / "src/common.h").read_text(encoding="utf-8")
nvs = (root / "src/storage/nvs_config.h").read_text(encoding="utf-8")
w25 = (root / "src/storage/w25q64.h").read_text(encoding="utf-8")
def function(name):
    start = main.index(name)
    while main[start - 1] != "\n": start -= 1
    end = main.index("\n}", start) + 2
    return main[start:end]
geometry_names = {"W25Q64_PAGE_SIZE", "W25Q64_SECTOR_SIZE", "W25Q64_STORAGE_SIZE", "W25Q64_RECORD_SIZE",
                  "W25Q64_RECORDS_PER_PAGE", "W25Q64_PAGES_PER_SECTOR", "W25Q64_RECORDS_PER_SECTOR",
                  "W25Q64_MAX_RECORDS", "W25Q64_MAX_SECTORS_LIMIT"}
geometry = "\n".join(line for line in w25.splitlines() if line.startswith("#define ") and line.split()[1] in geometry_names)
record = common[common.index("struct data_record {"):common.index("} __packed;", common.index("struct data_record {")) + 11]
retention_start = nvs.index("#define HISTORY_RETENTION_MAGIC")
retention_end = nvs.index("} __packed history_retention_t;", retention_start) + len("} __packed history_retention_t;")
position_start = nvs.index("typedef struct {", nvs.index("/* 存储位置上下文 */"))
position_end = nvs.index("} storage_position_t;", position_start) + len("} storage_position_t;")
types = geometry + "\n#define W25Q64_STORAGE_BASE 0x2e000U\n#define RAM_BUFFER_SIZE 5\n" + record + "\n" + nvs[retention_start:retention_end] + "\n" + nvs[position_start:position_end]
retention_code = main[main.index("/* A separate atomic NVS checkpoint"):main.index("// 配置数据结构已移至", main.index("/* A separate atomic NVS checkpoint"))]
under_test = function("static uint32_t storage_physical_count(void)") + "\n" + retention_code + "\n" + function("static void storage_keep_unwritten(") + "\n" + function("static void storage_write_batch(void)\n{") + "\n" + function("static void storage_scan_flash_for_write_head(") + "\n" + function("static bool linear_find_first_after_timestamp(") + "\n" + function("static uint16_t history_retention_days(void)")
if args.compare_old_head:
    old = subprocess.run(["git", "-C", str(root), "show", f"{args.old_head_ref}:src/main.c"], check=True, capture_output=True, encoding="utf-8").stdout
    name = "static void storage_scan_flash_for_write_head("
    start = old.index(name)
    end = old.index("\n}", start) + 2
    old_function = old[start:end].replace("storage_scan_flash_for_write_head", "old_storage_scan_flash_for_write_head")
    under_test += "\n#define HAVE_OLD_SCAN_CONTROL 1\n" + old_function + "\n"
wire_start = main.index("        rec.pressure_pa = decoded_pressure;")
wire_end = main.index("        tx_len += history_wire_size;", wire_start) + len("        tx_len += history_wire_size;")
wire = "static uint8_t append_actual_wire(struct data_record rec, uint32_t decoded_pressure, uint16_t decoded_voltage, uint8_t *tx_buf, uint8_t tx_len) {\n" + main[wire_start:wire_end] + "\nreturn tx_len;\n}\n"
last_start = main.index("uint32_t last_timestamp = sys_get_le32(tx_buf + tx_len - history_wire_size);")
last_end = last_start + len("uint32_t last_timestamp = sys_get_le32(tx_buf + tx_len - history_wire_size);")
wire += "static uint32_t actual_last_timestamp(uint8_t *tx_buf, uint8_t tx_len) {\n" + main[last_start:last_end] + "\nreturn last_timestamp;\n}\n"
if args.work_dir: args.work_dir.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="history-c-", dir=args.work_dir) as temp:
    out = Path(temp)
    (out / "history_storage_types.inc").write_text(types, encoding="utf-8")
    (out / "history_storage_under_test.inc").write_text(under_test, encoding="utf-8")
    (out / "history_wire_under_test.inc").write_text(wire, encoding="utf-8")
    executable = out / "history_storage_test.exe"
    subprocess.run([args.cc, "-I" + str(out), "-I" + str(root / "src/storage"),
                    str(root / "tools/history_storage_test.c"), "-o", str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
