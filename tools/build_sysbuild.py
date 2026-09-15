#!/usr/bin/env python3
"""sysbuild 构建包装（引号安全版）。

为什么需要这个脚本
------------------
`SB_CONFIG_BOOT_SIGNATURE_KEY_FILE` 是一个 **Kconfig 字符串**，Kconfig 要求值本身带引号，
也就是最终要传给 CMake 的参数必须形如：

    -DSB_CONFIG_BOOT_SIGNATURE_KEY_FILE="C:/path/root-ec-p256.pem"      （含字面双引号）

问题在于这层引号要穿过 **shell → python → west → cmake** 四层：
- 在 Git Bash 里必须整体用单引号包住才不被吃掉；
- 在 PowerShell 里内层双引号会被剥离，于是拿到
  `malformed string literal in assignment to BOOT_SIGNATURE_KEY_FILE` 而构建中止。

本脚本**不让 shell 参与引号处理**：自己设置环境变量、自己拼 sys.argv、直接调用 west 的
main()。无论从 PowerShell、Git Bash 还是 CI 调用，行为都一致。

用法
----
    python tools/build_sysbuild.py <build_dir> [额外 CMake 参数...]
    python tools/build_sysbuild.py --signing-key <key.pem> <build_dir> [额外 CMake 参数...]

例：
    python tools/build_sysbuild.py build-v8
    python tools/build_sysbuild.py build-stk8 -DCONFIG_INIT_STACKS=y

约定
----
- 源码目录固定为本仓库根（脚本的上一级目录）。
- board 固定 nrf52dk/nrf52810；用 `-p always` 保证干净重建。

签名私钥（**凭据，绝不入库**）
------------------------------
按以下顺序解析，**没有内置默认路径**：

    1) 命令行参数   --signing-key <path>   （也接受 --signing-key=<path>）
    2) 环境变量     MCU_BOOT_SIGNING_KEY=<path>
    3) 都没有       → 带用法说明报错退出

PowerShell：
    $env:MCU_BOOT_SIGNING_KEY="D:\\keys\\root-ec-p256.pem"
    python tools\\build_sysbuild.py build-v8

或：
    python tools\\build_sysbuild.py --signing-key "D:\\keys\\root-ec-p256.pem" build-v8

不要把这个私钥复制进仓库，也不要把它写成脚本里的默认路径
（历史上这里曾硬编码过某个开发机的绝对路径，既不可移植也是信息泄露）。

其它可覆盖的环境变量
--------------------
NCS_ROOT        Nordic Connect SDK 安装根目录（默认 `C:\\ncs`）。
NCS_VERSION     NCS 版本目录名（默认 `v3.2.1`）。
NCS_TOOLCHAIN   工具链目录（默认 `<NCS_ROOT>/toolchains/<hash>/opt`；hash 随安装而异，
                建议在本机用 `ls <NCS_ROOT>/toolchains` 查得后显式设置）。
GIT_CONFIG_GLOBAL_FILE  若设置且文件存在，则用作 git 的全局配置（见下）。
"""

import os
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

NCS_ROOT = os.environ.get("NCS_ROOT", r"C:\ncs")
NCS_VERSION = os.environ.get("NCS_VERSION", "v3.2.1")
TOOLCHAIN = os.environ.get(
    "NCS_TOOLCHAIN", os.path.join(NCS_ROOT, "toolchains", "66cdf9b75e", "opt"))
ZEPHYR_BASE = os.path.join(NCS_ROOT, NCS_VERSION, "zephyr")
CMAKE_PREFIX = os.path.join(NCS_ROOT, NCS_VERSION, "zephyr", "share",
                            "zephyr-package", "cmake").replace("\\", "/")

BOARD = "nrf52dk/nrf52810"

# 环境变量名：签名私钥路径
ENV_SIGNING_KEY = "MCU_BOOT_SIGNING_KEY"

KEY_HELP = """\
缺少 MCUboot 签名私钥路径。签名私钥属凭据，**不入库**，必须由调用方显式提供：

    1) 命令行：   python tools/build_sysbuild.py --signing-key <key.pem> <build_dir> ...
    2) 环境变量： {env}=<key.pem>   （PowerShell: $env:{env}="D:\\keys\\root-ec-p256.pem"）

本脚本刻意不提供任何默认私钥路径，也不接受把私钥放进仓库。
""".format(env=ENV_SIGNING_KEY)


