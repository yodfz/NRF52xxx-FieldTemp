#!/usr/bin/env python3
"""生成「坏签名」镜像，用于验证 MCUboot 会拒绝验签失败的镜像。

为什么不能简单地改载荷
----------------------
固件在 END 阶段会校验「整文件 SHA-256」并拒绝不一致的镜像（`err=8`），
所以改载荷根本走不到 MCUboot。要测 **MCUboot 的 ECDSA 验签**，
必须让文件**自身一致**（SHA-256 与手机声明的一致）但**签名无效** ——
即：只破坏签名 TLV，不碰 header 与 payload
（MCUboot 的镜像哈希只覆盖 header + payload，不含尾部 TLV）。

产出镜像的行为预期
------------------
1. 上传 + END 校验 **通过**（固件看到的 SHA-256 与手机声明一致）；
2. `boot_request_upgrade()` 被调用、设备重启；
3. MCUboot 验签失败 → **拒绝搬运**，主槽保持不变，设备继续跑旧固件。

镜像尾部布局（实测，imgtool 产出）
--------------------------------
    [0..511]                 MCUboot 头（hdr_size=512）
    [512 .. 512+img_size-1]  载荷
    尾部 TLV 区（每条 TLV：type u16 LE / len u16 LE / value，4 字节对齐）：
        type=0x0010 SHA256     len=32   镜像哈希
        type=0x0001 KEYHASH    len=32   公钥哈希
        type=0x0022 ECDSA256   len=70   DER 编码签名（`30 44 02 20 <r32> 02 20 <s32>`）

注意：**签名是 0x0022 而不是 0x0001**（0x0001 是 KEYHASH）—— 这点很容易搞错。

用法
----
    python tools/ota_mkbadsig.py <输入签名镜像> <输出路径> [--flip-offset N]

不带 --flip-offset 时，自动在末尾找到 ECDSA256 TLV 并翻转其签名的第 56 字节
（落在 r||s 的 s 段内，确保签名一定失效）。
"""

import argparse
import struct
import sys

TLV_SHA256 = 0x0010
TLV_KEYHASH = 0x0001
TLV_ECDSA256 = 0x0022
# DER: SEQUENCE(len=0x44) INTEGER(len=0x20) —— ECDSA P-256 的签名前缀
DER_ECDSA_P256_PREFIX = bytes([0x30, 0x44, 0x02, 0x20])
NAMES = {TLV_SHA256: "SHA256(镜像哈希)", TLV_KEYHASH: "KEYHASH(公钥哈希)",
         TLV_ECDSA256: "ECDSA256(签名)"}


def parse_header(blob):
    magic, load_addr, hdr_size, ptlv, img_size, flags = \
        struct.unpack_from("<IIHHII", blob, 0)
    return dict(magic=magic, load_addr=load_addr, hdr_size=hdr_size,
                protect_tlv_size=ptlv, img_size=img_size, flags=flags)


def find_signature_tlv(blob, window=512):
    """在末尾 window 字节内定位 ECDSA256 签名 TLV。

    判据：找到 `22 00 <len:u16 LE>` 且其后紧接 DER ECDSA P-256 前缀。
    返回 (tlv_header_offset, value_offset, value_len) 或 None。
    """
    end = len(blob)
    for off in range(max(0, end - window), end - 4):
        if blob[off] != 0x22 or blob[off + 1] != 0x00:
            continue
        ln = struct.unpack_from("<H", blob, off + 2)[0]
        voff = off + 4
        if voff + 4 <= end and blob[voff:voff + 4] == DER_ECDSA_P256_PREFIX \
                and voff + ln <= end:
            return off, voff, ln
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--flip-offset", type=int, default=None,
                    help="手动指定要翻转的绝对偏移（默认：签名值内第 56 字节）")
    args = ap.parse_args()

    blob = bytearray(open(args.src, "rb").read())
    h = parse_header(blob)
    print("镜像: %s" % args.src)
    print("  magic=0x%08X  hdr_size=%d  img_size=%d  文件=%d B"
          % (h["magic"], h["hdr_size"], h["img_size"], len(blob)))

    sig = find_signature_tlv(blob)
    if sig is None:
        sys.exit("✗ 未能在末尾定位 ECDSA256 签名 TLV，放弃")
    hoff, voff, vlen = sig
    print("  ECDSA256 TLV: 头@%d  值@%d 长=%d  前 4 字节=%s"
          % (hoff, voff, vlen, blob[voff:voff + 4].hex(" ")))

    # 列一下尾部 TLV，便于人工核对
    print("  尾部 TLV 概览：")
    o = hoff
    while o + 4 <= len(blob):
        t, ln = struct.unpack_from("<HH", blob, o)
        if t not in NAMES:
            break
        print("    type=0x%04X %-18s len=%d" % (t, NAMES[t], ln))
        o += 4 + ((ln + 3) & ~3)

    target = args.flip_offset if args.flip_offset is not None else voff + 56
    if not (voff <= target < voff + vlen):
        sys.exit("✗ 指定的翻转偏移 %d 不在签名值区间 [%d, %d) 内"
                 % (target, voff, voff + vlen))

    old = blob[target]
    blob[target] = old ^ 0xFF
    print("\n  翻转签名值内第 %d 字节（绝对偏移 %d）: 0x%02X -> 0x%02X"
          % (target - voff, target, old, blob[target]))
    print("  header 与 payload **未改动** → MCUboot 的镜像哈希仍自洽，"
          "只有 ECDSA 验签会失败。")

    open(args.dst, "wb").write(bytes(blob))
    print("  已写出: %s (%d B)" % (args.dst, len(blob)))


if __name__ == "__main__":
    main()
