#!/usr/bin/env python3
"""stk6 硬件验证：① 60 秒真实节奏  ② 事件限流是否堵住越限边沿。

@ 地址取自 build-stk6（每次重建必须用 nm 重取）。

阶段 1 —— 节奏测量
  注入时间基准后，每秒采样一次 record_count，记录每次自增的墙钟时刻，
  由相邻间隔反推真实记录周期。v5（单次定时器+重武装）实测 ≈68 s；本版期望 60 s。

阶段 2 —— 事件限流压力注入
  固件在 history_maybe_event() 里每拍把 alarm_state 归 0；这里以 5 Hz 反复把它写成 1，
  于是每个采样拍都看到"越限状态发生变化"→ alarm_edge 恒为真。
  这就是"数值在阈值附近抖动"的退化极限（每拍都是边沿）。
    旧实现：`if (alarm_edge) emit = true;` 绕过 gap 判定 → 每秒追加一条，130 s ≈ 130 条。
    本版：共用闸门 → 至多每 60 s 一条。
  注意：这是**合成压力注入**，不是现场工况；记录里存的是真实温湿度，只有触发原因是人为的。
"""
import struct
import time

from pyocd.core.helpers import ConnectHelper

# ---- 符号地址（build-stk6）----
A = {
    "acc_hum_n":            (0x20002d90, 2),
    "acc_press_n":          (0x20002d8e, 2),
    "acc_temp_n":           (0x20002d92, 2),
    "alarm_state":          (0x20002e12, 1),
    "bt_ready":             (0x20002e16, 1),
    "dropped_records":      (0x200028b4, 4),
    "event_pending_kind":   (0x20002e10, 1),
    "history_interval":     (0x200007ea, 2),
    "last_event_seen":      (0x20002e11, 1),
    "last_event_uptime_ms": (0x200028b0, 4),
    "last_record_temp":     (0x20002d8a, 2),
    "last_record_valid":    (0x20002e13, 1),
    "nvs_ready":            (0x20002e57, 1),
    "record_count":         (0x20002d94, 2),
    "ram_buffer_count":     (0x20002e18, 1),
    "time_base_timestamp":  (0x200028e4, 4),
    "time_base_uptime":     (0x20001ae8, 8),
    "time_synced":          (0x20002e15, 1),
    "w25q64_ready":         (0x20002e56, 1),
    "window_deadline_ms":   (0x200028bc, 4),
    "window_ticks":         (0x20002d8c, 2),
}


def main():
    session = ConnectHelper.session_with_chosen_probe(
        unique_id="LU_2022_8888", target_override="nRF52810_xxAA", frequency=500000)
    session.open()
    tgt = session.target

    def rd(name):
        addr, size = A[name]
        return int.from_bytes(bytes(tgt.read_memory_block8(addr, size)), "little")

    def wr(name, value):
        addr, size = A[name]
        tgt.write_memory_block8(addr, list(value.to_bytes(size, "little")))

    tgt.reset()
    tgt.resume()
    for _ in range(40):
        time.sleep(1)
        if rd("bt_ready"):
            break
    time.sleep(3)

    print("启动: bt_ready=%d w25q64=%d nvs=%d history_interval=%d 秒"
          % (rd("bt_ready"), rd("w25q64_ready"), rd("nvs_ready"), rd("history_interval")),
          flush=True)

    # 注入时间基准（仅测量用；否则一条历史记录都不会产生）
    wr("time_base_uptime", 0)
    wr("time_base_timestamp", int(time.time()))
    wr("time_synced", 1)
    tgt.resume()
    print("已注入时间基准；deadline=%d ms  window_ticks=%d  record_count=%d"
          % (rd("window_deadline_ms"), rd("window_ticks"), rd("record_count")), flush=True)

    # ---------------- 阶段 1：节奏 ----------------
    base = rd("record_count")
    marks = []
    start = time.time()
    last = base
    while time.time() - start < 430:
        time.sleep(1.0)
        now = rd("record_count")
        if now != last:
            marks.append((round(time.time() - start, 1), now))
            last = now
    print("\n[阶段1] 430 s 节奏测量：起始 record_count=%d，新增 %d 条" % (base, last - base),
          flush=True)
    for i, (ts, val) in enumerate(marks):
        gap = "" if i == 0 else "   间隔 %.1f s（距上一条）" % (ts - marks[i - 1][0])
        print("   第 %d 条落盘  t+%6.1f s  record_count=%d%s" % (i + 1, ts, val, gap), flush=True)
    if len(marks) > 1:
        gaps = [marks[i][0] - marks[i - 1][0] for i in range(1, len(marks))]
        print("   相邻间隔: %s" % ", ".join("%.1f" % g for g in gaps), flush=True)
        print("   平均周期: %.1f s（60 s 为正确；旧实现 ≈68 s）" % (sum(gaps) / len(gaps)),
              flush=True)

    # ---------------- 阶段 2：事件限流压力注入 ----------------
    before = rd("record_count")
    ev_before = rd("last_event_seen")
    print("\n[阶段2] 以 5 Hz 反复把 alarm_state 置 1（模拟阈值抖动退化为每拍都是边沿）...",
          flush=True)
    start2 = time.time()
    last = before
    marks2 = []
    while time.time() - start2 < 130:
        wr("alarm_state", 1)          # 关键注入：让每个采样拍都判成"越限状态发生变化"
        time.sleep(0.2)
        now = rd("record_count")
        if now != last:
            marks2.append((round(time.time() - start2, 1), now))
            last = now
    print("[阶段2] 130 s 内新增记录 %d 条（旧实现会被绕过闸门，预期 ≈130 条）"
          % (last - before), flush=True)
    for i, (ts, val) in enumerate(marks2):
        gap = "" if i == 0 else "   间隔 %.1f s" % (ts - marks2[i - 1][0])
        print("   落盘 t+%6.1f s  record_count=%d%s" % (ts, val, gap), flush=True)

    print("\n收尾状态: record_count=%d  dropped=%d  ram_buffer=%d  time_synced=%d"
          % (rd("record_count"), rd("dropped_records"), rd("ram_buffer_count"),
             rd("time_synced")), flush=True)
    session.close()


if __name__ == "__main__":
    main()
