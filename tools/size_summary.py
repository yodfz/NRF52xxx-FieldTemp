#!/usr/bin/env python3
"""空间治理门禁 + 尺寸汇总（NRF52xxx-FieldTemp）。

这个脚本有**两个职责**，刻意合在一起，避免"报表"和"门禁"两套口径漂移：

1. **门禁（主职责）**：读取真实构建产物，校验空间治理的硬约束，失败时返回非零退出码，
   可直接挂进 CI / pre-commit / 发布检查单。
2. **汇总（次职责）**：把 Zephyr `ram_report`/`rom_report` 的 JSON 拍平成 top-N 表，
   便于回答"谁在吃 RAM/ROM"。

度量口径（全部来自产物本身，不手工维护常量）
------------------------------------------------
- FLASH 用量 = ELF 中所有 PT_LOAD 段的 `p_filesz` 之和（= `zephyr.bin` 字节数）。
- RAM  用量 = ELF 中落在 `sram_primary` 区间内的 PT_LOAD 段 `p_memsz` 之和。
  这两个口径与 Zephyr 构建日志里的 `Memory region ... FLASH/RAM` 完全一致
  （实测 App 19,704 B / MCUboot 10,432 B），但不需要解析日志。

用法
----
    python tools/size_summary.py [build_dir] [options]

`build_dir` 是**应用**构建目录（含 `zephyr/zephyr.elf`），默认 `build-dfu-fix/NRF52xxx-FieldTemp`。

常用参数
--------
    --reserve BYTES        App 槽内预留（默认 6144 = 6 KiB）         [对应红线 R-1]
    --mcuboot-min-free N   MCUboot 最小余量（默认 800）              [对应红线 R-2]
    --ram-warn-pct N       RAM 优化目标百分比（默认 75，超出告警）
    --ram-max-pct N        RAM 临时硬上限百分比（默认 0 = 不启用；须待栈高水位定稿后再设）
    --stack-report FILE    可选的栈高水位 JSON，追加栈余量校验
    --allow-printk         调试构建：把 I3 的 CONFIG_PRINTK=y 从 FAIL 降级为 WARN
    --no-gate              只出报表，永不返回非零退出码
    --json                以 JSON 输出校验结果（便于 CI 消费）
    --quiet               只输出校验结论，不输出 top-N 报表

`build_dir` 也可以直接给 **sysbuild 根目录**（如 `build-v4`），脚本会自动下钻到唯一
含 `zephyr/zephyr.elf` 的子目录（App 镜像目录），并给出提示。

退出码：0 = 全部通过（允许有 WARN）；1 = 存在 FAIL；2 = 用法/前置条件问题。
"""

import argparse
import json
import os
import struct
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - 仅在缺少 PyYAML 时触发
    yaml = None

# ---------------------------------------------------------------- 常量与口径

SRAM_LO, SRAM_HI = 0x20000000, 0x20006000

PASS, WARN, FAIL = "PASS", "WARN", "FAIL"

# 应用构建目录内各产物的相对路径
APP_ELF = os.path.join("zephyr", "zephyr.elf")
APP_BIN = os.path.join("zephyr", "zephyr.bin")
APP_SIGNED_BIN = os.path.join("zephyr", "zephyr.signed.bin")
APP_CONFIG = os.path.join("zephyr", ".config")

# MCUboot 子镜像在 sysbuild 根目录下，与本应用构建目录**同级**（不在其内部）
MCUBOOT_BIN = os.path.join(os.pardir, "mcuboot", "zephyr", "zephyr.bin")
MCUBOOT_ELF = os.path.join(os.pardir, "mcuboot", "zephyr", "zephyr.elf")
MCUBOOT_CONFIG = os.path.join(os.pardir, "mcuboot", "zephyr", ".config")

