# SPDX-License-Identifier: Apache-2.0

cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED)
project(blinky_3)

# 主源文件
target_sources(app PRIVATE src/main.c)

# 传感器模块
target_sources(app PRIVATE 
    src/sensors/aht30.c
    src/sensors/spl06.c
)

# 存储模块
target_sources(app PRIVATE 
    src/storage/w25q64.c
    src/storage/nvs_config.c
)

# 蓝牙模块
target_sources(app PRIVATE 
    src/ble/ble_adv.c
    src/ble/ble_services.c
    src/ble/ble_ota.c
)

# LED模块
target_sources(app PRIVATE src/led.c)

# 电池电压采样（仅 CONFIG_ADC=y 时产生代码，默认关闭 → 对发布镜像零影响）
target_sources(app PRIVATE src/vdd.c)

# 添加包含目录
target_include_directories(app PRIVATE src)
