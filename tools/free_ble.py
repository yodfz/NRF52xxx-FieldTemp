#!/usr/bin/env python3
"""PC 侧 BLE 测试前的「确认 BLE 已真正释放」检查（必须用它，不要只 force-stop）。

为什么需要这个脚本
------------------
本设备 `CONFIG_BT_MAX_CONN=1`。手机 App 有前台保活且**会自动重连**，
实测 `adb shell am force-stop` 之后进程仍会被拉起（`pidof` 能查到 PID），
并再次尝试连接。此时 PC 侧的 BLE 测试会莫名其妙地连不上、或中途掉线，
很容易被误判成"固件/链路有问题"。

判定「已释放」的两个必要条件（都要满足）
--------------------------------------
1. **手机进程确实没了**：`pidof <pkg>` 为空。
2. **设备在广播**：本设备被连接后会停止广播、断开后由 `ble_disconnected_cb` 重新广播，
   所以「扫到设备在广播」等价于「当前没有 central 占着连接」。

用法
----
    python tools/free_ble.py                    # 默认 6 次尝试
    python tools/free_ble.py --pkg xxx --tries 10
    python tools/free_ble.py --json out.json

需要 venv（有 bleak）；adb 走 LOCALAPPDATA\\Android\\Sdk\\platform-tools\\adb.exe。
退出码：0 = 已释放；1 = 未释放（会打印最后一次的证据）。
"""

import argparse
import asyncio
import os
import subprocess
import sys
import time

PKG_DEFAULT = "com.example.pandatemperature"
DEVICE_NAME_PREFIX = "PandaTemp"


def adb_path():
    p = os.path.join(os.environ.get("LOCALAPPDATA", ""),
                     "Android", "Sdk", "platform-tools", "adb.exe")
    return p if os.path.isfile(p) else "adb"


def adb(*args, timeout=20):
    return subprocess.run([adb_path(), *args], capture_output=True,
                          text=True, errors="replace", timeout=timeout)


def app_pids(pkg):
    r = adb("shell", "pidof", pkg)
    return [x for x in (r.stdout or "").split() if x.strip().isdigit()]


def scan_device(timeout=8.0):
    """返回 (是否扫到, 名字, rssi)。"""
    from bleak import BleakScanner

    async def _run():
        found = await BleakScanner.discover(timeout=timeout, return_adv=True)
        items = found.values() if isinstance(found, dict) else found
        for it in items:
            dev = it[0] if isinstance(it, (tuple, list)) else it
            name = getattr(dev, "name", None) or ""
            if name.startswith(DEVICE_NAME_PREFIX):
                return True, name, getattr(dev, "rssi", None)
        return False, None, None

    return asyncio.run(_run())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pkg", default=PKG_DEFAULT)
    ap.add_argument("--tries", type=int, default=6)
    ap.add_argument("--gap", type=float, default=4.0, help="每次尝试之间的间隔秒数")
    args = ap.parse_args()

    print("目标包名: %s" % args.pkg)
    for i in range(1, args.tries + 1):
        adb("shell", "am", "force-stop", args.pkg)
        time.sleep(2.0)

        pids = app_pids(args.pkg)
        seen, name, rssi = scan_device()

        print("[第 %d/%d 次] 进程=%s  广播=%s"
              % (i, args.tries,
                 ("无" if not pids else "仍在运行 %s" % pids),
                 ("%s (rssi=%s)" % (name, rssi) if seen else "未扫到")))

        if not pids and seen:
            print("\n✅ BLE 已释放：手机进程已退出，且设备在广播"
                  "（MAX_CONN=1 下「在广播」即「未被连接」）")
            return 0

        if i < args.tries:
            time.sleep(args.gap)

    print("\n❌ 未能确认 BLE 已释放 —— **不要**在这种情况下开始 PC 侧 BLE 测试，"
          "否则会把「连不上 / 掉线」误判成固件问题。")
    if app_pids(args.pkg):
        print("   · 手机侧 App 仍在运行（前台保活会自动拉起）→ 可在手机上手动「强行停止」，"
              "或临时关闭该 App 的自启动/前台服务。")
    else:
        print("   · 手机进程已退出但设备仍未广播 → 可能仍残留连接，等超时（最长 4.2 s 监督超时）"
              "或对设备复位一次。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
