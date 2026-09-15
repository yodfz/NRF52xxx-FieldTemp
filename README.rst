nRF52810 温湿度计固件
=====================

本仓库是 E104-BT5010A（nRF52810）上的 Zephyr 固件，配套 Android 项目为
``PandaThemperature-Android``。当前固件版本以根目录 ``VERSION`` 为准。

接手入口
--------

按顺序阅读：

1. `CODEX_START_HERE.md <CODEX_START_HERE.md>`_：已验证基线与后续任务。
2. `HANDOFF.md <HANDOFF.md>`_：状态、风险与代码地图。
3. `DEVELOPMENT.md <DEVELOPMENT.md>`_：环境、构建、烧录与门禁。
4. `HARDWARE.md <HARDWARE.md>`_：GPIO、W25Q64 与分区。
5. `OTA.md <OTA.md>`_：签名、镜像与升级流程。
6. `PROTOCOL.md <PROTOCOL.md>`_：Firmware ↔ Android 字节级协议。

仓库 ``docs/`` 保存旧版本设计和调试记录。它们不代表当前实现；若与代码冲突，以代码为准。

当前硬件与协议
--------------

- nRF52810：192 KiB 内部 Flash、24 KiB RAM；W25Q64：8 MiB 外置 SPI NOR。
- DATA / LINK LED 为 P0.4 / P0.5，低电平有效。P0.30 / P0.31 用于 W25Q64 SCK / MOSI。
- 四个自定义 GATT 服务：配置 ``12340020``、实时数据 ``12340030``、清空数据 ``12340040``、
  OTA ``12340050``。时间同步特征 ``12340011`` 属于配置服务；没有 ``12340010`` 服务。
- 实时帧 8 字节（温度、湿度、气压、VDD）；历史记录 12 字节，最多 65,000 条。
- W25Q64：secondary ``[0,0x28000)``、NVS ``[0x28000,0x2E000)``、
  业务 history ``[0x2E000,0x12E000)``。地址由 Partition Manager 与 ``w25q64.h`` 对齐。

构建与升级
----------

构建板目标是 ``nrf52dk/nrf52810``。通过 ``tools/build_sysbuild.py`` 构建 sysbuild；
签名私钥必须在仓库外提供，具体命令见 `DEVELOPMENT.md <DEVELOPMENT.md>`_。首次烧录或救砖使用
``merged.hex``；BLE OTA 必须发送 ``zephyr.signed.bin``。MCUboot 使用 ECDSA-P256 验签，
它是固件可信边界；``OTA_AUTH_KEY`` 只用于限制误触发和擦槽 DoS，不是安全边界。

签名私钥、OTA 授权密钥及 Android keystore 均不得提交到 Git。每次改动先运行
``tools/size_summary.py`` 与 ``tools/release_gate.py``；MCUboot 与 nRF52810 的空间余量很小。

许可证
------

SPDX-License-Identifier: Apache-2.0
