#!/usr/bin/env python3
"""自定义 BLE OTA 的主机端客户端（真机驱动用）。

用 bleak 走**真实 GATT** 把 zephyr.signed.bin 推到设备次级槽。
设计上刻意与固件侧口径一致：

- 传的是 **zephyr.signed.bin**，不是裸 zephyr.bin，也不是 merged.hex；
- Data 用 **Write Without Response**；
- 分片大小 = 协商出的 MTU − 3，**不假设一定是 128**（协商失败回退 65 也能跑）；
- **流量控制依据设备确认的偏移**（Status 通知里的 confirmed offset），
  不是"我已经发出去多少"；
- 断线后重新 START 即可从设备确认的连续偏移**续传**。

用法
----
    # 完整升级（含触发重启）
    python tools/ota_host_client.py --file build-ota2/.../zephyr.signed.bin

    # 只传到前 N 字节后断连（用于测续传）
    python tools/ota_host_client.py --file <bin> --max-bytes 20000

    # 续传剩余部分（同文件、同参数，设备会回报起始偏移）
    python tools/ota_host_client.py --file <bin>

    # 负向用例
    python tools/ota_host_client.py --file <bin> --bad-key
    python tools/ota_host_client.py --file <bin> --bad-sha      # 校验应失败、不得重启
    python tools/ota_host_client.py --file <bin> --too-large    # 应在擦除前被拒
    python tools/ota_host_client.py --file <bin> --no-trigger   # 只传完不重启

授权密钥（**不入库**）
--------------------
    export OTA_AUTH_KEY=<16 个可见 ASCII 字符 或 32 位十六进制>
    # 或：python tools/ota_host_client.py --file <bin> --key <key>
值与固件 `src/ble/ota_auth_key.h` 的 `OTA_AUTH_KEY` 一致；
模板见 `src/ble/ota_auth_key.h.example`。

注意：必须用同时装了 bleak 的解释器（NCS 工具链的 python 没有 bleak）：
    <你的 python 解释器路径>     # 例：python -m bleak --version 能跑即可
"""

import argparse
import asyncio
import hashlib
import os
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bleak import BleakClient, BleakScanner  # noqa: E402

# ---- UUID（与 src/ble/ble_ota.h 一致）----
SVC_UUID   = "12340050-1234-5678-1234-56789abcdef0"
CTRL_UUID  = "12340051-1234-5678-1234-56789abcdef0"
DATA_UUID  = "12340052-1234-5678-1234-56789abcdef0"
STATUS_UUID= "12340053-1234-5678-1234-56789abcdef0"

DEVICE_PREFIX = "PandaTemp"

# Control 操作码
CTRL_START, CTRL_END, CTRL_CANCEL, CTRL_TRIGGER = 0x01, 0x02, 0x03, 0x04
START_LEN = 61

# 状态
ST_IDLE, ST_READY, ST_RECEIVING, ST_VERIFY, ST_PENDING, ST_ERROR = range(6)
STATE_NAME = {ST_IDLE: "IDLE", ST_READY: "READY", ST_RECEIVING: "RECEIVING",
              ST_VERIFY: "VERIFY", ST_PENDING: "PENDING", ST_ERROR: "ERROR"}

# 授权密钥**不入库**（凭据）。按以下顺序解析：
#   1) 命令行 --key <16 个可见 ASCII 字符 或 32 位十六进制>
#   2) 环境变量 OTA_AUTH_KEY（写法同上）
# 值必须与固件 src/ble/ota_auth_key.h 里的 OTA_AUTH_KEY 一致；
# 获取方式见 src/ble/ota_auth_key.h.example。
AUTH_KEY_ENV = "OTA_AUTH_KEY"


def parse_auth_key(text):
    """16 个可见 ASCII 字符或 32 位十六进制 → 16 字节；非法返回 None。"""
    t = (text or "").strip()
    if len(t) == 16 and all(0x20 <= ord(c) <= 0x7E for c in t):
        return t.encode("ascii")
    h = t.replace(" ", "").replace("-", "")
    if len(h) == 32:
        try:
            return bytes.fromhex(h)
        except ValueError:
            return None
    return None


def resolve_auth_key(cli_key):
    raw = cli_key if cli_key is not None else os.environ.get(AUTH_KEY_ENV)
    if raw is None:
        raise SystemExit(
            "缺少 OTA 授权密钥：请用 --key 传入，或设置环境变量 %s。\n"
            "（密钥属凭据、不入库；固件侧见 src/ble/ota_auth_key.h，"
            "模板见 src/ble/ota_auth_key.h.example）" % AUTH_KEY_ENV)
    key = parse_auth_key(raw)
    if key is None:
        raise SystemExit("OTA 授权密钥格式非法：需要 16 个可见 ASCII 字符或 32 位十六进制")
    return key