# 分区表中必须存在的分区（叶子 + span 定义的容器）
REQUIRED_PARTITIONS = [
    "mcuboot", "mcuboot_pad", "app", "mcuboot_primary",
    "mcuboot_secondary", "external_flash", "nvs_storage", "sram_primary",
]

# 外部 Flash（W25Q64）上所有叶子分区必须满足的擦除块粒度
EXT_ALIGN = 4096

# 配置不变量：两个镜像必须一致的 Kconfig
LAYOUT_KCONFIG = "CONFIG_SPI_NOR_FLASH_LAYOUT_PAGE_SIZE"


def resolve_app_build_dir(path):
    """把用户给的目录解析成**应用镜像构建目录**。

    允许直接传 sysbuild 根目录（如 `build-v4`）：此时下钻到唯一含
    `zephyr/zephyr.elf` 的子目录。返回 (app_dir, note)。
    """
    path = os.path.abspath(path)
    if os.path.isfile(os.path.join(path, APP_ELF)):
        return path, None
    cands = []
    try:
        for name in sorted(os.listdir(path)):
            sub = os.path.join(path, name)
            if os.path.isfile(os.path.join(sub, APP_ELF)):
                cands.append(sub)
    except OSError:
        pass
    # sysbuild 根目录下 MCUboot 也是一个子镜像，先排除
    cands = [c for c in cands if os.path.basename(c) != "mcuboot"]
    # 应用镜像有 imgtool 签名产物，MCUboot 子镜像没有 —— 用它消歧
    signed = [c for c in cands if os.path.isfile(os.path.join(c, APP_SIGNED_BIN))]
    if len(signed) == 1:
        cands = signed
    if len(cands) == 1:
        return cands[0], ("已识别 sysbuild 根目录，自动下钻到应用镜像目录：%s"
                          % os.path.basename(cands[0]))
    if not cands:
        sys.exit("build dir not found: %s\n"
                 "  未找到 %s。请给出**应用**构建目录（含 zephyr/zephyr.elf），"
                 "或 sysbuild 根目录（其下只有一个含 ELF 的子目录）。\n"
                 "  若尚未构建，先执行一次完整 sysbuild 构建。"
                 % (path, APP_ELF))
    sys.exit("build dir ambiguous: %s\n"
             "  其下存在多个含 %s 的子目录：%s\n"
             "  请显式指定其中一个。"
             % (path, APP_ELF, ", ".join(os.path.basename(c) for c in cands)))


class Result:
    """收集一条条校验结论。"""

    def __init__(self, no_gate=False, title="空间治理门禁"):
        self.items = []
        self.no_gate = no_gate
        self.title = title

    def add(self, status, name, detail=""):
        self.items.append({"status": status, "check": name, "detail": detail})
        return status

    def ok(self, name, detail=""):
        return self.add(PASS, name, detail)

    def warn(self, name, detail=""):
        return self.add(WARN, name, detail)

    def fail(self, name, detail=""):
        return self.add(FAIL, name, detail)

    @property
    def n_fail(self):
        return sum(1 for i in self.items if i["status"] == FAIL)

    @property
    def n_warn(self):
        return sum(1 for i in self.items if i["status"] == WARN)

    def exit_code(self):
        if self.no_gate:
            return 0
        return 1 if self.n_fail else 0

    def emit(self, as_json=False):
        if as_json:
            print(json.dumps({
                "failures": self.n_fail,
                "warnings": self.n_warn,
                "checks": self.items,
            }, indent=2, ensure_ascii=False))
            return
        print("=" * 78)
        print(self.title)
        print("=" * 78)
        for item in self.items:
            mark = {PASS: "PASS", WARN: "WARN", FAIL: "FAIL"}[item["status"]]
            line = "  [%s] %s" % (mark, item["check"])
            print(line)
            if item["detail"]:
                for chunk in item["detail"].splitlines():
                    print("         %s" % chunk)
        print("-" * 78)
        if self.no_gate:
            print("  结论：报表模式（--no-gate），不判定成败")
        elif self.n_fail:
            print("  结论：FAIL —— %d 项不通过、%d 项告警"
                  % (self.n_fail, self.n_warn))
        else:
            print("  结论：PASS —— 0 项不通过、%d 项告警" % self.n_warn)
        print("=" * 78)
        print()


