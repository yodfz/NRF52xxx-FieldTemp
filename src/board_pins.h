/**
 * @file board_pins.h
 * @brief E104-BT5010A 开发板引脚配置中心
 * 
 * 本文件集中定义所有 GPIO 引脚，便于硬件移植和维护
 * 
 * 硬件版本: E104-BT5010A (nRF52810)
 * 参考: RIOT-2020.10-RC3 boards/e104-bt5010a-tb
 */

#ifndef BOARD_PINS_H_
#define BOARD_PINS_H_

#ifdef __cplusplus
extern "C" {
#endif

/* ========== LED 指示灯引脚 ========== */
/* LED 为低电平有效（低电平点亮，高电平熄灭）*/
#define PIN_LED_DATA       4   /* P0.4 - 数据指示灯 */
#define PIN_LED_LINK       5    /* P0.5 - 蓝牙连接指示灯 */

/* ========== I2C0 传感器总线引脚 ========== */
/* 连接设备: AHT30/SHT40 温湿度传感器, SPL06 气压传感器, LTR-390UV 紫外线传感器 */
/* 配置在 overlay 文件中，此处仅作文档记录 */
#define PIN_I2C0_SCL       6   /* P0.6 - I2C 时钟线 */
#define PIN_I2C0_SDA       7   /* P0.7 - I2C 数据线 */

/* ========== UART0 调试串口引脚 ========== */
/* 配置在 overlay 文件中，此处仅作文档记录 */
#define PIN_UART0_TX       18  /* P0.18 - 串口发送 */
#define PIN_UART0_RX       14  /* P0.14 - 串口接收 */

/* ========== SPI0 Flash 存储引脚 ========== */
/* W25Q64 8MB SPI Flash，用于历史数据存储 */
/* SCK/MOSI/MISO 配置在 overlay 文件中，CS 由驱动控制 */
#define PIN_SPI0_CS        28  /* P0.28 - W25Q64 片选，低电平有效 */
#define PIN_SPI0_MISO      29  /* P0.29 - SPI 数据输出 */
#define PIN_SPI0_SCK       30  /* P0.30 - SPI 时钟 */
#define PIN_SPI0_MOSI      31  /* P0.31 - SPI 数据输入 */

/* ========== 引脚功能汇总表 ========== */
/*
 * GPIO  | 功能          | 方向 | 说明
 * ------|---------------|------|----------------------------------
 * P0.4  | DATA LED      | 输出 | 数据指示灯，低电平点亮
 * P0.5  | LINK LED      | 输出 | 蓝牙连接指示灯，低电平点亮
 * P0.6  | I2C0 SCL      | 双向 | I2C 时钟线（AHT30/SHT40/SPL06/LTR390）
 * P0.7  | I2C0 SDA      | 双向 | I2C 数据线（AHT30/SHT40/SPL06/LTR390）
 * P0.14 | UART0 RX      | 输入 | 串口接收（调试用）
 * P0.18 | UART0 TX      | 输出 | 串口发送（调试用）
 * P0.28 | W25Q64 CS     | 输出 | SPI Flash 片选，低电平有效
 * P0.29 | W25Q64 MISO   | 输入 | SPI Flash 数据输出
 * P0.30 | W25Q64 SCK    | 输出 | SPI Flash 时钟
 * P0.31 | W25Q64 MOSI   | 输出 | SPI Flash 数据输入
 */

/* ========== 硬件版本标识 ========== */
#define BOARD_NAME         "E104-BT5010A"
#define BOARD_VERSION      "1.1.3"
#define MCU_MODEL          "nRF52810"

#ifdef __cplusplus
}
#endif

#endif /* BOARD_PINS_H_ */