# MCUboot 镜像头里 ih_ver 的偏移与长度
IH_VER_OFF, IH_VER_LEN = 20, 8
IMAGE_MAGIC = 0x96F3B83D


class OtaClient:
    def __init__(self, key):
        self.key = key
        self.client = None
        self.confirmed = 0      # 设备确认的连续偏移
        self.total = 0
        self.state = ST_IDLE
        self.err = 0
        self.ntfy_count = 0

    async def connect(self, attempts=12):
        last = None
        for i in range(1, attempts + 1):
            found = await BleakScanner.discover(timeout=10.0, return_adv=True)
            items = found.values() if isinstance(found, dict) else found
            dev = None
            for it in items:
                d = it[0] if isinstance(it, (tuple, list)) else it
                if (getattr(d, "name", None) or "").startswith(DEVICE_PREFIX):
                    dev = d
                    break
            if dev is None:
                last = "扫描未发现 %s*" % DEVICE_PREFIX
            else:
                try:
                    self.client = BleakClient(dev, timeout=30.0)
                    await self.client.connect()
                    return
                except Exception as e:      # noqa: BLE001
                    last = "%s: %s" % (type(e).__name__, e)
            print("  连接第 %d 次失败（%s），2 s 后重试…" % (i, last))
            await asyncio.sleep(2.0)
        raise RuntimeError("BLE 连接连续 %d 次失败：%s" % (attempts, last))

    def on_status(self, _h, data):
        b = bytes(data)
        if len(b) < 12:
            return
        self.confirmed, self.total = struct.unpack_from("<II", b, 0)
        self.state, self.err = b[8], b[9]
        self.ntfy_count += 1

    async def start(self, total, ih_ver, sha, bad_sha=False):
        payload = bytearray()
        payload.append(CTRL_START)
        payload += self.key
        payload += struct.pack("<I", total)
        payload += ih_ver
        payload += (bytes(32) if bad_sha else sha)
        assert len(payload) == START_LEN, len(payload)
        await self.client.write_gatt_char(CTRL_UUID, bytes(payload), response=True)

    async def send_data(self, blob, start_off, max_bytes=None, window=4096,
                        chunk_override=None):
        """从 start_off 开始发送。窗口：未确认字节数超过 window 就等一等。

        chunk_override 用于**不改固件**就验证 MTU 65 的回退路径：
        协商出 128 时也强制用 62 B 分片，走的是与真实回退完全相同的代码路径。
        """
        mtu = getattr(self.client, "mtu_size", None) or 65
        chunk = chunk_override or max(20, mtu - 3)
        end = len(blob) if max_bytes is None else min(len(blob), start_off + max_bytes)

        print("  协商 MTU=%d → 分片 %d B（Write Without Response）%s"
              % (mtu, chunk, "  [--chunk 覆盖]" if chunk_override else ""))
        off = start_off
        t0 = time.time()
        last_print = 0.0

        while off < end:
            n = min(chunk, end - off)
            await self.client.write_gatt_char(DATA_UUID, blob[off:off + n],
                                              response=False)
            off += n

            # 流控：别冲得比设备确认的偏移太远
            while (off - self.confirmed) > window and off < end:
                await asyncio.sleep(0.02)

            now = time.time()
            if now - last_print >= 2.0:
                last_print = now
                pct = off * 100.0 / len(blob)
                kbs = (off - start_off) / 1024.0 / max(0.001, now - t0)
                print("    %7d/%d B (%5.1f%%)  设备确认 %7d  %.1f KB/s"
                      % (off, len(blob), pct, self.confirmed, kbs))
        dur = max(0.001, time.time() - t0)
        print("  发送完毕: %d B，用时 %.1f s（%.1f KB/s），设备确认偏移 %d"
              % (off - start_off, dur, (off - start_off) / 1024.0 / dur,
                 self.confirmed))
        return off

    async def ctrl(self, op):
        await self.client.write_gatt_char(CTRL_UUID, bytes([op]), response=True)


def load_image(path):
    blob = open(path, "rb").read()
    if len(blob) < 32:
        raise SystemExit("文件太小，不像是固件镜像")
    magic = struct.unpack_from("<I", blob, 0)[0]
    if magic != IMAGE_MAGIC:
        print("  ⚠️ 镜像 magic=0x%08x，不是 MCUboot 签名镜像。"
              "确认传的是 zephyr.signed.bin（不是裸 zephyr.bin / merged.hex）" % magic)
    ih_ver = blob[IH_VER_OFF:IH_VER_OFF + IH_VER_LEN]
    sha = hashlib.sha256(blob).digest()
    return blob, ih_ver, sha


