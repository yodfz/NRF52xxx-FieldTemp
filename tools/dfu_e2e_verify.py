#!/usr/bin/env python3
"""外置 Flash 次级槽 DFU 闭环端到端验证。

流程（每一步都留证据）
----------------------
1. `nm` 解析被测 ELF，取出运行态可观测符号（`nvs_ready` / `w25q64_ready` /
   `bt_ready` / 历史写头）——符号地址随构建变化，绝不硬编码。
2. 把签名镜像写入 W25Q64 次级槽（整槽擦除 + 编程 + 写 trailer magic + 回读校验）。
3. 复位让 MCUboot 执行搬运，运行一段时间。
4. 只读取证（halt → 读，不复位）：
   - 主槽镜像头（版本号、img_size）
   - 主槽内容与镜像文件**逐字节比对**（这是"搬运成功"的决定性证据）
   - 次级槽 trailer 是否已被清除（`ff` = 已失效）
   - MCUboot 是否最终跳进了 App（VTOR / PC）
   - App 运行态：`w25q64_ready` / `bt_ready` / `nvs_ready` / 历史写头
5. 连续多次复位，验证升级幂等、无启动循环。

用法
----
    python tools/dfu_e2e_verify.py --image build-v4/.../zephyr.signed.v4.bin \
        --elf build-v4/NRF52xxx-FieldTemp/zephyr/zephyr.elf --resets 3

    # 只取证、不写次级槽（镜像已在槽里时）
    python tools/dfu_e2e_verify.py --image ... --elf ... --attach-only
"""

import argparse
import os
import re
import struct
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from w25q64_host_dfu import (  # noqa: E402
    W25Q64, do_write, check_chip,
    SECONDARY_BASE, SECONDARY_SIZE, MAGIC_OFF, TRAILER_MAGIC,
)

NM_CANDIDATES = [
    r"C:/ncs/toolchains/66cdf9b75e/opt/zephyr-sdk/arm-zephyr-eabi/bin/arm-zephyr-eabi-nm.exe",
    "arm-zephyr-eabi-nm",
]

PRIMARY_BASE = 0x8000          # 主槽起始（含 0x200 镜像头）
VTOR_ADDR = 0xE000ED08

WATCH_SYMBOLS = [
    "w25q64_ready", "bt_ready", "nvs_ready",
    "next_sector", "next_record_in_sector", "ram_buffer_count",
]


def find_nm():
    for cand in NM_CANDIDATES:
        if os.path.isfile(cand):
            return cand
    return None


def symbol_table(nm, elf):
    """返回 {name: (addr, size)}，只取 RAM 中的 B/b 符号。"""
    out = subprocess.run([nm, "-S", elf], capture_output=True, text=True)
    table = {}
    for line in out.stdout.splitlines():
        parts = line.split()
        if len(parts) != 4:
            continue
        addr, size, sym_type, name = parts
        if sym_type not in ("b", "B", "d", "D"):
            continue
        try:
            addr_i, size_i = int(addr, 16), int(size, 16)
        except ValueError:
            continue
        if 0x20000000 <= addr_i < 0x20006000:
            table[name] = (addr_i, size_i)
    return table


def decode_image_header(blob):
    """解析 MCUboot 镜像头。

    布局：magic(u32) load_addr(u32) hdr_size(u16) protect_tlv_size(u16)
          img_size(u32) flags(u32) version(major u8, minor u8, revision u16, build u32)
    """
    magic, load, hdr_size, _protect, img_size, flags = \
        struct.unpack_from("<IIHHII", blob, 0)
    at = blob[0x14:0x18]
    build = struct.unpack_from("<I", blob, 0x18)[0]
    return {
        "magic": magic, "load_addr": load, "hdr_size": hdr_size,
        "img_size": img_size, "flags": flags,
        "version": "%d.%d.%d+%d" % (at[0], at[1], at[2] | (at[3] << 8), build),
    }


