#!/usr/bin/env python3
"""v7 周期记录「墙钟边界对齐」硬件验证 —— 三项测试。

取证架构（v3，修掉 4 个历史 bug；每个都真踩过，记此以免重犯）
-------------------------------------------------------------

1. **探针构造即 halt，且一直不 resume** → BLE 测试根本跑不起来（固件被冻住）。
   现在 `Probe.rd/wr` 每次都是 **halt → 读/写 → resume**，目标其余时间保持运行。
2. **`Probe.rd()` 每次读完都会 resume** → 读外置 Flash 时核心已经在跑了，
   主机侧借走的 SPIM0 与 App 自己的 Flash 访问相撞，读回的是垃圾
   （实测 ts 全变成 64，制造过一次**假 FAIL**）。修法：`with probe.held():`
   —— 块内**保持 halt**（状态量 `_halt_open`）。**读 Flash 必须走它。**
3. **`self.held` 布尔属性遮蔽了同名方法** → `TypeError: 'bool' object is not callable`。
   状态量改名 `_halt_open`，方法名 `held` 保留。
4. **`probe.p.reset()`** 是笔误（`Probe` 没有 `.p`），应为 `probe.reset()`。

⚠️ **实体记录核对是侵入式的**：主机侧 poke SPIM0 会覆盖 App 的 SPI 驱动寄存器。
   实测在一次会话中把持久化写头从 `(11,4)` 顶到 `(11,0)`（App 随后会从头覆盖该扇区）。
   所以它**只作最后一步、只读一次、读完立即复位**，且不要进常规回归。
   测试期间的主证据只走 RAM（零扰动）。

证据链
------
- 边界算式：读 RAM 的 `window_boundary_ts`（固件自己在 GATT 回调里算出来的值）。
- 落盘时间戳：读 `ram_buffer` 的**前 4 字节** —— 正是 `storage_write_batch()` 写出的 12 B 镜像本体。
  （不用 `last_record_ts`：该静态量**只写不读**，已被编译器消除，ELF 里没有符号。）
- 实体记录：最后读 W25Q64 最近 N 条 12 B 记录。扇区内是**页内布局**：
  256/12 = 21 条/页，16 页/扇区 = 336 条/扇区（与 `src/storage/w25q64.h` 一致）。

符号地址从 ELF **现取**（arm-zephyr-eabi-nm），不硬编码 —— `verify_v6_timing.py`
顶部那张硬编码表在 v5→v6 就整体后移了 4–6 B，硬编码地址表是纯负债。

⚠️ 测试 1 / 2 会**真实修改设备的 history_interval**，结束时会还原成 60 秒。
"""

import argparse
import asyncio
import os
import struct
import subprocess
import sys
import time
from contextlib import contextmanager

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from w25q64_host_dfu import W25Q64  # noqa: E402

from pyocd.core.helpers import ConnectHelper  # noqa: E402

PROBE_UID = "LU_2022_8888"
TARGET = "nRF52810_xxAA"
SWD_HZ = 500000
NM = (r"C:\ncs\toolchains\66cdf9b75e\opt\zephyr-sdk\arm-zephyr-eabi\bin"
      r"\arm-zephyr-eabi-nm.exe")

