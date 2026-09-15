#!/usr/bin/env python3
"""线程栈高水位测量（0xAA 扫描法）。

原理
----
开启 `CONFIG_INIT_STACKS=y` 后，Zephyr 在创建线程/初始化期把每个线程栈填充为 0xAA。
线程运行后，未被触及的低地址区域仍是 0xAA，因此：

    未使用字节数 = 从栈底向上连续 0xAA 的字节数
    峰值使用量   = 栈对象大小 - 未使用字节数

这才是"RAM 还够不够"的真正安全判据 —— 只看静态占用率（如 80%）无法回答
"某个线程的栈会不会溢出"。

关于 idle 栈
-----------
`z_idle_stacks` 默认**不参与"最紧张/是否风险"的判定**（可用 `--ignore` 改）：
其可用区通常只有 128 B，空闲 64 B 即触发 `--min-free 128` 的告警，但 idle 线程只跑
`arch_cpu_idle()` 的空循环，栈深由 Kconfig 固定、不会随业务增长。把它列为"最紧张"
会制造一个**永久假阳性**，长期看会训练人忽略本工具的输出，故默认豁免。

关于 MPU 守卫区
--------------
开启 `CONFIG_MPU_STACK_GUARD=y` 时，`K_THREAD_STACK_LEN()` 会在栈对象**底部**
额外附加 `CONFIG_MPU_STACK_GUARD_SIZE`（通常 64）字节的守卫区，该区域**不会被
0xAA 填充**。若不跳过守卫区，扫描会立刻在第一个字节停下、得到"未使用=0"的
假告警。本脚本按 `--guard` 显式跳过（默认 64，按目标 .config 自动判定）。

用法
----
    # 1) 构建测量镜像（不要用发布镜像，发布镜像没有 INIT_STACKS）
    #    west build ... -- -DCONFIG_INIT_STACKS=y
    # 2) 让设备在真实负载下跑一段时间（BLE 已连接、历史写入、NVS flush）
    # 3) 只读测量（attach，不复位，保留栈现场）
    python tools/stack_hwm.py --elf <build>/NRF52xxx-FieldTemp/zephyr/zephyr.elf \
                              --config <build>/NRF52xxx-FieldTemp/zephyr/.config

选项
----
    --list-only        只列出发现的栈，不连调试器
    --extra NAME=ADDR,SIZE   手工补充符号表里找不到的栈
    --json FILE        把结果写成 JSON（可交给 size_summary.py --stack-report）
    --min-free BYTES   低于该余量即标记为风险（默认 128）
"""

import argparse
import json
import os
import re
import subprocess
import sys

RAM_LO, RAM_HI = 0x20000000, 0x20006000

NM_CANDIDATES = [
    r"C:/ncs/toolchains/66cdf9b75e/opt/zephyr-sdk/arm-zephyr-eabi/bin/arm-zephyr-eabi-nm.exe",
    "arm-zephyr-eabi-nm",
    "arm-none-eabi-nm",
]

# 不是线程栈、但名字里带 stack 的符号
SYMBOL_BLOCKLIST = (
    "_k_stack_list", "z_interrupt_stack_SIZEOF", "stack_chk",
)

STACK_NAME_RE = re.compile(r"stack", re.IGNORECASE)


def find_nm():
    for cand in NM_CANDIDATES:
        if os.path.isfile(cand):
            return cand
        found = None
        try:
            found = subprocess.run(["which", cand], capture_output=True,
                                   text=True).stdout.strip()
        except OSError:
            found = None
        if found:
            return found
    return None