def read_state(target, table, flash):
    """halt 后读取全部运行态证据。返回 dict。"""
    state = {}
    try:
        state["pc"] = target.read_core_register("pc")
        state["sp"] = target.read_core_register("sp")
    except Exception as exc:                       # 目标处于 System ON 时可能读不到
        state["pc"] = state["sp"] = "n/a (%s)" % exc.__class__.__name__
    state["vtor"] = target.read32(VTOR_ADDR)
    state["primary_header"] = decode_image_header(
        bytes(target.read_memory_block8(PRIMARY_BASE, 32)))
    for name in WATCH_SYMBOLS:
        if name not in table:
            state[name] = None
            continue
        addr, size = table[name]
        if size == 1:
            state[name] = target.read8(addr)
        elif size == 2:
            state[name] = target.read16(addr)
        else:
            state[name] = target.read32(addr)
    if flash is not None:
        state["secondary_trailer"] = flash.read(MAGIC_OFF, len(TRAILER_MAGIC))
        state["secondary_head"] = flash.read(SECONDARY_BASE, 16)
    else:
        state["secondary_trailer"] = None
        state["secondary_head"] = None
    return state


def show(tag, state):
    ph = state["primary_header"]
    print("  [%s] VTOR=0x%08x  PC=%s  SP=%s" % (tag, state["vtor"], state["pc"], state["sp"]))
    print("       主槽镜像头: ver=%s img_size=%d magic=0x%08x hdr=0x%x"
          % (ph["version"], ph["img_size"], ph["magic"], ph["hdr_size"]))
    print("       状态: w25q64_ready=%s bt_ready=%s nvs_ready=%s"
          % (state["w25q64_ready"], state["bt_ready"], state["nvs_ready"]))
    if state["next_sector"] is not None:
        print("       历史写头: next_sector=%s next_record=%s ram_buf=%s"
              % (state["next_sector"], state["next_record_in_sector"],
                 state["ram_buffer_count"]))
    if state["secondary_trailer"] is not None:
        tr = state["secondary_trailer"]
        verdict = "已清除(失效)" if tr == b"\xff" * len(tr) else "仍存在 -> 会重复触发升级"
        print("       次级槽 trailer @0x%06x: %s  => %s"
              % (MAGIC_OFF, " ".join("%02x" % b for b in tr), verdict))
    if state["secondary_head"] is not None:
        sh = state["secondary_head"]
        print("       次级槽头部: %s" % " ".join("%02x" % b for b in sh))


def verdicts(state, image):
    """把状态翻译成 pass/fail 判定。"""
    checks = []
    ph = state["primary_header"]
    checks.append(("主槽镜像头 magic 合法", ph["magic"] == 0x96f3b83d))
    checks.append(("MCUboot 已跳转进 App (VTOR=主槽基址)", state["vtor"] == 0x8200))
    checks.append(("W25Q64 就绪", state["w25q64_ready"] in (1, True)))
    checks.append(("蓝牙已启动", state["bt_ready"] in (1, True)))
    if state["nvs_ready"] is not None:
        checks.append(("NVS 已挂载", state["nvs_ready"] in (1, True)))
    if state["secondary_trailer"] is not None:
        checks.append(("次级槽 trailer 已清除",
                       state["secondary_trailer"] == b"\xff" * len(TRAILER_MAGIC)))
    if image is not None:
        checks.append(("主槽版本 == 镜像版本",
                       ph["version"] == decode_image_header(image)["version"]))
    return checks


