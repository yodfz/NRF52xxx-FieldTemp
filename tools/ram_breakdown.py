"""RAM 成分分析：从 ELF 符号表列出 SRAM 占用，按类别聚合。

用途：回答「总 RAM 比上次多了 832 B，到底来自队列、缓冲还是其他静态对象」。
用法：python tools/ram_breakdown.py <elf>
"""
import re
import subprocess
import sys
from collections import OrderedDict

NM = ("C:/ncs/toolchains/66cdf9b75e/opt/zephyr-sdk/"
      "arm-zephyr-eabi/bin/arm-zephyr-eabi-nm.exe")
SRAM_LO, SRAM_HI = 0x20000000, 0x20006000

BUCKETS = OrderedDict([
    ("线程栈", lambda n: n.endswith("_stack") or "stack" in n),
    ("OTA 模块", lambda n: n.startswith("ota_") or "ble_ota" in n),
    ("网络/蓝牙缓冲", lambda n: any(k in n for k in
                                    ("net_buf", "bt_", "conn", "acl", "tx_", "rx_",
                                     "adv_", "gatt", "att_", "l2cap", "mpsl"))),
    ("Flash/存储", lambda n: any(k in n for k in
                                 ("flash", "nvs", "w25q", "spi", "stream"))),
    ("驱动/传感器", lambda n: any(k in n for k in
                                  ("aht", "bmp", "sensor", "i2c", "adc", "timer"))),
])


def main(elf):
    # 不加 -t：地址与尺寸均为十六进制，避免十进制/十六进制混用导致解析错位。
    out = subprocess.run([NM, "-S", "--size-sort", elf],
                         capture_output=True, text=True, errors="replace").stdout
    syms = []
    for line in out.splitlines():
        m = re.match(r"^([0-9A-Fa-f]+)\s+(\d+)\s+(\w)\s+(\S+)$", line.strip())
        if not m:
            continue
        addr, size, _typ, name = (int(m.group(1), 16), int(m.group(2), 16),
                                  m.group(3), m.group(4))
        if SRAM_LO <= addr < SRAM_HI and size > 0:
            syms.append((size, addr, name))

    total = sum(s for s, _, _ in syms)
    print("SRAM 符号总计 = %d B（%d 个符号）\n" % (total, len(syms)))

    print("=== Top 25 单项 ===")
    for size, addr, name in sorted(syms, reverse=True)[:25]:
        print("%8d  0x%08X  %s" % (size, addr, name))

    print("\n=== 按类别聚合 ===")
    used = set()
    bucket_sum = OrderedDict((k, 0) for k in BUCKETS)
    for size, addr, name in syms:
        for label, pred in BUCKETS.items():
            if name not in used and pred(name):
                bucket_sum[label] += size
                used.add(name)
                break
    for k, v in bucket_sum.items():
        print("%-16s %8d B" % (k, v))
    other = total - sum(bucket_sum.values())
    print("%-16s %8d B" % ("其他", other))

    print("\n=== 线程栈明细 ===")
    for size, addr, name in sorted(syms, reverse=True):
        if name.endswith("_stack"):
            print("%8d  0x%08X  %s" % (size, addr, name))

    print("\n=== OTA 模块明细 ===")
    for size, addr, name in sorted(syms, reverse=True):
        if name.startswith("ota_") or "ble_ota" in name:
            print("%8d  0x%08X  %s" % (size, addr, name))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1])