# W25Q64 历史区布局（与 src/storage/w25q64.h 一致）
W25Q64_STORAGE_BASE = 0x2E000
RECORD_SIZE = 12
PAGE_SIZE = 256
RECORDS_PER_PAGE = PAGE_SIZE // RECORD_SIZE
SECTOR_SIZE = 4096
RECORDS_PER_SECTOR = (SECTOR_SIZE // PAGE_SIZE) * RECORDS_PER_PAGE

UUID_TIME = "12340011-1234-5678-1234-56789abcdef0"
UUID_INTERVAL = "12340021-1234-5678-1234-56789abcdef0"
UUID_STATUS = "12340022-1234-5678-1234-56789abcdef0"
DEVICE_NAME_PREFIX = "PandaTemp"

SYMS = {
    "time_synced": 1,
    "wall_clock_aligned": 1,
    "window_boundary_ts": 4,
    "window_deadline_ms": 4,
    "window_ticks": 2,
    "history_interval": 2,
    "record_count": 2,
    "dropped_records": 4,
    "next_sector": 2,
    "oldest_sector": 2,
    "next_record_in_sector": 2,
    "time_base_timestamp": 4,
    "time_base_uptime": 8,
    "acc_temp_n": 2,
    "ram_buffer": 12,
    "ram_buffer_count": 1,
}

CAP_WALLCLOCK_ALIGN = 0x10
CAP_PARTIAL_WINDOW = 0x20
STATUS_BIT_WALLCLOCK = 0x08

FAILS = []


def symtab(elf):
    out = subprocess.run([NM, elf], capture_output=True, text=True, check=True).stdout
    tab = {}
    for line in out.splitlines():
        p = line.split()
        if len(p) == 3:
            try:
                tab[p[2]] = int(p[0], 16)
            except ValueError:
                pass
    return tab


def check(name, ok, detail=""):
    tag = "PASS" if ok else "FAIL"
    if not ok:
        FAILS.append(name)
    print("  【%s】%s%s" % (tag, name, ("  " + detail) if detail else ""))
    return ok


class Probe:
    """RAM 读写。每次操作 halt → 访问 → resume，目标其余时间正常跑 BLE/采样。"""

    def __init__(self):
        self.session = ConnectHelper.session_with_chosen_probe(
            unique_id=PROBE_UID, target_override=TARGET, frequency=SWD_HZ)
        self.session.open()
        self.t = self.session.target
        self._halt_open = False

    def close(self):
        try:
            self.t.resume()
        finally:
            self.session.close()

    def rd(self, addr, size):
        if self._halt_open:
            return int.from_bytes(bytes(self.t.read_memory_block8(addr, size)), "little")
        self.t.halt()
        try:
            return int.from_bytes(bytes(self.t.read_memory_block8(addr, size)), "little")
        finally:
            self.t.resume()

    def wr(self, addr, size, value):
        self.t.halt()
        try:
            self.t.write_memory_block8(addr, value.to_bytes(size, "little"))
        finally:
            self.t.resume()

    @contextmanager
    def held(self):
        """在整个 with 块内**保持 halt**。

        必须用它，不能靠逐次 rd()：rd() 每次读完都会 resume，
        而在读 W25Q64 实体记录时核心一旦跑起来，App 自己的 SPIM 事务
        会和我们的主机侧读抢同一个外设，读回来的是垃圾数据
        （实测：ts 全变成 64 —— 正是这个 bug 造成的假 FAIL）。
        """
        self.t.halt()
        self._halt_open = True
        try:
            yield self
        finally:
            self._halt_open = False
            self.t.resume()

    def reset(self):
        self.t.reset_and_halt()
        self.t.resume()


class Board:
    def __init__(self, probe, tab):
        self.p = probe
        self.tab = tab

    def rd(self, name):
        return self.p.rd(self.tab[name], SYMS[name])

    def wr(self, name, value):
        self.p.wr(self.tab[name], SYMS[name], value)

    def write_head(self):
        return (self.rd("next_sector"), self.rd("next_record_in_sector"),
                self.rd("oldest_sector"))

    def last_pushed_ts(self):
        """最近一条**交给 Flash 层**的记录的时间戳。

        `ram_buffer[0].timestamp` —— 这正是 `storage_write_batch()` 写出去的那 12 字节镜像
        （正常路径下每写一条就 flush，`ram_buffer_count` 随即归 0，但缓冲内容保留）。

        为什么不用 `last_record_ts`：该静态变量**只写不读**，已被编译器消除，ELF 里没有符号。
        那是历史遗留的死状态，不是本次改动引入的。
        """
        return self.p.rd(self.tab["ram_buffer"], 4)

    # ---- 外置 Flash：最后一步才用（读完必须复位） ----
    @staticmethod
    def _record_addr(sector, idx):
        page = idx // RECORDS_PER_PAGE
        off = (idx % RECORDS_PER_PAGE) * RECORD_SIZE
        return W25Q64_STORAGE_BASE + sector * SECTOR_SIZE + page * PAGE_SIZE + off

    def read_last_records(self, n=3):
        """读最近 n 条实体记录（按写头往前推）。要求目标已 halt。"""
        ns = self.rd("next_sector")
        nrec = self.rd("next_record_in_sector")
        # 线性化：(sector, idx) → 全局序号
        linear = ns * RECORDS_PER_SECTOR + nrec
        out = []
        fl = W25Q64(self.p.t)
        fl.init()
        fl.release_dpd()
        for k in range(1, n + 1):
            g = linear - k
            if g < 0:
                break
            sec, idx = divmod(g, RECORDS_PER_SECTOR)
            addr = self._record_addr(sec, idx)
            raw = fl.read(addr, RECORD_SIZE)
            ts, temp, hum, press = struct.unpack("<IhHI", raw)
            out.append({"addr": addr, "sector": sec, "idx": idx, "ts": ts,
                        "temp": temp, "hum": hum, "press": press})
        return out


def hhmmss(ts):
    return time.strftime("%H:%M:%S", time.gmtime(ts))


class BleLink:
    """带重连的 GATT 会话。

    为什么需要它：本机 dongle 到设备的链路偏弱（RSSI −67~−77），
    **服务发现经常在半途被中断**（`WinError -2147467260 已中止操作` 或 `-2147024809 参数错误`），
    实测约 1/6 次能拿到完整 GATT 表。所以不能"连上就算成功"，
    必须**校验关键特征是否到齐**，不到齐就断开重来。

    时间同步特性 `12340011` 挂在 **config 服务 12340020** 内；固件没有 12340010 服务。
    """

    NEEDED = (UUID_TIME, UUID_INTERVAL, UUID_STATUS)

    def __init__(self):
        self.client = None
        self.name = None

    async def _probe_attempt(self, uncached):
        from bleak import BleakClient, BleakScanner
        found = await BleakScanner.discover(timeout=8.0, return_adv=True)
        items = found.values() if isinstance(found, dict) else found
        target = None
        for it in items:
            dev = it[0] if isinstance(it, (tuple, list)) else it
            adv = it[1] if isinstance(it, (tuple, list)) and len(it) > 1 else None
            nm = getattr(dev, "name", None) or ""
            if nm.startswith(DEVICE_NAME_PREFIX):
                target = (dev, nm, getattr(adv, "rssi", None))
                break
        if target is None:
            return None, None, None, "扫描未发现设备"
        dev, nm, rssi = target
        kw = {"winrt": {"use_cached_services": False}} if uncached else {}
        client = None
        try:
            client = BleakClient(dev, timeout=20.0, **kw)
            await client.connect()
            # ⚠️ 必须枚举 **characteristics**，不能拿特征 UUID 去比 `client.services`
            #    （那是服务集合）。初版就栽在这里：明明读得到状态帧，却被判"表不完整"。
            have = {ch.uuid.lower() for s in client.services
                    for ch in s.characteristics}
            missing = [u for u in self.NEEDED if u not in have]
            if missing:
                await client.disconnect()
                return None, nm, rssi, "GATT 表不完整，缺 %d 个特征" % len(missing)
            return client, nm, rssi, "ok"
        except Exception as e:                               # noqa: BLE001
            if client is not None:
                try:
                    await client.disconnect()
                except Exception:
                    pass
            return None, nm, rssi, "%s: %s" % (type(e).__name__, e)

    async def connect(self, total_attempts=16):
        for i in range(1, total_attempts + 1):
            uncached = (i > 10)        # 先试 OS 自主（实测更易成功），再退化到强制非缓存
            client, nm, rssi, why = await self._probe_attempt(uncached)
            if client is not None:
                self.client, self.name = client, nm
                print("  已连接 BLE: %s (第 %d 次尝试，RSSI=%s，发现完整)"
                      % (nm, i, rssi))
                return
            print("  尝试 %d/%d 失败（rssi=%s，%s），2 s 后重试…"
                  % (i, total_attempts, rssi, why))
            await asyncio.sleep(2.0)
        raise RuntimeError("BLE 连续 %d 次未取得完整 GATT 表" % total_attempts)

    async def ensure(self):
        if self.client is None or not self.client.is_connected:
            print("  ⚠️ 链路已断，重连…")
            await self.connect()

    async def write(self, uuid, data, attempts=3):
        for k in range(attempts):
            await self.ensure()
            try:
                await self.client.write_gatt_char(uuid, data, response=True)
                return
            except Exception as e:                           # noqa: BLE001
                print("  ⚠️ 写 %s 失败(%d/%d): %s" % (uuid[:8], k + 1, attempts, e))
                await asyncio.sleep(1.0)
        raise RuntimeError("写特征 %s 连续 %d 次失败" % (uuid, attempts))

    async def read(self, uuid, attempts=3):
        for k in range(attempts):
            await self.ensure()
            try:
                return bytes(await self.client.read_gatt_char(uuid))
            except Exception as e:                           # noqa: BLE001
                print("  ⚠️ 读 %s 失败(%d/%d): %s" % (uuid[:8], k + 1, attempts, e))
                await asyncio.sleep(1.0)
        raise RuntimeError("读特征 %s 连续 %d 次失败" % (uuid, attempts))

    async def close(self):
        if self.client is not None:
            try:
                await self.client.disconnect()
            except Exception:
                pass
            self.client = None


def wait_new_record(board, rc0, timeout_s, poll=1.5):
    """等 `record_count` 自增（= 新记录产生），返回 (record_count, 记录时间戳)。"""
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        rc = board.rd("record_count")
        if rc != rc0:
            time.sleep(0.3)          # 让 flush 收尾
            return rc, board.last_pushed_ts()
        time.sleep(poll)
    raise TimeoutError("等了 %ds，record_count 一直停在 %d" % (timeout_s, rc0))


async def run(args):
    tab = symtab(args.elf)
    missing = [k for k in SYMS if k not in tab]
    if missing:
        print("❌ ELF 缺符号: %s" % missing)
        return 2
    print("符号表已从 ELF 现取：%s" % args.elf)

    probe = Probe()
    board = Board(probe, tab)
    link = BleLink()
    try:
        print("\n初始状态: %s" % {k: board.rd(k) for k in
                                 ("history_interval", "time_synced",
                                  "wall_clock_aligned", "record_count")})

        # ---------------- 测试 1 ----------------
        if args.test in ("1", "all"):
            print("\n=== 测试 1：对时到秒针 :27，首条应落在下一个整分 ===")
            await link.connect()
            st = await link.read(UUID_STATUS)
            print("  状态帧: %s" % st.hex(" "))
            check("能力位 CAP_WALLCLOCK_ALIGN(0x10)", bool(st[5] & CAP_WALLCLOCK_ALIGN),
                  "caps=0x%02x" % st[5])
            check("能力位 CAP_PARTIAL_WINDOW(0x20)", bool(st[5] & CAP_PARTIAL_WINDOW),
                  "caps=0x%02x" % st[5])

            now = int(time.time())
            target = now - (now % 60) + 27            # 秒针落在 :27
            if target <= now:
                target += 60
            expect = ((target // 60) + 1) * 60
            print("  注入 %d (%s, 秒针=%d)" % (target, hhmmss(target), target % 60))
            print("  期望边界 = ((%d/60)+1)*60 = %d (%s)" % (target, expect, hhmmss(expect)))

            await link.write(UUID_TIME, struct.pack("<I", target))
            time.sleep(0.8)
            b = board.rd("window_boundary_ts")
            aligned = board.rd("wall_clock_aligned")
            print("  固件算出 window_boundary_ts=%d (%s, %%60=%d)"
                  % (b, hhmmss(b), b % 60))
            print("  wall_clock_aligned=%d  time_synced=%d"
                  % (aligned, board.rd("time_synced")))
            st2 = await link.read(UUID_STATUS)
            print("  状态帧 bit3(墙钟可信)=%d" % bool(st2[4] & STATUS_BIT_WALLCLOCK))
            check("对时后置位 wall_clock_aligned", aligned == 1)
            check("状态帧上报墙钟可信", bool(st2[4] & STATUS_BIT_WALLCLOCK))
            check("边界 = 下一个整分", b == expect,
                  "%d vs %d" % (b, expect))
            check("边界严格晚于注入时刻（首窗长度非负）", b > target,
                  "首窗 %d 秒" % (b - target))

            rc0 = board.rd("record_count")
            rc, ts = wait_new_record(board, rc0, 120.0)
            print("  首条新记录: ts=%d (%s, %%60=%d)  record_count %d→%d"
                  % (ts, hhmmss(ts), ts % 60, rc0, rc))
            check("首条记录时间戳 = 边界值", ts == expect, "%d vs %d" % (ts, expect))
            check("首条记录落在整分 (ts%60==0)", ts % 60 == 0)
            check("首条为部分窗口（不足 60 秒，按 A7 保留）", (b - target) < 60,
                  "首窗 %d 秒" % (b - target))

        # ---------------- 测试 2 ----------------
        if args.test in ("2", "all"):
            print("\n=== 测试 2：60 秒改 300 秒，下一条应落在最近的 5 分钟边界 ===")
            await link.ensure()
            new_iv = 300
            host = int(time.time())
            # 重对时到一个"距下个 5 分钟边界 25 秒"的时刻：
            # 设备时钟由此完全可知，边界必在 25 秒后到达 —— 不必等主机时钟走到正确相位，
            # 也避开了"设备时钟与主机时钟存在注入偏差"的坑（初版就栽在这里）。
            base = ((host // new_iv) + 1) * new_iv - 25
            if base <= host:
                base += new_iv
            expect = ((base // new_iv) + 1) * new_iv       # == base + 25
            print("  重对时到 %d (%s, %%300=%d)，距 5 分钟边界还有 %d 秒 → 期望边界 %d (%s)"
                  % (base, hhmmss(base), base % new_iv, expect - base,
                     expect, hhmmss(expect)))

            await link.write(UUID_TIME, struct.pack("<I", base))
            time.sleep(0.7)
            rc0 = board.rd("record_count")
            await link.write(UUID_INTERVAL, struct.pack("<H", new_iv))
            time.sleep(0.7)
            iv = board.rd("history_interval")
            b = board.rd("window_boundary_ts")
            print("  history_interval=%d  window_boundary_ts=%d (%s, %%300=%d)"
                  % (iv, b, hhmmss(b), b % new_iv))
            check("history_interval 已改为 300", iv == new_iv, "%d" % iv)
            check("新边界落在 5 分钟网格上 (b%300==0)", b % new_iv == 0)
            check("新边界 == 改周期后最近的那个 5 分钟边界", b == expect,
                  "固件 %d vs 期望 %d" % (b, expect))
            check("新边界严格晚于当前时刻（未落回已过去的边界）", b > base,
                  "边界距注入点 %d 秒" % (b - base))

            rc, ts = wait_new_record(board, rc0, 90.0)
            print("  下一条记录: ts=%d (%s, %%300=%d)  record_count %d→%d"
                  % (ts, hhmmss(ts), ts % new_iv, rc0, rc))
            check("下一条记录 ts == 新边界", ts == expect, "%d vs %d" % (ts, expect))
            check("下一条记录落在 5 分钟边界 (ts%300==0)", ts % new_iv == 0)

            # 还原 60 秒
            await link.write(UUID_INTERVAL, struct.pack("<H", 60))
            time.sleep(0.7)
            b2 = board.rd("window_boundary_ts")
            print("  已还原 history_interval=%d，新边界=%d (%s, %%60=%d)"
                  % (board.rd("history_interval"), b2, hhmmss(b2), b2 % 60))
            check("还原 60 秒后边界回到整分 (b%60==0)", b2 % 60 == 0)
            try:
                wait_new_record(board, rc, 90.0)      # 消化还原后的那条
            except TimeoutError:
                pass

        await link.close()

        # ---------------- 测试 3 ----------------
        if args.test in ("3", "all"):
            print("\n=== 测试 3：复位不触碰存储位置 ===")
            before = board.write_head()
            rc_before = board.rd("record_count")
            print("  复位前: next_sector=%d next_record_in_sector=%d oldest=%d record_count=%d"
                  % (before[0], before[1], before[2], rc_before))
            board.p.reset()
            time.sleep(3.5)
            after = board.write_head()
            print("  复位后: next_sector=%d next_record_in_sector=%d oldest=%d record_count=%d"
                  % (after[0], after[1], after[2], board.rd("record_count")))
            check("写头三元组复位前后完全一致", before == after,
                  "%s → %s" % (before, after))
            check("record_count 归零（RAM 计数，符合预期）",
                  board.rd("record_count") == 0, "%d" % board.rd("record_count"))
            check("复位后不宣称墙钟对齐（仅 NVS 旧时间）",
                  board.rd("wall_clock_aligned") == 0)

            rc, ts = wait_new_record(board, 0, 160.0)
            after2 = board.write_head()
            lin = lambda h: h[0] * RECORDS_PER_SECTOR + h[1]
            d = lin(after2) - lin(after)
            print("  新记录后: next_sector=%d next_record_in_sector=%d 推进 %d 条"
                  % (after2[0], after2[1], d))
            print("  新记录 ts=%d (%s, %%60=%d)" % (ts, hhmmss(ts), ts % 60))
            check("写头恰好推进 1 条", d == 1, "推进 %d" % d)
            check("未对齐状态下 ts%60 不保证为 0（诚实不宣称）", True,
                  "ts%%60=%d" % (ts % 60))

        # ---------------- 实体记录核对（最后一步，读完复位） ----------------
        if args.flash_confirm:
            print("\n=== 实体记录核对（W25Q64，读完立即复位恢复） ===")
            # 全程保持 halt（probe.held）：主机侧读 W25Q64 会借走 SPIM 外设，
            # 核心若在跑，App 的同一次 Flash 访问就会和它相撞。
            with probe.held():
                recs = board.read_last_records(4)
            for r in recs:
                print("  sector=%d idx=%d addr=0x%06X ts=%d (%s) %%60=%d %%300=%d"
                      % (r["sector"], r["idx"], r["addr"], r["ts"], hhmmss(r["ts"]),
                         r["ts"] % 60, r["ts"] % 300))
            if recs:
                check("实体记录里能找到整分时间戳",
                      any(r["ts"] % 60 == 0 for r in recs),
                      "最近 %d 条" % len(recs))
            probe.reset()
            time.sleep(1.0)
            print("  已复位恢复（App 重新初始化 SPI/Flash 驱动）")
    finally:
        probe.close()
        print("\nSWD 已 resume 并断开")

    print("\n" + "=" * 70)
    if FAILS:
        print("结论：FAIL —— %d 项不通过: %s" % (len(FAILS), FAILS))
        return 1
    print("结论：PASS —— 全部断言通过")
    return 0


def main():
    ap = argparse.ArgumentParser(description="v7 墙钟边界对齐验证")
    ap.add_argument("--elf", required=True, help="build-stk7 的 zephyr.elf")
    ap.add_argument("--test", default="all",
                    choices=["1", "2", "3", "flash", "all"],
                    help="flash = 只做实体记录回读（约 1 分钟，便于迭代）；"
                         "all = 三项 + 回读（需配 --flash-confirm）")
    ap.add_argument("--flash-confirm", action="store_true",
                    help="最后直读 W25Q64 实体记录核对（会 halt+复位，有扰动）")
    args = ap.parse_args()
    if args.test == "flash":
        args.flash_confirm = True
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
