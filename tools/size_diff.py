"""对比两个构建的 ROM/RAM 与符号增量。

用法：python tools/size_diff.py <基线构建> <对比构建>
输出：ROM(zephyr.bin)、RAM(ELF PT_LOAD in SRAM)、以及新增/变大的符号 Top N。
"""
import os
import re
import subprocess
import sys

NM = ("C:/ncs/toolchains/66cdf9b75e/opt/zephyr-sdk/"
      "arm-zephyr-eabi/bin/arm-zephyr-eabi-nm.exe")
SRAM_LO, SRAM_HI = 0x20000000, 0x20006000
ZEPHYR = "NRF52xxx-FieldTemp/zephyr"


def elf_footprint(path):
    import struct
    with open(path, "rb") as handle:
        blob = handle.read()
    phoff = struct.unpack_from("<I", blob, 0x1C)[0]
    phentsize = struct.unpack_from("<H", blob, 0x2A)[0]
    phnum = struct.unpack_from("<H", blob, 0x2C)[0]
    flash = ram = 0
    for idx in range(phnum):
        off = phoff + idx * phentsize
        p_type, _o, p_vaddr, _p, p_filesz, p_memsz, _f, _a = \
            struct.unpack_from("<IIIIIIII", blob, off)
        if p_type != 1:
            continue
        flash += p_filesz
        if SRAM_LO <= p_vaddr < SRAM_HI:
            ram += p_memsz
    return flash, ram


def symbols(elf):
    out = subprocess.run([NM, "-S", "--size-sort", elf],
                         capture_output=True, text=True, errors="replace").stdout
    syms = {}
    for line in out.splitlines():
        m = re.match(r"^([0-9A-Fa-f]+)\s+([0-9A-Fa-f]+)\s+(\w)\s+(\S+)$", line.strip())
        if not m:
            continue
        addr, size = int(m.group(1), 16), int(m.group(2), 16)
        syms.setdefault(m.group(4), []).append((addr, size))
    return syms


def total_size(syms):
    return sum(sum(s for _, s in v) for v in syms.values())


def main(base, new):
    base_dir, new_dir = os.path.join(base, ZEPHYR), os.path.join(new, ZEPHYR)
    b_rom = os.path.getsize(os.path.join(base_dir, "zephyr.bin"))
    n_rom = os.path.getsize(os.path.join(base_dir and new_dir, "zephyr.bin"))
    _, b_ram = elf_footprint(os.path.join(base_dir, "zephyr.elf"))
    _, n_ram = elf_footprint(os.path.join(new_dir, "zephyr.elf"))

    print("%-16s %12s %12s %10s" % ("", base, new, "增量"))
    print("%-16s %12d %12d %+10d" % ("ROM (zephyr.bin)", b_rom, n_rom, n_rom - b_rom))
    print("%-16s %12d %12d %+10d" % ("RAM (ELF SRAM)", b_ram, n_ram, n_ram - b_ram))

    bs, ns = symbols(os.path.join(base_dir, "zephyr.elf")), symbols(os.path.join(new_dir, "zephyr.elf"))
    print("\n符号表统计：基线 %d 个 / 对比 %d 个" % (len(bs), len(ns)))

    rows = []
    for name, lst in ns.items():
        nsz = sum(s for _, s in lst)
        bsz = sum(s for _, s in bs.get(name, []))
        if nsz != bsz:
            rows.append((nsz - bsz, nsz, bsz, name))
    rows.sort(reverse=True)
    print("\n=== 变大 / 新增的符号 Top 25 ===")
    for delta, nsz, bsz, name in rows[:25]:
        print("%+8d  (now %6d, was %6d)  %s" % (delta, nsz, bsz, name))

    gone = [n for n in bs if n not in ns]
    print("\n消失的符号数：%d" % len(gone))
    big_gone = sorted(((sum(s for _, s in bs[n]), n) for n in gone), reverse=True)[:10]
    for sz, n in big_gone:
        print("  -%6d  %s" % (sz, n))


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