def main():
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--image", required=True, help="签名镜像（zephyr.signed.*.bin）")
    ap.add_argument("--elf", required=True, help="同一构建的 App zephyr.elf")
    ap.add_argument("--probe", default="LU_2022_8888")
    ap.add_argument("--target", default="nRF52810_xxAA")
    ap.add_argument("--swd", type=int, default=500000)
    ap.add_argument("--run-sec", type=int, default=45, help="升级后运行时长（秒）")
    ap.add_argument("--resets", type=int, default=3, help="额外复位次数（幂等性）")
    ap.add_argument("--attach-only", action="store_true",
                    help="不写次级槽，只做只读取证")
    args = ap.parse_args()

    nm = find_nm()
    if not nm:
        sys.exit("找不到 arm-zephyr-eabi-nm")
    table = symbol_table(nm, args.elf)
    missing = [s for s in WATCH_SYMBOLS if s not in table]
    print("符号解析：%d 个被观察符号" % (len(WATCH_SYMBOLS) - len(missing)),
          end="")
    if missing:
        print("，缺失 %s（可能被编译器优化掉）" % ",".join(missing))
    else:
        print()

    image = open(args.image, "rb").read()
    img_hdr = decode_image_header(image)
    print("待验证镜像：%s  %d B  (版本 %s)" % (args.image, len(image), img_hdr["version"]))

    from pyocd.core.helpers import ConnectHelper

    # ---- 阶段 1：写入次级槽 ----
    if not args.attach_only:
        session = ConnectHelper.session_with_chosen_probe(
            unique_id=args.probe, target_override=args.target, frequency=args.swd)
        if session is None:
            sys.exit("未找到调试器 %s" % args.probe)
        with session:
            target = session.target
            target.reset_and_halt()
            flash = W25Q64(target)
            flash.init()
            check_chip(flash)
            do_write(flash, image)
            print("次级槽写入完成，复位并释放调试器\n")
            target.reset()
        time.sleep(args.run_sec)
    else:
        print("跳过写入（--attach-only）\n")

    # ---- 阶段 2：只读取证 ----
    session = ConnectHelper.session_with_chosen_probe(
        unique_id=args.probe, target_override=args.target, frequency=args.swd)
    if session is None:
        sys.exit("未找到调试器 %s" % args.probe)
    with session:
        target = session.target
        target.halt()
        state = read_state(target, table, flash=None)
    print("升级后运行 %d 秒" % args.run_sec)
    show("升级后", state)

    # 次级槽 trailer 需要 SPIM0 主动访问，单独开一次会话
    session = ConnectHelper.session_with_chosen_probe(
        unique_id=args.probe, target_override=args.target, frequency=args.swd)
    with session:
        target = session.target
        target.halt()
        flash = W25Q64(target)
        flash.init()
        state["secondary_trailer"] = flash.read(MAGIC_OFF, len(TRAILER_MAGIC))
        state["secondary_head"] = flash.read(SECONDARY_BASE, 16)
        show("次级槽", state)
        target.reset()

    # ---- 阶段 3：主槽逐字节比对 ----
    print("\n主槽内容逐字节比对（决定性证据）")
    session = ConnectHelper.session_with_chosen_probe(
        unique_id=args.probe, target_override=args.target, frequency=args.swd)
    with session:
        target = session.target
        target.halt()
        got = bytes(target.read_memory_block8(PRIMARY_BASE, len(image)))
    diff = [i for i in range(len(image)) if got[i] != image[i]]
    if not diff:
        print("  ✓ %d 字节全部一致（0 差异）" % len(image))
    else:
        print("  ✗ %d / %d 字节不一致，首个差异 @0x%x" % (len(diff), len(image), diff[0]))
        state["byte_identical"] = False
    state.setdefault("byte_identical", not diff)

    # ---- 阶段 4：复位幂等 ----
    print("\n复位幂等性（%d 次）" % args.resets)
    versions = []
    for idx in range(args.resets):
        session = ConnectHelper.session_with_chosen_probe(
            unique_id=args.probe, target_override=args.target, frequency=args.swd)
        with session:
            target = session.target
            target.reset()
        time.sleep(8)
        session = ConnectHelper.session_with_chosen_probe(
            unique_id=args.probe, target_override=args.target, frequency=args.swd)
        with session:
            target = session.target
            target.halt()
            vt = target.read32(VTOR_ADDR)
            ver = decode_image_header(bytes(target.read_memory_block8(PRIMARY_BASE, 32)))["version"]
            w = target.read8(table["w25q64_ready"][0]) if "w25q64_ready" in table else None
            b = target.read8(table["bt_ready"][0]) if "bt_ready" in table else None
            n = target.read8(table["nvs_ready"][0]) if "nvs_ready" in table else None
            target.resume()
        versions.append(ver)
        print("  #%d ver=%s VTOR=0x%08x w25q64=%s bt=%s nvs=%s"
              % (idx + 1, ver, vt, w, b, n))

    # ---- 汇总 ----
    print("\n" + "=" * 70)
    print("判定")
    print("=" * 70)
    checks = verdicts(state, image)
    checks.append(("主槽与镜像逐字节一致", state["byte_identical"]))
    checks.append(("复位幂等（版本号恒定）", len(set(versions)) <= 1))
    failed = 0
    for name, ok in checks:
        print("  [%s] %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            failed += 1
    print("-" * 70)
    print("  结论：%s（%d 项不通过）" % ("全部通过" if not failed else "存在问题", failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
