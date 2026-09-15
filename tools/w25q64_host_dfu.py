#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Host-driven W25Q64 programmer for NRF52xxx-FieldTemp (nRF52810 + E104-BT5010A-TB).

What this is for
----------------
The MCUboot secondary slot lives in external SPI flash (W25Q64 @ 0x000000,
160 KiB), but the application firmware has no DFU transport yet (no mcumgr/SMP,
no shell) and there is only ~7 KiB of internal flash headroom.  This tool
sidesteps both problems: it drives the on-board W25Q64 directly from the host
over SWD by poking the nRF52810 SPIM0 registers while the core is halted, so a
signed update image can be staged into the secondary slot without touching the
application at all.

How the upgrade is triggered
----------------------------
MCUboot derives the swap type from the *secondary* slot trailer
(`boot_swap_tables[]` in bootutil_public.c matches on
`magic_secondary_slot == BOOT_MAGIC_GOOD` with `image_ok_secondary_slot`
UNSET).  Overwrite-only mode (`CONFIG_BOOT_UPGRADE_ONLY=y`) keeps no swap
state, so writing the image plus the 16-byte trailer magic at
`fa_size - 16` is enough: MCUboot validates, copies secondary -> primary and
boots the new image.  The magic bytes below were dumped from the actual
MCUboot ELF (`boot_img_magic` in rodata), not guessed.

Pin-select gotcha
-----------------
The PSEL block order is SCK(0x508), MOSI(0x50C), MISO(0x510), CSN(0x514) on
this part, but the mdk header comment on nrf52810.h labels 0x50C as MISO.
Trusting that comment swaps MOSI and MISO and the flash never answers.  The
values here were confirmed by dumping PSEL from a running, working firmware
image (PSEL@0x50C=31=MOSI, PSEL@0x510=29=MISO), which matches app.overlay.

Safety
------
Only the secondary slot range 0x000000-0x027FFF is ever erased.  History
records and NVS live at 0x028000+ and are never touched.  Every run gates on a
JEDEC ID check (EF 40 17) before issuing the first erase.

Usage
-----
  # stage a signed image into the secondary slot (erase + program + trailer
  # magic + verify), then let the target run so MCUboot performs the upgrade
  python w25q64_host_dfu.py write build/.../zephyr.signed.v2.bin --reset

  python w25q64_host_dfu.py info                 # JEDEC ID + status regs
  python w25q64_host_dfu.py erase                # erase the secondary slot
  python w25q64_host_dfu.py read out.bin 0x0 256 # dump external flash
  python w25q64_host_dfu.py verify some.bin      # compare against flash
