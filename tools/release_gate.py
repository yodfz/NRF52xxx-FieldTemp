#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""发布门禁：镜像版本号一致性校验（VERSION 单一版本源）。

为什么需要这个门禁
------------------
sysbuild 产出的 `zephyr.signed.bin` 曾长期带着 `0.0.0+0` 的占位版本号。根因在
`CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION` 的 Kconfig 默认值：

    default "$(APP_VERSION_TWEAK_STRING)" if "$(VERSION_MAJOR)" != ""
    default "0.0.0+0"

工程根没有 `VERSION` 文件 → `VERSION_MAJOR` 为空 → 落到硬编码的 `0.0.0+0`。

overwrite-only 模式下 MCUboot 不看版本号，所以升级"看起来"一切正常，但：
  * mcumgr 的 image list / 版本比较失效；
  * 降级保护形同虚设；
  * 现场无法从镜像头判断设备跑的是哪一版。

于是历史上只能靠"手工跑 imgtool 加 --version"另存出 `zephyr.signed.vN.bin` 当成品
（`zephyr.signed.v2/v3/v4.bin` 就是这么来的）。本门禁终结这个流程：
**发布对象永远是 sysbuild 原生产出的 `zephyr.signed.bin`，版本由 VERSION 文件决定。**

检查项
------
  R1  签名镜像版本不得为 0.0.0+0（占位版本）
  R2  签名镜像版本必须与工程根 VERSION 文件**完全相同**
  R5  签名版本必须来自 VERSION（.config 的 CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION 与之一致）
  R3  签名镜像版本必须**高于上一生产版本**（基线文件）
  R4  禁止把手工生成的 zephyr.signed.vN.bin 当作发布成品（存在即 FAIL）

用法
----
  python tools/release_gate.py build-v6                  # 校验
  python tools/release_gate.py build-v6 --json           # CI 消费
  python tools/release_gate.py build-v6 --promote        # 全部通过后把基线推进到当前版本
  python tools/release_gate.py build-v6 --baseline other-baseline.json

