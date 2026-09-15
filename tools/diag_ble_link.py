#!/usr/bin/env python3
"""BLE 链路稳定性诊断：连上后逐秒观察是否掉线，并打印服务/特征。"""
import asyncio
import time

from bleak import BleakClient, BleakScanner

PREFIX = "PandaTemp"
UUID_STATUS = "12340022-1234-5678-1234-56789abcdef0"


async def main():
    print("== 扫描 ==")
    found = await BleakScanner.discover(timeout=12.0, return_adv=True)
    items = found.values() if isinstance(found, dict) else found
    dev = None
    for it in items:
        d = it[0] if isinstance(it, (tuple, list)) else it
        adv = it[1] if isinstance(it, (tuple, list)) and len(it) > 1 else None
        n = getattr(d, "name", None) or ""
        if n.startswith(PREFIX):
            dev = d
            print("  找到 %s  addr=%s  rssi=%s" % (n, d.address, getattr(adv, "rssi", "?")))
            break
    if dev is None:
        print("  ❌ 未找到")
        return 1

    print("== 连接（强制非缓存 GATT 发现）==")
    c = BleakClient(dev, timeout=30.0, winrt={"use_cached_services": False})
    await c.connect()
    print("  connected=%s" % c.is_connected)

    print("== 服务 ==")
    for s in c.services:
        print("  service %s" % s.uuid)
        for ch in s.characteristics:
            print("     char %s props=%s" % (ch.uuid, ch.properties))

    print("== 逐秒观察 20 s ==")
    t0 = time.time()
    for i in range(20):
        line = "  t+%4.1fs connected=%s" % (time.time() - t0, c.is_connected)
        if c.is_connected:
            try:
                v = bytes(await c.read_gatt_char(UUID_STATUS))
                line += "  status=%s" % v.hex(" ")
            except Exception as e:
                line += "  read failed: %s: %s" % (type(e).__name__, e)
        print(line)
        await asyncio.sleep(1.0)

    if c.is_connected:
        await c.disconnect()
        print("  disconnected")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