# ---------------------------------------------------------------- ELF / 产物


def elf_footprint(path, sram_lo=SRAM_LO, sram_hi=SRAM_HI):
    """返回 (flash_bytes, ram_bytes)。

    flash_bytes = 所有 PT_LOAD 段的 p_filesz 之和（等于 .bin 大小）
    ram_bytes   = 落在 [sram_lo, sram_hi) 的 PT_LOAD 段 p_memsz 之和
    """
    with open(path, "rb") as handle:
        blob = handle.read()
    if blob[:4] != b"\x7fELF":
        raise ValueError("not an ELF file: %s" % path)
    phoff = struct.unpack_from("<I", blob, 0x1C)[0]
    phentsize = struct.unpack_from("<H", blob, 0x2A)[0]
    phnum = struct.unpack_from("<H", blob, 0x2C)[0]

    flash = 0
    ram = 0
    for idx in range(phnum):
        off = phoff + idx * phentsize
        p_type, _p_offset, p_vaddr, _p_paddr, p_filesz, p_memsz, _fl, _al = \
            struct.unpack_from("<IIIIIIII", blob, off)
        if p_type != 1:  # PT_LOAD
            continue
        flash += p_filesz
        if sram_lo <= p_vaddr < sram_hi:
            ram += p_memsz
    return flash, ram


def size_of(path):
    return os.path.getsize(path)


def onoff(value):
    """把 .config 取值渲染成 on/off：未设置（None）、'n'、'0' 都显示为 'n'。"""
    if value is None or value in ("n", "0"):
        return "n"
    return str(value)


def read_config(path):
    """把 .config 解析成 dict：'CONFIG_X=y' -> {'CONFIG_X': 'y'}，未设置项为 None。"""
    cfg = {}
    if not os.path.isfile(path):
        return cfg
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.strip()
            if not line:
                continue
            if line.startswith("#") and line.endswith(" is not set"):
                cfg[line.split()[1]] = None
                continue
            if line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            cfg[key.strip()] = value.strip().strip('"')
    return cfg


def load_partitions(build_dir):
    """读取构建根目录的 partitions.yml（PM 生成的分区表）。"""
    path = os.path.join(os.path.dirname(os.path.dirname(
        os.path.abspath(build_dir))), "partitions.yml")
    if not os.path.isfile(path):
        # 兼容：有些布局把 partitions.yml 放在构建根，即 build_dir 的上一级
        alt = os.path.join(build_dir, "..", "partitions.yml")
        path = os.path.normpath(alt)
    if not os.path.isfile(path):
        return None, None
    if yaml is None:
        raise RuntimeError("需要 PyYAML 解析 partitions.yml")
    with open(path, "r", encoding="utf-8") as handle:
        return yaml.safe_load(handle), path


def human(n):
    return "{:,}".format(n)


# ---------------------------------------------------------------- 门禁校验