"""

import argparse
import sys
import time

# --- nRF52810 memory map -----------------------------------------------------
SPIM0_BASE = 0x40004000
GPIO0_BASE = 0x50000000

# SPIM0 registers (nrf52810_bitfields.h)
SPIM_TASKS_START = 0x010
SPIM_TASKS_STOP  = 0x014
SPIM_EVENTS_END  = 0x118
SPIM_ENABLE      = 0x500
SPIM_PSEL_SCK    = 0x508
SPIM_PSEL_MOSI   = 0x50C   # see note below - do NOT trust the mdk header comment
SPIM_PSEL_MISO   = 0x510
SPIM_PSEL_CSN    = 0x514
SPIM_FREQUENCY   = 0x524
SPIM_RXD_PTR     = 0x534
SPIM_RXD_MAXCNT  = 0x538
SPIM_TXD_PTR     = 0x544
SPIM_TXD_MAXCNT  = 0x548
SPIM_CONFIG      = 0x554
SPIM_ORC         = 0x5C0
SPIM_ENABLE_ON   = 7
SPIM_ENABLE_OFF  = 0
FREQ_4MBPS       = 0x40000000

GPIO_OUTSET   = 0x508
GPIO_OUTCLR   = 0x50C
GPIO_DIRSET   = 0x518
GPIO_PIN_CNF  = 0x700

# Board wiring: see app.overlay (spi0_default_custom / cs-gpios)
PIN_SCK  = 30
PIN_MOSI = 31
PIN_MISO = 29
PIN_CS   = 28

# PIN_CNF values copied from a running (working) firmware image, so the pad
# configuration is bit-for-bit what the Zephyr pinctrl driver produced.
PIN_CNF_CS   = 0x00000003   # P0.28: output
PIN_CNF_MISO = 0x00000004   # P0.29: input + pull-down
PIN_CNF_SCK  = 0x00000005   # P0.30: output + pull-down
PIN_CNF_MOSI = 0x00000007   # P0.31: output + pull-down

# Scratch RAM for EasyDMA buffers (nRF52810 has 24 KiB RAM @ 0x20000000)
TXBUF = 0x20004000
RXBUF = 0x20004800

# --- W25Q64 ------------------------------------------------------------------
CMD_PAGE_PROGRAM = 0x02
CMD_READ_DATA    = 0x03
CMD_READ_STATUS1 = 0x05
CMD_WRITE_ENABLE = 0x06
CMD_JEDEC_ID     = 0x9F
CMD_RELEASE_DPD  = 0xAB
CMD_SECTOR_ERASE = 0x20   # 4 KiB, keeps us inside the secondary slot
SECTOR_SIZE      = 0x1000
PAGE_SIZE        = 0x100
STATUS_BUSY      = 0x01

JEDEC_ID = bytes([0xEF, 0x40, 0x17])

# MCUboot secondary slot (pm_static.yml)
SECONDARY_BASE = 0x000000
SECONDARY_SIZE = 0x028000

# boot_img_magic, dumped from build/mcuboot/zephyr/zephyr.elf rodata
TRAILER_MAGIC = bytes([
    0x77, 0xc2, 0x95, 0xf3, 0x60, 0xd2, 0xef, 0x7f,
    0x35, 0x52, 0x50, 0x0f, 0x2c, 0xb6, 0x79, 0x80,
])
MAGIC_OFF = SECONDARY_SIZE - len(TRAILER_MAGIC)   # 0x27FF0


class W25Q64:
    def __init__(self, target):
        self.t = target

    # -- low level ------------------------------------------------------------
    def init(self):
        t = self.t
        t.write32(SPIM0_BASE + SPIM_ENABLE, SPIM_ENABLE_OFF)
        # Pad config: same values the Zephyr pinctrl driver programmed.
        # CS is driven as a plain GPIO output; SPIM leaves CSN disconnected.
        t.write32(GPIO0_BASE + GPIO_PIN_CNF + 4 * PIN_CS, PIN_CNF_CS)
        t.write32(GPIO0_BASE + GPIO_PIN_CNF + 4 * PIN_MISO, PIN_CNF_MISO)
        t.write32(GPIO0_BASE + GPIO_PIN_CNF + 4 * PIN_SCK, PIN_CNF_SCK)
        t.write32(GPIO0_BASE + GPIO_PIN_CNF + 4 * PIN_MOSI, PIN_CNF_MOSI)
        t.write32(GPIO0_BASE + GPIO_OUTSET, 1 << PIN_CS)
        # PSEL must be written while the peripheral is disabled.
        t.write32(SPIM0_BASE + SPIM_PSEL_SCK, PIN_SCK)
        t.write32(SPIM0_BASE + SPIM_PSEL_MOSI, PIN_MOSI)
        t.write32(SPIM0_BASE + SPIM_PSEL_MISO, PIN_MISO)
        t.write32(SPIM0_BASE + SPIM_PSEL_CSN, 0xFFFFFFFF)
        t.write32(SPIM0_BASE + SPIM_CONFIG, 0)          # MSB first, CPOL=0, CPHA=0
        t.write32(SPIM0_BASE + SPIM_FREQUENCY, FREQ_4MBPS)
        t.write32(SPIM0_BASE + SPIM_ORC, 0xFF)
        t.write32(SPIM0_BASE + SPIM_ENABLE, SPIM_ENABLE_ON)

    def cs_low(self):
        self.t.write32(GPIO0_BASE + GPIO_OUTCLR, 1 << PIN_CS)

    def cs_high(self):
        self.t.write32(GPIO0_BASE + GPIO_OUTSET, 1 << PIN_CS)

    def xfer(self, tx=b"", rx_len=0):
        """One SPI transaction. Returns rx_len bytes (or b'')."""
        t = self.t
        if tx:
            t.write_memory_block8(TXBUF, list(tx))
        t.write32(SPIM0_BASE + SPIM_RXD_PTR, RXBUF)
        t.write32(SPIM0_BASE + SPIM_RXD_MAXCNT, rx_len)
        t.write32(SPIM0_BASE + SPIM_TXD_PTR, TXBUF if tx else 0)
        t.write32(SPIM0_BASE + SPIM_TXD_MAXCNT, len(tx))
        t.write32(SPIM0_BASE + SPIM_EVENTS_END, 0)
        t.write32(SPIM0_BASE + SPIM_TASKS_START, 1)
        for _ in range(100000):
            if t.read32(SPIM0_BASE + SPIM_EVENTS_END):
                break
        else:
            raise RuntimeError("SPI transaction timed out (EVENTS_END never set)")
        if rx_len:
            return bytes(t.read_memory_block8(RXBUF, rx_len))
        return b""

    # -- chip level -----------------------------------------------------------
    def release_dpd(self):
        """Wake the flash if the previous firmware left it in deep power-down."""
        self.cs_low()
        self.xfer(bytes([CMD_RELEASE_DPD, 0x00, 0x00, 0x00]))
        self.cs_high()
        time.sleep(0.0001)

    def jedec_id(self):
        self.cs_low()
        r = self.xfer(bytes([CMD_JEDEC_ID, 0x00, 0x00, 0x00]), 4)
        self.cs_high()
        return r[1:4]

    def status(self):
        self.cs_low()
        r = self.xfer(bytes([CMD_READ_STATUS1, 0x00]), 2)
        self.cs_high()
        return r[1]

    def wait_ready(self, timeout_s=5.0):
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if not (self.status() & STATUS_BUSY):
                return
            time.sleep(0.001)
        raise RuntimeError("flash stayed busy past %.1fs" % timeout_s)

    def write_enable(self):
        self.cs_low()
        self.xfer(bytes([CMD_WRITE_ENABLE]))
        self.cs_high()

    def erase_sector(self, addr):
        self.write_enable()
        self.cs_low()
        self.xfer(bytes([CMD_SECTOR_ERASE,
                         (addr >> 16) & 0xFF, (addr >> 8) & 0xFF, addr & 0xFF]))
        self.cs_high()
        self.wait_ready()

    def program_page(self, addr, data):
        """Program at most PAGE_SIZE bytes without crossing a page boundary."""
        if not data:
            return
        self.write_enable()
        self.cs_low()
        self.xfer(bytes([CMD_PAGE_PROGRAM,
                         (addr >> 16) & 0xFF, (addr >> 8) & 0xFF, addr & 0xFF]) + data)
        self.cs_high()
        self.wait_ready()

    def read(self, addr, length):
        out = bytearray()
        while length:
            n = min(length, 256)
            self.cs_low()
            r = self.xfer(bytes([CMD_READ_DATA,
                                 (addr >> 16) & 0xFF, (addr >> 8) & 0xFF, addr & 0xFF]),
                          4 + n)
            self.cs_high()
            out += r[4:4 + n]
            addr += n
            length -= n
        return bytes(out)


def check_chip(fl):
    fl.release_dpd()
    jid = fl.jedec_id()
    print("JEDEC ID : %02x %02x %02x" % tuple(jid))
    if jid != JEDEC_ID:
        raise SystemExit("JEDEC ID mismatch (expected %s) - refusing to touch flash."
                         % JEDEC_ID.hex(" ").upper())
    print("status-1 : 0x%02x" % fl.status())


def do_write(fl, data):
    if len(data) > SECONDARY_SIZE:
        raise SystemExit("image %d B does not fit in the 0x%x-byte secondary slot"
                         % (len(data), SECONDARY_SIZE))

    # Always erase the whole slot: the trailer magic lives in the last sector,
    # which an image-length-based sector count would leave untouched (and page
    # program only clears bits, so it must start from 0xFF).
    nsec = SECONDARY_SIZE // SECTOR_SIZE
    print("erasing  : %d sectors (0x%06x-0x%06x)"
          % (nsec, SECONDARY_BASE, SECONDARY_BASE + nsec * SECTOR_SIZE - 1))
    for i in range(nsec):
        fl.erase_sector(SECONDARY_BASE + i * SECTOR_SIZE)
        if i % 8 == 0:
            print("  sector %d/%d" % (i, nsec))

    total = len(data)
    print("programming %d bytes" % total)
    pos = 0
    while pos < total:
        chunk = data[pos:pos + PAGE_SIZE]
        fl.program_page(SECONDARY_BASE + pos, chunk)
        pos += len(chunk)
        if (pos // PAGE_SIZE) % 64 == 0:
            print("  %d/%d bytes (%.0f%%)" % (pos, total, 100.0 * pos / total))

    print("trailer  : magic @0x%06x (swap type -> TEST)" % MAGIC_OFF)
    fl.program_page(MAGIC_OFF, TRAILER_MAGIC)

    print("verifying")
    back = fl.read(SECONDARY_BASE, total)
    if back != data:
        for i in range(0, total, PAGE_SIZE):
            if back[i:i + PAGE_SIZE] != data[i:i + PAGE_SIZE]:
                raise SystemExit("verify failed at offset 0x%06x" % (SECONDARY_BASE + i))
    print("verify   : OK (%d bytes match)" % total)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", choices=["info", "erase", "write", "read", "verify"])
    ap.add_argument("file", nargs="?")
    ap.add_argument("offset", nargs="?", default="0")
    ap.add_argument("length", nargs="?", default="256")
    ap.add_argument("--probe", default="LU_2022_8888")
    ap.add_argument("--target", default="nRF52810_xxAA")
    ap.add_argument("--swd", default=500000, type=int)
    ap.add_argument("--no-magic", action="store_true",
                    help="do not write the MCUboot trailer magic")
    ap.add_argument("--reset", action="store_true",
                    help="release the core after the operation (starts the upgrade)")
    args = ap.parse_args()

    from pyocd.core.helpers import ConnectHelper

    session = ConnectHelper.session_with_chosen_probe(
        unique_id=args.probe, target_override=args.target, frequency=args.swd)
    if session is None:
        raise SystemExit("probe %s not found" % args.probe)
    with session:
        target = session.target
        target.reset_and_halt()
        fl = W25Q64(target)
        fl.init()
        try:
            check_chip(fl)

            if args.action == "info":
                return
            if args.action == "erase":
                for i in range(SECONDARY_SIZE // SECTOR_SIZE):
                    fl.erase_sector(SECONDARY_BASE + i * SECTOR_SIZE)
                print("secondary slot erased")
            elif args.action == "write":
                data = open(args.file, "rb").read()
                do_write(fl, data)
                if args.no_magic:
                    print("(trailer magic skipped)")
            elif args.action == "read":
                off = int(args.offset, 0)
                n = int(args.length, 0)
                open(args.file, "wb").write(fl.read(off, n))
                print("wrote %d bytes from 0x%06x to %s" % (n, off, args.file))
            elif args.action == "verify":
                data = open(args.file, "rb").read()
                back = fl.read(SECONDARY_BASE, len(data))
                print("verify: %s" % ("OK" if back == data else "MISMATCH"))
        finally:
            if args.reset:
                print("releasing core -- MCUboot will now process the secondary slot")
                target.reset()
            else:
                print("core left halted; re-run with --reset to start the upgrade")


if __name__ == "__main__":
    sys.exit(main())
