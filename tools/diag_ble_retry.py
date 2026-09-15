#!/usr/bin/env python3
"""探测 BLE：分别用「缓存」与「非缓存」发现反复尝试，看各有几次能拿到完整 GATT 表。"""
import asyncio

from bleak import BleakClient, BleakScanner

PREFIX = "PandaTemp"
EXPECT = {
    "12340020-1234-5678-1234-56789abcdef0": "config",
    "12340030-1234-5678-1234-56789abcdef0": "realtime",
    "12340040-1234-5678-1234-56789abcdef0": "clear_data",
    "12340050-1234-5678-1234-56789abcdef0": "ota",
}


async def find():
    f = await BleakScanner.discover(timeout=8.0, return_adv=True)
    items = f.values() if isinstance(f, dict) else f
    for it in items:
        d = it[0] if isinstance(it, (tuple, list)) else it
        adv = it[1] if isinstance(it, (tuple, list)) and len(it) > 1 else None
        n = getattr(d, "name", None) or ""
        if n.startswith(PREFIX):
            return d, n, getattr(adv, "rssi", None)
    return None, None, None


async def attempt(dev, label, uncached, timeout=20.0):
    kw = {"winrt": {"use_cached_services": False}} if uncached else {}
    try:
        c = BleakClient(dev, timeout=timeout, **kw)
        await c.connect()
        svcs = {s.uuid.lower() for s in c.services}
        have = [v for k, v in EXPECT.items() if k in svcs]
        missing = [v for k, v in EXPECT.items() if k not in svcs]
        await c.disconnect()
        return "svcs=%d have=%s missing=%s" % (len(svcs), have, missing)
    except Exception as e:                                   # noqa: BLE001
        return "FAIL %s: %s" % (type(e).__name__, e)


async def main():
    for uncached in (True, False):
        label = "非缓存" if uncached else "缓存"
        print("===== 模式：%s =====" % label)
        for i in range(1, 5):
            dev, name, rssi = await find()
            if dev is None:
                print("  #%d 扫描未发现设备" % i)
                await asyncio.sleep(3)
                continue
            r = await attempt(dev, label, uncached)
            print("  #%d rssi=%s → %s" % (i, rssi, r))
            await asyncio.sleep(2)


if __name__ == "__main__":
    asyncio.run(main())