def gate(build_dir, res, args):
    """
    返回 dict：测量到的关键数值，供报表复用。
    所有失败都记录进 res，不抛异常（前置条件缺失除外）。
    """
    measured = {}

    # --- A. 产物完整性 ---------------------------------------------------
    need = {
        "App ELF": APP_ELF, "App bin": APP_BIN,
        "App signed bin": APP_SIGNED_BIN, "App .config": APP_CONFIG,
        "MCUboot bin": MCUBOOT_BIN, "MCUboot ELF": MCUBOOT_ELF,
        "MCUboot .config": MCUBOOT_CONFIG,
    }
    missing = [k for k, rel in need.items()
               if not os.path.isfile(os.path.join(build_dir, rel))]
    if missing:
        res.fail("A 产物完整性", "缺失：%s\n（先执行完整 sysbuild 构建）" % "、".join(missing))
        return measured
    res.ok("A 产物完整性", "App/MCUboot 的 ELF、bin、signed bin、.config 均在")

    # --- 尺寸口径 --------------------------------------------------------
    app_flash, app_ram = elf_footprint(os.path.join(build_dir, APP_ELF))
    mcb_flash, mcb_ram = elf_footprint(os.path.join(build_dir, MCUBOOT_ELF))
    signed_size = size_of(os.path.join(build_dir, APP_SIGNED_BIN))
    bin_size = size_of(os.path.join(build_dir, APP_BIN))
    measured.update(app_flash=app_flash, app_ram=app_ram,
                    mcuboot_flash=mcb_flash, mcuboot_ram=mcb_ram,
                    signed_size=signed_size, bin_size=bin_size)

    if signed_size != bin_size:
        res.ok("B 签名产物一致性",
               "signed bin %s B = bin %s B + 镜像头/签名；ELF FLASH %s B"
               % (human(signed_size), human(bin_size), human(app_flash)))
    else:
        res.warn("B 签名产物一致性",
                 "signed bin 与 bin 等大（%s B），请确认签名步骤确实执行" % human(signed_size))

    if app_flash != bin_size:
        res.warn("B 尺寸口径",
                 "ELF FLASH 合计 %s B 与 .bin %s B 不等，请复核 objcopy 参数"
                 % (human(app_flash), human(bin_size)))

    # --- C. 分区表 ------------------------------------------------------
    parts, parts_path = load_partitions(build_dir)
    if parts is None:
        res.fail("C 分区表", "未找到 partitions.yml（PM 未生成？检查 pm_static.yml 与 overlay）")
        return measured
    res.ok("C 分区表", "已读取 %s（%d 个分区）" % (parts_path, len(parts)))

    for name in REQUIRED_PARTITIONS:
        if name not in parts:
            res.fail("C 分区表-必需项", "缺少分区 `%s`" % name)
    if any(name not in parts for name in REQUIRED_PARTITIONS):
        return measured

    # C1. 只有 Partition Manager 用 span 明确定义的条目才是容器。
    # 其余条目一律按叶子检查；不要用“包含另一分区”之类的启发式掩盖重叠。
    def rng(key):
        p = parts[key]
        start = p.get("address", 0)
        return start, start + p.get("size", 0)

    def bucket(key):
        p = parts[key]
        return (p.get("region"), p.get("device"))

    containers = {name for name, part in parts.items() if part.get("span")}
    leaves = [name for name in parts if name not in containers]

    # 只在「同 region 同 device」的叶子之间查重叠，避免跨介质误报

    overlaps = []
    for i, a in enumerate(leaves):
        for b in leaves[i + 1:]:
            if bucket(a) != bucket(b):
                continue
            alo, ahi = rng(a)
            blo, bhi = rng(b)
            if alo < bhi and blo < ahi:
                overlaps.append("%s[0x%x-0x%x] 与 %s[0x%x-0x%x]"
                                % (a, alo, ahi, b, blo, bhi))
    if overlaps:
        res.fail("C1 叶子分区不重叠", "\n".join(overlaps))
    else:
        detail = "%d 个叶子分区" % len(leaves)
        if containers:
            detail += "；span 定义的容器已排除：%s" % "、".join(sorted(containers))
        res.ok("C1 叶子分区不重叠", detail)

    # C2. mcuboot_primary 的 span 与实际地址自洽
    prim_lo, prim_hi = rng("mcuboot_primary")
    pad_lo, pad_hi = rng("mcuboot_pad")
    app_lo, app_hi = rng("app")
    if pad_lo == prim_lo and pad_hi == app_lo and app_hi == prim_hi:
        res.ok("C2 主槽 span 自洽",
               "mcuboot_pad[0x%x-0x%x] + app[0x%x-0x%x] == mcuboot_primary[0x%x-0x%x]"
               % (pad_lo, pad_hi, app_lo, app_hi, prim_lo, prim_hi))
    else:
        res.fail("C2 主槽 span 自洽",
                 "pad[0x%x-0x%x] app[0x%x-0x%x] 无法拼成 primary[0x%x-0x%x]"
                 % (pad_lo, pad_hi, app_lo, app_hi, prim_lo, prim_hi))

    # --- D. 两槽等大（overwrite-only 强耦合）-----------------------------
    sec_lo, sec_hi = rng("mcuboot_secondary")
    prim_size = parts["mcuboot_primary"]["size"]
    sec_size = parts["mcuboot_secondary"]["size"]
    if prim_size == sec_size:
        res.ok("D 主次槽等大",
               "mcuboot_primary == mcuboot_secondary == %s B（0x%X）"
               % (human(prim_size), prim_size))
    else:
        res.fail("D 主次槽等大",
                 "primary %s B != secondary %s B —— 改一个必须同步改另一个"
                 % (human(prim_size), human(sec_size)))

    # --- E. MCUboot 余量 -------------------------------------------------
    mcb_region = parts["mcuboot"]["size"]
    mcb_free = mcb_region - mcb_flash
    measured["mcuboot_free"] = mcb_free
    detail = ("MCUboot %s B / 区域 %s B（%.2f%%），余量 %s B（下限 %s B）"
              % (human(mcb_flash), human(mcb_region),
                 100.0 * mcb_flash / mcb_region, human(mcb_free),
                 human(args.mcuboot_min_free)))
    if mcb_free < 0:
        res.fail("E MCUboot 余量", detail)
    elif mcb_free < args.mcuboot_min_free:
        res.fail("E MCUboot 余量", detail)
    elif mcb_free < args.mcuboot_min_free * 2:
        res.warn("E MCUboot 余量", detail)
    else:
        res.ok("E MCUboot 余量", detail)

    # --- F. App 签名镜像 vs 槽容量 ---------------------------------------
    # 关键口径：签名镜像装在**完整主槽**（mcuboot_primary = app 区 + 512 B pad）里，
    # 因此必须与 prim_size 比较，不能用 app 区容量（会少算 512 B）。
    app_region = parts["app"]["size"]
    nominal = prim_size - signed_size
    limit = prim_size - args.reserve
    detail = ("签名镜像 %s B\n"
              "对比完整主槽 mcuboot_primary %s B：名义余量 %s B（%.2f%%）\n"
              "对比 app 子区 %s B：余量 %s B（仅作参考，非门禁口径）\n"
              "红线：预留 %s B，故上限 %s B"
              % (human(signed_size), human(prim_size), human(nominal),
                 100.0 * signed_size / prim_size,
                 human(app_region), human(app_region - signed_size),
                 human(args.reserve), human(limit)))
    measured["app_signed_nominal_free"] = nominal
    if signed_size > limit:
        res.fail("F App 签名镜像 ≤ 槽容量-预留", detail)
    elif nominal < args.reserve * 2:
        res.warn("F App 签名镜像 ≤ 槽容量-预留", detail)
    else:
        res.ok("F App 签名镜像 ≤ 槽容量-预留", detail)

    # --- G. RAM ----------------------------------------------------------
    # 依据评审：不设凭空的硬上限。>ram_warn_pct 只告警；
    # 真正的安全判据是运行时栈高水位（见 J 项与 --stack-report）。
    # ram_max_pct > 0 时才额外启用一条临时硬上限。
    sram_size = parts["sram_primary"]["size"]
    ram_pct = 100.0 * app_ram / sram_size
    measured.update(sram_size=sram_size, ram_pct=ram_pct)
    hard = ("临时硬上限 %g%%" % args.ram_max_pct) if args.ram_max_pct > 0 else \
        "未设硬上限（待栈高水位数据定稿）"
    detail = ("App RAM %s B / %s B = %.2f%%\n优化目标 ≤%g%%，%s"
              % (human(app_ram), human(sram_size), ram_pct,
                 args.ram_warn_pct, hard))
    if args.ram_max_pct > 0 and ram_pct > args.ram_max_pct:
        res.fail("G App RAM 用量", detail)
    elif ram_pct > args.ram_warn_pct:
        res.warn("G App RAM 用量", detail)
    else:
        res.ok("G App RAM 用量", detail)

    # --- H. 外置 Flash 叶子分区对齐与边界 --------------------------------
    ext_leaves = [n for n in leaves
                  if parts[n].get("region") == "external_flash"]
    bad_align, bad_order = [], []
    for name in ext_leaves:
        lo, hi = rng(name)
        size = parts[name]["size"]
        if lo % EXT_ALIGN or size % EXT_ALIGN:
            bad_align.append("%s addr=0x%x size=0x%x 未按 %d B 对齐"
                             % (name, lo, size, EXT_ALIGN))
    nvs_lo, nvs_hi = rng("nvs_storage")
    for name in ext_leaves:
        lo, hi = rng(name)
        if name not in ("mcuboot_secondary", "nvs_storage") and lo < nvs_hi:
            bad_order.append("%s 起始 0x%x 侵入 secondary+NVS 保护区 [0x%x,0x%x)"
                             % (name, lo, sec_lo, nvs_hi))
    if bad_align or bad_order:
        res.fail("H 外置 Flash 对齐与边界", "\n".join(bad_align + bad_order))
    else:
        res.ok("H 外置 Flash 对齐与边界",
               "%s 全部 %d B 对齐；secondary+NVS [0x%x,0x%x) 未被其他分区侵入"
               % ("、".join(sorted(ext_leaves)), EXT_ALIGN, sec_lo, nvs_hi))

    # --- I. 配置不变量 ---------------------------------------------------
    app_cfg = read_config(os.path.join(build_dir, APP_CONFIG))
    mcb_cfg = read_config(os.path.join(build_dir, MCUBOOT_CONFIG))
    measured["app_layout_page"] = app_cfg.get(LAYOUT_KCONFIG)
    measured["mcuboot_layout_page"] = mcb_cfg.get(LAYOUT_KCONFIG)

    # I1. 两个镜像的外置 Flash 布局页必须都等于 4 KiB 擦除块
    problems = []
    for label, cfg in (("App", app_cfg), ("MCUboot", mcb_cfg)):
        value = cfg.get(LAYOUT_KCONFIG)
        if value != str(EXT_ALIGN):
            problems.append("%s 的 %s=%s（期望 %d）"
                            % (label, LAYOUT_KCONFIG, value, EXT_ALIGN))
    if problems:
        res.fail("I1 布局页 4KiB 对齐",
                 "\n".join(problems) + "\n"
                 "65536 会让 nvs_mount() 的 `sector_size % info.size` 判为非法，"
                 "NVS 将永远挂载失败。")
    else:
        res.ok("I1 布局页 4KiB 对齐",
               "App 与 MCUboot 的 %s 均为 %d" % (LAYOUT_KCONFIG, EXT_ALIGN))

    # I2. MCUboot 必须处于 overwrite-only 升级模式（与原设计一致）
    upgrade_only = mcb_cfg.get("CONFIG_BOOT_UPGRADE_ONLY")
    if upgrade_only == "y":
        res.ok("I2 MCUboot 升级模式",
               "CONFIG_BOOT_UPGRADE_ONLY=y（overwrite-only，次级槽->主槽单向搬运）")
    else:
        res.warn("I2 MCUboot 升级模式",
                 "CONFIG_BOOT_UPGRADE_ONLY=%s，与既有 DFU 验证结论不匹配，请确认" % upgrade_only)

    # I3. 发布态不应编译 printk。
    # 这是**硬门禁**：一旦被 CONFIG_NCS_BOOT_BANNER 隐式 select 打开，
    # App ROM 会立刻回涨约 17,492 B（本项目的真实事故，见 §7.4）。
    # 调试构建可用 --allow-printk 降级为 WARN。
    printk = app_cfg.get("CONFIG_PRINTK")
    banner = app_cfg.get("CONFIG_NCS_BOOT_BANNER")
    console = app_cfg.get("CONFIG_CONSOLE")
    if printk == "y":
        detail = ("CONFIG_PRINTK=y（CONFIG_CONSOLE=%s / NCS_BOOT_BANNER=%s）。\n"
                  "发布镜像实测会多占约 17,492 B ROM；"
                  "若 CONFIG_NCS_BOOT_BANNER=y 会 select PRINTK，单写 CONFIG_PRINTK=n 无效，"
                  "必须同时置 NCS_BOOT_BANNER=n 与 EARLY_CONSOLE=n。"
                  % (console, banner))
        if args.allow_printk:
            res.warn("I3 发布态 printk", detail)
        else:
            res.fail("I3 发布态 printk", detail)
    else:
        res.ok("I3 发布态 printk",
               "CONFIG_PRINTK 已关闭（NCS_BOOT_BANNER=%s / EARLY_CONSOLE=%s）"
               % (onoff(banner), onoff(app_cfg.get("CONFIG_EARLY_CONSOLE"))))

    # I4. 内部 RAM 分区尺寸与 SoC 一致
    if sram_size == 0x6000:
        res.ok("I4 SRAM 分区尺寸", "sram_primary = %s B（24 KiB，nRF52810）"
               % human(sram_size))
    else:
        res.fail("I4 SRAM 分区尺寸",
                 "sram_primary = %s B，与 nRF52810 的 24 KiB 不符" % human(sram_size))

    # --- J. 可选的栈高水位 ------------------------------------------------
    if args.stack_report:
        check_stack_report(args.stack_report, res, args)

    return measured