def _extract_signing_key(args):
    """从参数列表里取出 --signing-key / --signing-key=...，返回 (key, 剩余参数)。"""
    key = None
    rest = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--signing-key":
            if i + 1 >= len(args):
                sys.exit("--signing-key 后面缺少路径\n\n" + KEY_HELP)
            key = args[i + 1]
            i += 2
            continue
        if a.startswith("--signing-key="):
            key = a.split("=", 1)[1]
            i += 1
            continue
        rest.append(a)
        i += 1
    return key, rest


def _resolve_signing_key(cli_key):
    """CLI > 环境变量 > 报错。不做任何路径猜测。

    返回 `(用于校验/展示的绝对路径, 传给 CMake 的路径)`。

    ⚠️ 传给 CMake 的路径**必须用正斜杠**：该值会经 Kconfig 字符串 → CMake → imgtool
    多层传递，反斜杠会在途中被当作转义符吞掉，实测表现为
    `C:UserslinckrDocuments...root-ec-p256.pem`（分隔符全没了），
    构建在签名步骤才失败，且报错信息完全看不出是路径问题。
    """
    key = cli_key if cli_key is not None else os.environ.get(ENV_SIGNING_KEY)
    if not key:
        sys.exit(KEY_HELP)
    key = os.path.abspath(os.path.expanduser(key))
    if not os.path.isfile(key):
        sys.exit("签名私钥文件不存在: %s\n\n%s" % (key, KEY_HELP))
    return key, key.replace("\\", "/")


def _ensure_safe_git_config():
    """给 west/zephyr 的 git 调用准备一个最小 gitconfig。

    Zephyr 的版本生成会调用 git；若仓库属主与当前用户不一致（例如工作副本是从别的
    账户/沙箱复制过来的），git 会以 `dubious ownership` 拒绝操作，而版本号会**静默**
    退回 `0.0.0+0`。这里写一个只含 `safe.directory = *` 的临时配置并通过
    GIT_CONFIG_GLOBAL 生效，避免把任何本机绝对路径写进仓库。
    """
    path = os.environ.get("GIT_CONFIG_GLOBAL_FILE")
    if path and os.path.isfile(path):
        os.environ["GIT_CONFIG_GLOBAL"] = path
        return path
    path = os.path.join(tempfile.gettempdir(), "zephyr-safe-gitconfig")
    if not os.path.isfile(path):
        with open(path, "w", encoding="utf-8") as f:
            f.write("[safe]\n\tdirectory = *\n")
    os.environ["GIT_CONFIG_GLOBAL"] = path
    return path


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0 if args else 2

    cli_key, args = _extract_signing_key(args)
    if not args:
        sys.exit("缺少 <build_dir>。用法见:\n\n" + __doc__)

    build_dir = args[0]
    extra = args[1:]
    key, key_cmake = _resolve_signing_key(cli_key)

    if not os.path.isabs(build_dir):
        build_dir = os.path.join(REPO, build_dir)

    os.environ["PATH"] = os.pathsep.join(
        [os.path.join(TOOLCHAIN, "bin"),
         os.path.join(TOOLCHAIN, "bin", "Scripts"),
         os.environ.get("PATH", "")])
    os.environ["ZEPHYR_BASE"] = ZEPHYR_BASE
    os.environ["CMAKE_PREFIX_PATH"] = CMAKE_PREFIX
    _ensure_safe_git_config()

    # 关键：这里拼出的字符串**已经带字面双引号**，后续不再经过任何 shell；
    # key_cmake 已归一化成正斜杠（见 _resolve_signing_key 的说明）。
    sign_arg = '-DSB_CONFIG_BOOT_SIGNATURE_KEY_FILE="%s"' % key_cmake

    argv = ["west", "build", "-p", "always", "-b", BOARD, "--sysbuild",
            "-d", build_dir, REPO, "--", sign_arg]
    argv.extend(extra)

    print("build_dir  = %s" % build_dir)
    print("signing    = %s (来自 %s)" % (key, "命令行" if cli_key else ENV_SIGNING_KEY))
    print("extra      = %s" % (" ".join(extra) if extra else "(无)"))
    sys.stdout.flush()

    sys.argv = argv
    from west.app.main import main as west_main
    return west_main()


if __name__ == "__main__":
    sys.exit(main())