退出码：0 = 通过，1 = 有 FAIL。
"""

import argparse
import glob
import json
import os
import re
import struct
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from size_summary import Result, resolve_app_build_dir  # noqa: E402

# MCUboot 镜像头魔数（与 imgtool 生成的 ih_magic 一致）
IMAGE_MAGIC = b"\x3d\xb8\xf3\x96"
IH_MAGIC_OFF = 0
IH_HDR_SIZE_OFF = 8
IH_IMG_SIZE_OFF = 12
IH_VER_OFF = 20          # ih_ver = major(u8) minor(u8) revision(u16) build(u32)

APP_SIGNED_BIN = os.path.join("zephyr", "zephyr.signed.bin")
APP_CONFIG = os.path.join("zephyr", ".config")

DEFAULT_BASELINE = "tools/release-baseline.json"

# 手工签名残留的文件名形态：zephyr.signed.v2.bin / zephyr.signed.v4.hex ...
LEGACY_SIGNED_GLOB = "zephyr.signed.v*"


# ------------------------------------------------------------------ 版本解析


def parse_version_file(path):
    """解析 Zephyr 风格的 VERSION 文件，返回 (major, minor, patch, tweak)。

    正则与 zephyr/cmake/modules/version.cmake 保持一致，避免"文档写的"和
    "构建实际用的"出现差异。
    """
    try:
        with open(path, "r", encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        return None

    def grab(name):
        match = re.search(r"%s\s*=\s*(\d+)" % name, text)
        return int(match.group(1)) if match else None

    fields = (grab("VERSION_MAJOR"), grab("VERSION_MINOR"),
              grab("PATCHLEVEL"), grab("VERSION_TWEAK"))
    if any(f is None for f in fields):
        return None
    return fields


def parse_version_string(text):
    """把 "1.0.4" / "1.0.4+0" 解析成 (major, minor, patch, tweak)。"""
    if not text:
        return None
    match = re.match(r"^\s*(\d+)\.(\d+)\.(\d+)(?:\+(\d+))?\s*$", str(text))
    if not match:
        return None
    major, minor, patch, tweak = match.groups()
    return (int(major), int(minor), int(patch), int(tweak or 0))


def vstr(ver):
    """imgtool 版本串形态：major.minor.patch+tweak"""
    return "%d.%d.%d+%d" % ver


def vshort(ver):
    return "%d.%d.%d" % ver[:3]


def read_image_version(path):
    """读签名镜像头的 ih_ver。

    返回 ((major, minor, patch, tweak), img_size)，魔数不匹配或读不到时返回 None。
    """
    try:
        with open(path, "rb") as handle:
            head = handle.read(IH_VER_OFF + 8)
    except OSError:
        return None
    if len(head) < IH_VER_OFF + 8 or head[IH_MAGIC_OFF:IH_MAGIC_OFF + 4] != IMAGE_MAGIC:
        return None
    img_size = struct.unpack_from("<I", head, IH_IMG_SIZE_OFF)[0]
    major, minor, revision, build = struct.unpack_from("<BBHI", head, IH_VER_OFF)
    return (major, minor, revision, build), img_size


def read_signed_config_version(app_dir):
    """从 .config 里取 CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION 的值。"""
    path = os.path.join(app_dir, APP_CONFIG)
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if line.startswith("CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION="):
                    return line.split("=", 1)[1].strip().strip('"')
    except OSError:
        return None
    return None


def find_legacy_signed(app_dir):
    """找出手工签名的残留产物（zephyr.signed.vN.*）。"""
    hits = []
    for pattern in (LEGACY_SIGNED_GLOB,):
        hits.extend(glob.glob(os.path.join(app_dir, "zephyr", pattern)))
    return sorted(os.path.basename(p) for p in hits)


def load_baseline(path):
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except OSError:
        return None, None
    except ValueError as exc:
        return None, "基线文件不是合法 JSON：%s" % exc
    return data, None


def save_baseline(path, version_string, note):
    data = {
        "version": version_string,
        "note": note,
        "updated": date.today().isoformat(),
    }
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, indent=2, ensure_ascii=False)
        handle.write("\n")


# ---------------------------------------------------------------------- 门禁


def gate(project_dir, build_dir, baseline_path, res, promote=False):
    app_dir, note = resolve_app_build_dir(build_dir)
    if note:
        res.ok("构建目录", note)

    # ---- 期望版本：工程根 VERSION 文件 -------------------------------------
    version_file = os.path.join(project_dir, "VERSION")
    expected = parse_version_file(version_file)
    if expected is None:
        res.fail("R2 版本源 VERSION",
                 "未找到或无法解析 %s\n"
                 "  Zephyr 需要工程根的 VERSION 文件来设置镜像版本；缺失时\n"
                 "  CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION 会退回硬编码的 0.0.0+0。" % version_file)
    else:
        res.ok("R2 版本源 VERSION",
               "%s → %s（major.minor.patch+tweak）" % (version_file, vstr(expected)))

    # ---- 实际签名产物 ------------------------------------------------------
    signed = os.path.join(app_dir, APP_SIGNED_BIN)
    parsed = read_image_version(signed)
    if parsed is None:
        res.fail("R1 签名镜像版本",
                 "无法读取或魔数不匹配：%s\n"
                 "  期望 MCUboot 镜像头（ih_magic = 3d b8 f3 96）。" % signed)
        return app_dir
    actual, img_size = parsed

    if actual == (0, 0, 0, 0):
        res.fail("R1 签名镜像版本",
                 "zephyr.signed.bin 的 ih_ver = %s（占位版本）\n"
                 "  这是 CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION 落到默认值导致的。\n"
                 "  排查：① 工程根是否存在 VERSION 文件；② prj.conf 是否硬编码覆盖了它；\n"
                 "        ③ 是否做过一次干净重建（版本号在 CMake 配置期读取）。" % vstr(actual))
    else:
        res.ok("R1 签名镜像版本",
               "ih_ver = %s，imgsz = %d B" % (vstr(actual), img_size))

    # ---- R2 与 VERSION 完全一致 -------------------------------------------
    if expected is not None:
        if actual == expected:
            res.ok("R2 版本与 VERSION 一致", "%s == %s" % (vstr(actual), vstr(expected)))
        else:
            res.fail("R2 版本与 VERSION 一致",
                     "签名镜像 %s != VERSION %s\n"
                     "  两者必须完全相同。差异通常来自：构建目录是旧的（版本号在 CMake\n"
                     "  配置期读取，改了 VERSION 必须重新配置/重建），或有人在 prj.conf 里\n"
                     "  硬编码了 CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION。" % (vstr(actual), vstr(expected)))

    # ---- R5 签名版本确实来自 VERSION（机制校验）----------------------------
    cfg_version = read_signed_config_version(app_dir)
    if cfg_version is None:
        res.warn("R5 签名参数来源",
                 "未在 .config 中找到 CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION（可能是旧构建目录）")
    elif parse_version_string(cfg_version) == actual:
        if expected is not None and parse_version_string(cfg_version) != expected:
            res.fail("R5 签名参数来源",
                     "CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION=%s 与 VERSION %s 不一致\n"
                     "  说明有硬编码覆盖，版本源没有唯一化。" % (cfg_version, vstr(expected)))
        else:
            res.ok("R5 签名参数来源",
                   "CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION = %s（与镜像头一致，源自 VERSION）"
                   % cfg_version)
    else:
        res.fail("R5 签名参数来源",
                 "CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION=%s 与镜像头 %s 不一致 —— "
                 "构建状态异常，请干净重建。" % (cfg_version, vstr(actual)))

    for proj_conf in ("prj.conf", os.path.join("sysbuild", "mcuboot.conf"), "sysbuild.conf"):
        path = os.path.join(project_dir, proj_conf)
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                body = handle.read()
        except OSError:
            continue
        if re.search(r"^\s*CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION\s*=", body, re.M):
            res.warn("R5 版本源唯一性",
                     "%s 里显式设置了 CONFIG_MCUBOOT_IMGTOOL_SIGN_VERSION。\n"
                     "  版本唯一来源应是工程根 VERSION 文件，请删除这行，避免两处各说各话。"
                     % proj_conf)

    # ---- R3 高于上一生产版本 ----------------------------------------------
    baseline, err = load_baseline(baseline_path)
    if err:
        res.fail("R3 高于上一生产版本", err)
    elif baseline is None:
        res.warn("R3 高于上一生产版本",
                 "基线文件不存在：%s\n"
                 "  首次使用请用 --promote 建立基线（应指向**当前已发布**的版本）。"
                 % baseline_path)
    else:
        base_ver = parse_version_string(baseline.get("version"))
        if base_ver is None:
            res.fail("R3 高于上一生产版本",
                     "基线文件缺少可解析的 version 字段：%s" % baseline_path)
        elif actual > base_ver:
            res.ok("R3 高于上一生产版本",
                   "%s > 基线 %s（%s）"
                   % (vstr(actual), vstr(base_ver), baseline.get("note", "")[:60]))
        elif actual == base_ver:
            res.fail("R3 高于上一生产版本",
                     "候选版本 %s 与上一生产版本相同 —— 未抬版本号，不能作为新发布。\n"
                     "  发布前请先抬工程根 VERSION 文件。" % vstr(actual))
        else:
            res.fail("R3 高于上一生产版本",
                     "候选版本 %s **低于**上一生产版本 %s —— 会被降级保护拦下，"
                     "或（overwrite-only 下）静默降级现场设备。"
                     % (vstr(actual), vstr(base_ver)))

    # ---- R4 禁止手工签名产物当成品 ----------------------------------------
    legacy = find_legacy_signed(app_dir)
    if legacy:
        res.fail("R4 无手工签名残留",
                 "发现手工签名的历史产物：%s\n"
                 "  发布对象只能是 sysbuild 原生产出的 zephyr.signed.bin（及其 .hex）。\n"
                 "  这些 zephyr.signed.vN.bin 是 VERSION 单一版本源落地之前的变通做法，\n"
                 "  继续沿用会让\"谁才是正确成品\"重新变得含糊。请删除后重新构建。"
                 % ", ".join(legacy))
    else:
        res.ok("R4 无手工签名残留",
               "未发现 %s（发布对象 = zephyr.signed.bin）" % LEGACY_SIGNED_GLOB)

    # ---- 可选：把基线推进到当前版本 ---------------------------------------
    if promote and not res.n_fail and expected is not None:
        save_baseline(baseline_path,
                      vshort(expected),
                      "由 release_gate.py --promote 推进（镜像版本 %s）" % vstr(expected))
        res.ok("基线推进", "%s → %s" % (baseline_path, vshort(expected)))

    return app_dir


def main():
    parser = argparse.ArgumentParser(
        description="发布门禁：校验签名镜像版本号（VERSION 单一版本源）")
    parser.add_argument("build_dir", nargs="?",
                        help="sysbuild 根目录或应用镜像构建目录")
    parser.add_argument("--project", default=None,
                        help="工程根目录（默认：本脚本所在目录的上一级）")
    parser.add_argument("--baseline", default=None,
                        help="基线文件（默认：<工程根>/%s）" % DEFAULT_BASELINE)
    parser.add_argument("--promote", action="store_true",
                        help="全部检查通过后，把基线推进到当前 VERSION 版本")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--quiet", action="store_true", help="只输出结论")
    args = parser.parse_args()

    project = os.path.abspath(args.project) if args.project else \
        os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    baseline = os.path.abspath(args.baseline) if args.baseline else \
        os.path.join(project, DEFAULT_BASELINE)

    if not args.build_dir:
        parser.error("需要给出构建目录（sysbuild 根目录或应用镜像目录）")

    res = Result(title="发布门禁（镜像版本号）")
    gate(project, args.build_dir, baseline, res, promote=args.promote)

    if args.json:
        res.emit(as_json=True)
    elif args.quiet:
        print("%s：FAIL %d / WARN %d" % ("发布门禁", res.n_fail, res.n_warn))
    else:
        res.emit()
    return res.exit_code()


if __name__ == "__main__":
    sys.exit(main())
