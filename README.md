# PandaTemperature：固件、Android 与硬件

nRF52810 + W25Q64 温湿度/气压记录仪与配套 Android 客户端，统一在此 Git 项目维护。

- 仓库根 src/、boards/、overlay 与 sysbuild 配置：Firmware。
- android/：Android Gradle 根目录，含源码、测试和凭据示例。
- [CODEX_START_HERE.md](CODEX_START_HERE.md)：统一接手入口。
- [HANDOFF.md](HANDOFF.md)：验证边界和 P0–P4 任务。
- [PROTOCOL.md](PROTOCOL.md)、[OTA.md](OTA.md)、[HARDWARE.md](HARDWARE.md)：两端协议、OTA、引脚/分区事实。

保持现有固件布局，不为目录合并修改 MCUboot、UUID、OTA 命令、签名算法或 Flash 地址。签名私钥、Android keystore、OTA 凭据、设备数据库和构建产物不入库。历史设计文档与当前实现冲突时，以代码为准。

Android 独立仓库保留用于向 yodfz/PandaThemperature-Android 提交上游 PR；对应提交见 android/README.md。
