#!/usr/bin/env python3
"""复位专项：在 MCUboot「次级槽 → 主槽」搬运途中强制复位；不属于真实断电测试。

为什么要这样打
--------------
本工具只做 SWD **复位**回归，不是真实断电矩阵。MCU 复位不会切断 W25Q64 电源，
不能模拟 NOR Flash 擦写期间掉电；即使复位后恢复，也不能据此宣称 P3 真断电通过。
真正断电必须使用人工电池断开或受控电源，并分别记录阶段命中证据和恢复证据。

两个关键实现点（都踩过坑）
--------------------------
1. **SWD 会话必须在 TRIGGER 之前打开**。nRF52 内部 Flash 擦写期间 CPU 会停摆，
   此时新建 pyocd 会话会在 `s.open()` 阶段直接 `WAIT ACK` 超时。会话一旦初始化好，
   之后的 `reset()` 走 CTRL-AP，不受擦写影响。
2. **复位后立刻 halt 快照主槽 magic**。若读到 `0xFFFFFFFF` 或非法值，
   就证明我们真的打在了"主槽已擦、还没写完"的窗口内 —— 这是打断生效的铁证，
   而不是"复位得太早/太晚所以没打到"。

判据
----
- 打断后 MCUboot 能重新搬运 → 主槽 magic 恢复有效、设备正常启动并广播 → **不变砖**；
- 主槽被擦了一半且 MCUboot 无法恢复 → 卡死/反复重启 → **变砖**（用
  `pyocd flash build-ota3/merged.hex` 重烧恢复）。

用法
----
    python tools/ota_powerloss_reset.py --file <signed.bin> --delays 500,1500,2500
"""
import argparse
import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from bleak import BleakScanner  # noqa: E402
from pyocd.core.helpers import ConnectHelper  # noqa: E402

import ota_host_client as C  # noqa: E402

PRIMARY_HDR = 0x8000
IMAGE_MAGIC = 0x96F3B83D


class Swd:
    """持有已初始化的 SWD 会话，避免擦写期间重新 open 超时。"""

    def __init__(self, probe_id=None):
        self.s = ConnectHelper.session_with_chosen_probe(
            unique_id=probe_id, target_override="nRF52810_xxAA",
            frequency=500000)
        self.s.open()
        self.t = self.s.target
        # 关键：pyocd 连上时会**默认 halt 核心**。会话必须在这里就 resume，
        # 否则后面 BLE 扫描永远看不到设备广播（不广播 = 连不上 = 一直重试）。
        self.t.resume()

    def rd(self, a, n=4):
        return int.from_bytes(bytes(self.t.read_memory_block8(a, n)), "little")

    def snapshot(self, tag):
        """读一次设备状态。MCUboot 擦写**内部** Flash 期间 CPU 会停摆，
        此时 SWD 会 WAIT/FAULT ACK —— 这是预期现象，跳过即可，不能当成失败。"""
        try:
            self.t.halt()
        except Exception as e:      # noqa: BLE001
            print("  [%s] SWD 不可用（MCUboot 应在擦写内部 Flash）: %s"
                  % (tag, type(e).__name__))
            return None
        try:
            pc = self.t.read_core_register("pc")
            vtor = self.rd(0xE000ED08)
            cfsr = self.rd(0xE000ED28)
            hfsr = self.rd(0xE000ED2C)
            magic = self.rd(PRIMARY_HDR)
            img = self.rd(PRIMARY_HDR + 0x0C)
            print("  [%s] PC=0x%08X VTOR=0x%08X CFSR=0x%08X HFSR=0x%08X"
                  % (tag, pc, vtor, cfsr, hfsr))
            print("  [%s] 主槽 magic=0x%08X img_size=%d (%s)"
                  % (tag, magic, img,
                     "有效" if magic == IMAGE_MAGIC else "!! 无效：已擦/搬运中 !!"))
            return dict(pc=pc, vtor=vtor, cfsr=cfsr, hfsr=hfsr,
                        magic=magic, img=img)
        except Exception as e:      # noqa: BLE001
            print("  [%s] SWD 读失败: %s" % (tag, type(e).__name__))
            return None
        finally:
            try:
                self.t.resume()
            except Exception:
                pass

    def reset(self):
        self.t.reset()

    def close(self):
        # 必须先 resume 再 close：pyocd 会话若把核心留在 halt 状态，
        # 设备就不再广播，后续任何 BLE 测试都会"扫描不到设备"而失败。
        try:
            self.t.resume()
        except Exception:
            pass
        try:
            self.s.close()
        except Exception:
            pass