def check_stack_report(path, res, args):
    """消费栈高水位 JSON：{'threads': [{'name':..,'size':..,'peak':..}]}"""
    if not os.path.isfile(path):
        res.warn("J 栈高水位", "未找到 %s，跳过" % path)
        return
    with open(path, "r", encoding="utf-8") as handle:
        doc = json.load(handle)
    threads = doc.get("threads") or []
    if not threads:
        res.warn("J 栈高水位", "%s 中没有 threads 记录" % path)
        return
    worst = min(threads, key=lambda t: t["size"] - t.get("peak", 0))
    lines = ["%-22s %8s %8s %8s %7s" % ("线程", "栈大小", "峰值", "余量", "占比")]
    for t in sorted(threads, key=lambda x: -(x.get("peak", 0) / max(x["size"], 1))):
        free = t["size"] - t.get("peak", 0)
        lines.append("%-22s %8d %8d %8d %6.1f%%"
                     % (t["name"], t["size"], t.get("peak", 0), free,
                        100.0 * t.get("peak", 0) / max(t["size"], 1)))
    detail = "\n".join(lines)
    worst_free = worst["size"] - worst.get("peak", 0)
    if worst_free < 64:
        res.fail("J 栈高水位", detail + "\n最紧张线程（按绝对余量）`%s` 仅余 %d B"
                 % (worst["name"], worst_free))
    elif worst_free < 128:
        res.warn("J 栈高水位", detail + "\n最紧张线程（按绝对余量）`%s` 余量 %d B，偏紧"
                 % (worst["name"], worst_free))
    else:
        res.ok("J 栈高水位", detail)


