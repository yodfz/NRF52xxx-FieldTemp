# E104-BT5010A GPIO 引脚定义

## 概述

本文档记录 E104-BT5010A 开发板的 GPIO 引脚分配情况。

- MCU: nRF52810
- Flash: 192 KiB
- RAM: 24 KiB

## 引脚配置架构

为便于硬件移植和维护，引脚定义采用集中管理方式：

- **`src/board_pins.h`**: 引脚配置中心，定义所有 GPIO 引脚编号
- **各模块头文件**: 引用 `board_pins.h` 中的定义，避免硬编码
- **`.overlay` 文件**: 配置 UART/I2C/SPI 的 pinctrl（设备树层面）

### 使用方式

```c
#include "board_pins.h"

// LED 引脚
PIN_LED_DATA    // P0.4
PIN_LED_LINK    // P0.5

// SPI Flash 引脚
PIN_SPI0_CS     // P0.28
PIN_SPI0_MISO   // P0.29
PIN_SPI0_SCK    // P0.30
PIN_SPI0_MOSI   // P0.31

// I2C 引脚（文档记录）
PIN_I2C0_SCL    // P0.6
PIN_I2C0_SDA    // P0.7

// UART 引脚（文档记录）
PIN_UART0_TX    // P0.18
PIN_UART0_RX    // P0.14
```

## GPIO 引脚分配表

| GPIO 引脚 | 功能 | 方向 | 说明 |
|-----------|------|------|------|
| P0.4 | DATA LED | 输出 | 数据指示灯，低电平点亮 |
| P0.5 | LINK LED | 输出 | 蓝牙连接指示灯，低电平点亮 |
| P0.6 | I2C0 SCL | 双向 | I2C 时钟线（AHT30/SPL06传感器） |
| P0.7 | I2C0 SDA | 双向 | I2C 数据线（AHT30/SPL06传感器） |
| P0.14 | UART0 RX | 输入 | 串口接收（调试用） |
| P0.18 | UART0 TX | 输出 | 串口发送（调试用） |
| P0.28 | W25Q64 CS | 输出 | SPI Flash 片选，低电平有效 |
| P0.29 | W25Q64 MISO | 输入 | SPI Flash 数据输出 |
| P0.30 | W25Q64 SCK | 输出 | SPI Flash 时钟 |
| P0.31 | W25Q64 MOSI | 输出 | SPI Flash 数据输入 |

## 外设配置

### LED 指示灯

- **DATA LED (P0.4)**: 数据更新时短暂闪烁
- **LINK LED (P0.5)**: 
  - 初始化时闪烁
  - 蓝牙广播时慢闪（500ms间隔）
  - 蓝牙连接后常亮

### I2C0 (传感器)

- 时钟频率: 100kHz (标准模式)
- 连接设备:
  - AHT30 温湿度传感器
  - SPL06 气压传感器

### UART0 (调试串口)

- 波特率: 115200
- 数据位: 8
- 停止位: 1
- 无校验

### SPI0 (W25Q64 Flash)

- 时钟频率: 由驱动配置
- 模式: SPI Mode 0
- 存储容量: 8MB
- 用途: 历史数据存储

## 相关文件

- **`src/board_pins.h`** - 引脚配置中心（推荐优先查看）
- `src/led.h` - LED 引脚定义（引用 board_pins.h）
- `src/storage/w25q64.h` - W25Q64 CS 引脚定义（引用 board_pins.h）
- `boards/*.overlay` - 设备树配置（UART/I2C/SPI pinctrl）