async def advertise(timeout=12.0):
    f = await BleakScanner.discover(timeout=timeout, return_adv=True)
    items = f.values() if isinstance(f, dict) else f
    for it in items:
        d = it[0] if isinstance(it, (tuple, list)) else it
        if (getattr(d, "name", None) or "").startswith(C.DEVICE_PREFIX):
            return d
    return None


async def arm_and_interrupt(swd, image_path, delay_ms, key_text=None):
    """上传 → VERIFY → TRIGGER → 延迟 delay_ms → 复位 → 立刻快照 → 观察恢复。"""
    print("\n======== 打断延迟 %d ms ========" % delay_ms)
    rc = await C.run(argparse.Namespace(
        file=image_path, key=key_text, bad_key=False, bad_sha=False, too_large=False,
        max_bytes=None, no_trigger=True, chunk=None, cancel=False,
        only_trigger=False, expect_err=None))
    if rc != 0:
        print("  上传失败，跳过该延迟")
        return None

    c = C.OtaClient(C.resolve_auth_key(key_text))
    await c.connect()
    try:
        await c.client.start_notify(C.STATUS_UUID, c.on_status)
        try:
            await c.ctrl(C.CTRL_TRIGGER)
            print("  TRIGGER 已写入")
        except Exception as e:      # noqa: BLE001
            print("  TRIGGER 写入异常（设备可能已复位）: %s" % type(e).__name__)
    finally:
        try:
            await c.client.disconnect()
        except Exception:
            pass

    t0 = time.monotonic()
    time.sleep(delay_ms / 1000.0)
    swd.reset()
    print("  已在 TRIGGER 后约 %d ms 复位" % int((time.monotonic() - t0) * 1000))

    # 立刻快照：magic 无效 = 真的打在搬运窗口内
    hit = swd.snapshot("复位瞬间")
    # SWD failure alone cannot establish which boot phase was interrupted.
    caught = hit is not None and hit["magic"] != IMAGE_MAGIC

    recovered = None
    for wait in (2, 3, 5, 10, 15):
        time.sleep(wait)
        snap = swd.snapshot("复位后 +%ds" % wait)
        if snap and snap["magic"] == IMAGE_MAGIC and snap["cfsr"] == 0 \
                and snap["hfsr"] == 0:
            recovered = wait
            break

    dev = await advertise()
    adv = dev.name if dev else None
    print("  结果: 打断生效=%s  恢复用时=%ss  广播=%s"
          % ("是" if caught else "否（可能没打中窗口）",
             recovered if recovered else "未恢复", adv or "无"))
    return dict(delay_ms=delay_ms, caught=caught,
                recovered_s=recovered, advertising=adv)


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True, help="zephyr.signed.bin 路径")
    ap.add_argument("--delays", default="500,1500,2500",
                    help="逗号分隔的打断延迟（ms）")
    ap.add_argument("--key", help="OTA authorization key; prefer OTA_AUTH_KEY environment variable")
    ap.add_argument("--probe", help="Optional SWD probe unique ID; default selects available probe")
    args = ap.parse_args()

    delays = [int(x) for x in args.delays.split(",") if x.strip()]
    if not delays or any(d < 0 for d in delays):
        ap.error("At least one non-negative interruption delay is required")
    C.resolve_auth_key(args.key)
    results = []
    for d in delays:
        # 每个延迟用**独立**的 SWD 会话：擦写期间的 FAULT ACK 会把会话搞脏，
        # 复用同一会话会让后续用例全部失败。
        swd = Swd(args.probe)
        try:
            r = await arm_and_interrupt(swd, args.file, d, args.key)
            if r:
                results.append(r)
        finally:
            swd.close()

    print("\n======== 汇总 ========")
    for r in results:
        print("  延迟 %5d ms: 打断生效=%-3s 恢复=%ss 广播=%s"
              % (r["delay_ms"], "是" if r["caught"] else "否",
                 r["recovered_s"] or "未",
                 "是" if r["advertising"] else "否"))
    all_ok = len(results) == len(delays) and all(r["caught"] and r["recovered_s"] and r["advertising"] for r in results)
    print("结论: %s" % ("复位用例均命中并恢复（不证明真实断电）" if all_ok else "复位用例未命中、未恢复或证据不完整，需排查"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