# ---------------------------------------------------------------- 报表


def flatten(node, path, out):
    name = node.get("name", "")
    here = path + "/" + name if path else name
    out.append((node.get("size", 0), here))
    for child in node.get("children") or []:
        flatten(child, here, out)


def report(title, json_path, total_hint, max_depth, min_bytes, top):
    if not os.path.isfile(json_path):
        print("   （缺少 %s —— 先执行 `ninja <ram|rom>_report`）" % json_path)
        return
    with open(json_path, "r", encoding="utf-8") as handle:
        doc = json.load(handle)
    root = doc.get("symbols") or doc
    items = []
    flatten(root, "", items)
    items.sort(reverse=True)

    total = total_hint or doc.get("total_size") or sum(s for s, _ in items)
    print("=" * 78)
    print("%s   total = %s B" % (title, human(total)))
    print("-" * 78)
    shown = set()
    for size, path in items:
        if size < min_bytes or path in shown or path.count("/") > max_depth:
            continue
        shown.add(path)
        if len(shown) > top:
            break
        print("  %-56s %8s  %5.2f%%"
              % (path[-56:], human(size), 100.0 * size / total))
    print()


# ---------------------------------------------------------------- main


def main():
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("build_dir", nargs="?", default=None,
                        help="应用构建目录（含 zephyr/zephyr.elf），"
                             "默认 <repo>/build-dfu-fix/NRF52xxx-FieldTemp")
    parser.add_argument("--reserve", type=int, default=6144,
                        help="App 槽内预留字节数（默认 6144 = 6 KiB）")
    parser.add_argument("--mcuboot-min-free", type=int, default=800,
                        help="MCUboot 最小余量（默认 800 B）")
    parser.add_argument("--ram-warn-pct", type=float, default=75.0,
                        help="RAM 优化目标百分比（默认 75，超出仅告警）")
    parser.add_argument("--ram-max-pct", type=float, default=0.0,
                        help="RAM 临时硬上限百分比；0（默认）= 不启用。"
                             "依据评审，硬上限须由运行时栈高水位数据定稿后再设。")
    parser.add_argument("--stack-report", default=None,
                        help="可选的栈高水位 JSON 路径")
    parser.add_argument("--allow-printk", action="store_true",
                        help="调试构建：把 I3 的 CONFIG_PRINTK=y 从 FAIL 降级为 WARN")
    parser.add_argument("--depth", type=int, default=4, help="报表最大路径深度")
    parser.add_argument("--min", dest="min_bytes", type=int, default=400,
                        help="报表隐藏小于该值的条目")
    parser.add_argument("--top", type=int, default=28, help="每张报表最大行数")
    parser.add_argument("--no-gate", action="store_true",
                        help="只出报表，不判定成败")
    parser.add_argument("--json", action="store_true",
                        help="以 JSON 输出校验结论")
    parser.add_argument("--quiet", action="store_true",
                        help="不输出 top-N 报表")
    args = parser.parse_args()

    if args.build_dir:
        build_dir = os.path.abspath(args.build_dir)
    else:
        repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        build_dir = os.path.join(repo, "build-dfu-fix", "NRF52xxx-FieldTemp")

    if not os.path.isdir(build_dir):
        sys.exit("build dir not found: %s\n"
                 "（先执行一次 sysbuild 构建，或用位置参数指定构建目录）" % build_dir)

    build_dir, note = resolve_app_build_dir(build_dir)
    if note:
        print("提示：" + note, file=sys.stderr)

    res = Result(no_gate=args.no_gate)

    if args.stack_report:
        pass  # 栈校验在 gate() 内部执行，以便与其它结论一起汇总

    try:
        measured = gate(build_dir, res, args)
    except RuntimeError as exc:
        sys.exit("前置条件不满足：%s" % exc)

    res.emit(as_json=args.json)

    if not args.quiet and not args.json:
        report("APP RAM", os.path.join(build_dir, "ram.json"),
               measured.get("app_ram"), args.depth, args.min_bytes, args.top)
        report("APP ROM", os.path.join(build_dir, "rom.json"),
               measured.get("app_flash"), args.depth, args.min_bytes, args.top)

    return res.exit_code()


if __name__ == "__main__":
    sys.exit(main())