async def run(args):
    blob, ih_ver, sha = load_image(args.file)
    key = resolve_auth_key(args.key)
    if args.bad_key:
        key = bytes(b ^ 0xFF for b in key)
    total = len(blob)
    if args.too_large:
        total = 0x40000          # 256 KiB，远超 160 KiB 次级槽

    print("镜像: %s" % args.file)
    print("  %d B  ih_ver=%s  sha256=%s…" % (len(blob), ih_ver.hex(), sha[:8].hex()))

    # 连接 → 订阅 → START 是一个**整体**，任一步掉线都要整段重来。
    # Windows WinRT 的 BLE 栈在本机实测成功率约 1/6，只重试 connect() 是不够的：
    # 常见故障是"连上后立刻掉"，表现为 start_notify 抛 Not connected。
    if args.cancel:
        c = OtaClient(key)
        await c.connect()
        try:
            print("发送 CANCEL（清除设备侧续传状态）…")
            await c.ctrl(CTRL_CANCEL)
            await asyncio.sleep(0.5)
            print("  已发送。注意：CANCEL 不会擦除次级槽数据，只是让下次从 0 开始。")
            return 0
        finally:
            try:
                await c.client.disconnect()
            except Exception:
                pass

    if args.only_trigger:
        c = OtaClient(key)
        await c.connect()
        try:
            print("仅触发升级（设备应处于 VERIFY 状态）…")
            # 同 --only-trigger：TRIGGER 后链路必然断开，属预期（见下方完整流程的说明）。
            try:
                await c.ctrl(CTRL_TRIGGER)
                await asyncio.sleep(1.5)
            except Exception as e:      # noqa: BLE001
                print("  TRIGGER 已发出，链路随设备复位断开（%s: %s）"
                      % (type(e).__name__, e))
                print("  ✅ 设备已请求升级并重启（链路断开属预期，判为成功）。")
                return 0
            print("  已发送 TRIGGER，设备应重启。")
            return 0
        finally:
            try:
                await c.client.disconnect()
            except Exception:
                pass

    c = None
    last_exc = None
    for attempt in range(1, 4):
        c = OtaClient(key)
        try:
            # --max-bytes 的意图是"模拟传到一半掉线"，必须**从 0 开始**才有意义。
            # 设备侧续传状态是持久的（NVS），不清掉的话会直接接着上次传完的偏移，
            # 发几百字节就 END 了，测不到"中途断连"。
            if args.max_bytes is not None:
                await c.connect()
                await c.ctrl(CTRL_CANCEL)
                await asyncio.sleep(0.6)
                try:
                    await c.client.disconnect()
                except Exception:
                    pass
                await asyncio.sleep(0.6)
                c = OtaClient(key)

            await c.connect()
            mtu = getattr(c.client, "mtu_size", None) or 23
            print("已连接，MTU=%d（固件请求 128，协商失败会自动回退；两者都必须能跑）" % mtu)
            if mtu < 64:
                raise RuntimeError(
                    "协商 MTU=%d，不足以发送 %d 字节 START（需 ≥64）" % (mtu, START_LEN))
            await c.client.start_notify(STATUS_UUID, c.on_status)
            await c.start(total, ih_ver, sha, bad_sha=args.bad_sha)

            # START 现在是**异步**的：整槽擦除被固件移到系统工作队列，
            # 约 1~2 s 后才会发出 state=READY 的 Status 通知。
            # 因此必须等到 READY（或 ERROR）再发数据 ——
            # "IDLE 且无错误"**不等于**可以开始发送。
            deadline = time.monotonic() + 20.0
            while time.monotonic() < deadline:
                if c.state in (ST_READY, ST_RECEIVING, ST_VERIFY,
                               ST_PENDING, ST_ERROR):
                    break
                await asyncio.sleep(0.1)
            if c.state not in (ST_READY, ST_RECEIVING, ST_VERIFY,
                               ST_PENDING, ST_ERROR):
                raise RuntimeError("START 后 20 s 内未收到 READY/ERROR，链路疑似已断")
            last_exc = None
            break
        except Exception as e:      # noqa: BLE001
            last_exc = e
            print("  会话建立第 %d 次失败（%s），2 s 后重连…" % (attempt, e))
            try:
                await c.client.disconnect()
            except Exception:
                pass
            await asyncio.sleep(2.0)
    if last_exc is not None:
        raise RuntimeError("会话建立连续 3 次失败：%s" % last_exc)

    try:
        print("START 后: state=%s err=%d confirmed=%d total=%d"
              % (STATE_NAME.get(c.state, "?"), c.err, c.confirmed, c.total))

        if c.state == ST_ERROR:
            print("  → 设备已拒绝（err=%d）" % c.err)
            if args.expect_err is not None:
                print("  期望 err=%d，实测 err=%d → %s"
                      % (args.expect_err, c.err,
                         "符合" if c.err == args.expect_err else "不符"))
            return 1 if (args.expect_err is not None and c.err != args.expect_err) else 0

        resume_from = c.confirmed
        if resume_from:
            print("  ✅ 续传：设备要求从 %d 继续（%.1f%%）"
                  % (resume_from, resume_from * 100.0 / len(blob)))

        end = await c.send_data(blob, resume_from, max_bytes=args.max_bytes,
                                chunk_override=args.chunk)
        await asyncio.sleep(0.5)

        # 只要给了 --max-bytes，就一定是"故意中断"用例：**绝不**发 END / TRIGGER。
        # （早先版本的判据是 `end < len(blob)`，一旦设备侧偏移已接近文件尾就不再成立，
        #   于是会意外走完 END 并触发重启 —— 这个坑真实踩过。）
        if args.max_bytes is not None:
            print("\n--max-bytes：只发了 %d B，现在断连（不 END、不重启）。" % end)
            print("  再跑一次同样命令即可验证续传。")
            return 0

        await c.ctrl(CTRL_END)
        await asyncio.sleep(1.0)
        print("END 后: state=%s err=%d confirmed=%d/%d"
              % (STATE_NAME.get(c.state, "?"), c.err, c.confirmed, c.total))

        if args.bad_sha:
            ok = (c.state == ST_ERROR and c.err == 8)
            print("  SHA 不匹配用例：期望 err=8(HASH) 且不得进入 PENDING → %s"
                  % ("符合" if ok else "不符（state=%s err=%d）" % (c.state, c.err)))
            return 0 if ok else 1

        if c.state == ST_ERROR:
            print("  校验失败，中止（不触发升级）: err=%d" % c.err)
            return 1
        if c.state != ST_VERIFY:
            print("  状态异常（期望 VERIFY）: %s" % STATE_NAME.get(c.state, "?"))
            return 1

        if args.no_trigger:
            print("--no-trigger：已校验通过，不重启。")
            return 0

        print("触发升级（设备会先保存历史写头与配置，再请求升级并重启）…")
        # ⚠️ TRIGGER 之后设备立刻 sys_reboot()，链路必然断开。
        # 这不代表失败 —— 与 Android 端（TRIGGER_DISCONNECT_GRACE_MS）和
        # OTA.md §5.5 / PROTOCOL.md §7 的口径一致：**断开即成功**。
        # 实测断开的表象有两种：BLE 写返回 status=133(GATT_ERROR)，
        # 或 WinRT 直接抛 OSError [WinError -2147467260] 已中止操作。
        try:
            await c.ctrl(CTRL_TRIGGER)
            await asyncio.sleep(2.0)
        except Exception as e:      # noqa: BLE001
            print("  TRIGGER 已发出，链路随设备复位断开（%s: %s）"
                  % (type(e).__name__, e))
            print("  ✅ 设备已请求升级并重启（链路断开属预期，判为成功）。")
            return 0
        print("  已发送 TRIGGER，设备应重启。")
        return 0
    finally:
        try:
            await c.client.disconnect()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser(description="自定义 BLE OTA 主机端客户端")
    ap.add_argument("--file", required=True, help="zephyr.signed.bin 路径")
    ap.add_argument("--key", help="OTA 授权密钥：16 个可见 ASCII 字符或 32 位十六进制"
                                 "（也可用环境变量 OTA_AUTH_KEY；密钥不入库）")
    ap.add_argument("--bad-key", action="store_true", help="用错误密钥（测授权拒绝）")
    ap.add_argument("--bad-sha", action="store_true", help="声明错误的 SHA-256（测校验拒绝）")
    ap.add_argument("--too-large", action="store_true", help="声明超大长度（测擦除前拒绝）")
    ap.add_argument("--max-bytes", type=int, help="只发这么多字节后断连（续传用例）")
    ap.add_argument("--chunk", type=int,
                    help="强制分片大小（如 62，用于不改固件验证 MTU 65 回退路径）")
    ap.add_argument("--no-trigger", action="store_true", help="传完不触发重启")
    ap.add_argument("--only-trigger", action="store_true",
                    help="不重传，直接对已处于 VERIFY 的设备发 TRIGGER")
    ap.add_argument("--cancel", action="store_true",
                    help="只发 CANCEL，清除设备侧续传状态（做「从 0 开始」用例前先调用）")
    ap.add_argument("--expect-err", type=int, help="期望的错误码（负向用例断言用）")
    args = ap.parse_args()
    return asyncio.run(run(args))


if __name__ == "__main__":
    sys.exit(main())