def read_symbols(nm, elf):
    """返回 [(addr, size, type, name)]，只保留 B/b 段且地址在 RAM 区间。"""
    out = subprocess.run([nm, "-S", elf], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("nm 执行失败：%s" % out.stderr.strip())
    rows = []
    for line in out.stdout.splitlines():
        parts = line.split()
        if len(parts) == 4:
            addr, size, sym_type, name = parts
        elif len(parts) == 3:
            addr, sym_type, name = parts
            size = "0"
        else:
            continue
        if sym_type not in ("b", "B", "d", "D"):
            continue
        try:
            addr_i = int(addr, 16)
            size_i = int(size, 16)
        except ValueError:
            continue
        if not (RAM_LO <= addr_i < RAM_HI):
            continue
        rows.append((addr_i, size_i, sym_type, name))
    return rows


def discover_stacks(rows, min_size=128):
    """按命名约定挑出线程栈对象。"""
    found = []
    for addr, size, _t, name in rows:
        if size < min_size:
            continue
        if any(b in name for b in SYMBOL_BLOCKLIST):
            continue
        if not STACK_NAME_RE.search(name):
            continue
        found.append({"name": name, "addr": addr, "size": size})
    return sorted(found, key=lambda s: s["addr"])


def read_config_bool(path, key):
    if not path or not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if line == key + "=y":
                return True
            if line == "# %s is not set" % key:
                return False
    return None


def connect(probe, target, freq):
    from pyocd.core.helpers import ConnectHelper
    session = ConnectHelper.session_with_chosen_probe(
        unique_id=probe, target_override=target, frequency=freq)
    if session is None:
        sys.exit("未找到调试器 %s" % probe)
    return session


def measure(target, stacks, guard):
    """读取每个栈对象，统计底部连续 0xAA 的字节数。"""
    results = []
    for stk in stacks:
        base, size = stk["addr"], stk["size"]
        blob = bytes(target.read_memory_block8(base, size))
        scan_from = guard if (guard and size > guard) else 0
        untouched = 0
        for idx in range(scan_from, size):
            if blob[idx] != 0xAA:
                break
            untouched += 1
        usable = size - scan_from
        peak = usable - untouched
        # 峰值占比以"可用区"为分母；free 即真正还能下探的空间
        results.append({
            "name": stk["name"],
            "addr": base,
            "size": size,
            "guard": scan_from,
            "usable": usable,
            "untouched": untouched,
            "peak": peak,
            "free": untouched,
            "used_pct": round(100.0 * peak / usable, 2) if usable else 0.0,
            "lowest_addr_touched": base + scan_from + untouched,
        })
    return results


def main():
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--elf", required=True, help="应用 zephyr.elf（含 INIT_STACKS 的构建）")
    ap.add_argument("--config", default=None, help="该构建的 .config，用于判定守卫区")
    ap.add_argument("--probe", default="LU_2022_8888", help="pyOCD 探针 UID")
    ap.add_argument("--target", default="nRF52810_xxAA", help="pyOCD 目标名")
    ap.add_argument("--freq", type=int, default=500000, help="SWD 时钟 Hz")
    ap.add_argument("--guard", type=int, default=None,
                    help="跳过的底部守卫区字节数；默认按 .config 自动判定")
    ap.add_argument("--extra", action="append", default=[],
                    help="补充栈定义，形如 NAME=0xADDR,0xSIZE")
    ap.add_argument("--min-free", type=int, default=128,
                    help="低于该空闲字节数视为风险（默认 128）")
    ap.add_argument("--ignore", default="z_idle_stacks",
                    help="不参与风险判定的栈名，逗号分隔（默认 z_idle_stacks）；"
                         "留空 --ignore= 表示全部参与判定")
    ap.add_argument("--json", default=None, help="结果 JSON 输出路径")
    ap.add_argument("--list-only", action="store_true", help="只列栈，不连调试器")
    args = ap.parse_args()

    nm = find_nm()
    if not nm:
        sys.exit("找不到 arm-zephyr-eabi-nm")
    rows = read_symbols(nm, args.elf)
    stacks = discover_stacks(rows)
    for spec in args.extra:
        name, _, spec2 = spec.partition("=")
        addr_s, _, size_s = spec2.partition(",")
        stacks.append({"name": name, "addr": int(addr_s, 16), "size": int(size_s, 0)})
    stacks.sort(key=lambda s: s["addr"])

    if not stacks:
        sys.exit("没有发现任何栈符号；请用 --extra 手工指定")

    print("发现 %d 个栈对象：" % len(stacks))
    for s in stacks:
        print("  0x%08x  %6d B  %s" % (s["addr"], s["size"], s["name"]))
    if args.list_only:
        return 0

    # 守卫区判定
    guard = args.guard
    if guard is None:
        mpu_guard = read_config_bool(args.config, "CONFIG_MPU_STACK_GUARD") \
            if args.config else None
        init_stacks = read_config_bool(args.config, "CONFIG_INIT_STACKS") \
            if args.config else None
        if init_stacks is False:
            print("!! 该构建 CONFIG_INIT_STACKS 未开启，0xAA 填充不存在，测量无意义")
            return 2
        guard = 64 if mpu_guard else 0
        print("守卫区：MPU_STACK_GUARD=%s -> 跳过底部 %d B" % (mpu_guard, guard))

    session = connect(args.probe, args.target, args.freq)
    with session:
        target = session.target
        # 读取前先 halt：目标可能处于 System ON 睡眠，且 halt 能保证读取一致性。
        # halt 不会改变栈内容（只是停止取指），因此栈现场完好。
        target.halt()
        results = measure(target, stacks, guard)
        target.resume()

    ignored = set(n for n in (args.ignore or "").split(",") if n)
    judged = [r for r in results if r["name"] not in ignored]
    if not judged:
        judged = results
        ignored = set()

    print()
    print("%-22s %8s %8s %8s %8s %8s %8s" %
          ("线程栈", "对象", "守卫", "可用", "峰值", "空闲", "占比"))
    print("-" * 78)
    for r in sorted(results, key=lambda x: x["free"]):
        if r["name"] in ignored:
            flag = "  (豁免)"
        elif r["free"] < args.min_free:
            flag = "  <== 偏紧"
        else:
            flag = ""
        print("%-22s %8d %8d %8d %8d %8d %7.1f%%%s"
              % (r["name"], r["size"], r["guard"], r["usable"],
                 r["peak"], r["free"], r["used_pct"], flag))
    worst = min(judged, key=lambda x: x["free"])
    print("-" * 78)
    print("最紧张：%s 空闲 %d B（阈值 %d B）" % (worst["name"], worst["free"], args.min_free))
    if ignored:
        print("豁免判定：%s（默认，因其栈深由 Kconfig 固定、不随业务增长）"
              % "、".join(sorted(ignored)))

    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump({
                "elf": args.elf,
                "guard": guard,
                "min_free": args.min_free,
                "ignored": sorted(ignored),
                "worst": worst["name"],
                # threads 只含参与判定的栈，供 size_summary.py --stack-report 消费
                "threads": [{"name": r["name"], "size": r["usable"],
                             "peak": r["peak"]} for r in judged],
                "raw": results,
            }, handle, indent=2, ensure_ascii=False)
        print("已写出 %s" % args.json)

    return 1 if worst["free"] < args.min_free else 0


if __name__ == "__main__":
    sys.exit(main())
