/**
 * @file w25q64.h
 * @brief W25Q64 SPI Flash 驱动头文件
 */

#ifndef STORAGE_W25Q64_H_
#define STORAGE_W25Q64_H_

#include <zephyr/kernel.h>
#include <zephyr/drivers/spi.h>
#include <zephyr/drivers/gpio.h>
#include <pm_config.h>
#include "../board_pins.h"

#ifdef __cplusplus
extern "C" {
#endif

/* W25Q64 命令定义 */
#define W25Q64_CMD_WRITE_ENABLE      0x06
#define W25Q64_CMD_WRITE_DISABLE     0x04
#define W25Q64_CMD_READ_STATUS_REG1  0x05
#define W25Q64_CMD_WRITE_STATUS_REG1 0x01
#define W25Q64_CMD_READ_STATUS_REG2  0x35
#define W25Q64_CMD_READ_DATA         0x03
#define W25Q64_CMD_PAGE_PROGRAM      0x02
#define W25Q64_CMD_SECTOR_ERASE      0x20
#define W25Q64_CMD_BLOCK_ERASE_32K   0x52
#define W25Q64_CMD_BLOCK_ERASE_64K   0xD8
#define W25Q64_CMD_CHIP_ERASE        0xC7
#define W25Q64_CMD_POWER_DOWN        0xB9
#define W25Q64_CMD_RELEASE_POWER_DOWN 0xAB
#define W25Q64_CMD_DEVICE_ID         0x90
#define W25Q64_CMD_JEDEC_ID          0x9F

/* W25Q64 参数定义 */
#define W25Q64_PAGE_SIZE      256     /* 页大小：256字节 */
#define W25Q64_SECTOR_SIZE    4096    /* 扇区大小：4KB */
#define W25Q64_BLOCK_SIZE_32K 32768   /* 32KB块大小 */
#define W25Q64_BLOCK_SIZE_64K 65536   /* 64KB块大小 */
#define W25Q64_TOTAL_SIZE     0x800000 /* 8MB */

/*
 * W25Q64 layout with MCUboot:
 *   0x000000 - 0x027FFF : MCUboot secondary image
 *   0x028000 - 0x02DFFF : NVS (PM nvs_storage, 24 KiB)
 *   0x02E000 -           : history records
 */
#define W25Q64_MCUBOOT_SECONDARY_BASE PM_MCUBOOT_SECONDARY_ADDRESS
#define W25Q64_MCUBOOT_SECONDARY_SIZE PM_MCUBOOT_SECONDARY_SIZE
#define W25Q64_NVS_BASE               PM_NVS_STORAGE_ADDRESS
#define W25Q64_NVS_SIZE               PM_NVS_STORAGE_SIZE
#define W25Q64_STORAGE_BASE           PM_NVS_STORAGE_END_ADDRESS

#if PM_MCUBOOT_SECONDARY_END_ADDRESS != PM_NVS_STORAGE_ADDRESS
#error "mcuboot_secondary and nvs_storage must be adjacent"
#endif
#define W25Q64_STORAGE_SIZE   0x100000  /* 1MB存储区域 */
#define W25Q64_MAX_SECTORS    256       /* 1MB / 4KB = 256个扇区 */

/* 记录大小和容量定义 */
#define W25Q64_RECORD_SIZE        12        /* 每条记录12字节 */
#define W25Q64_RECORDS_PER_PAGE   (W25Q64_PAGE_SIZE / W25Q64_RECORD_SIZE)    /* 21条/页 (256/12=21, 浪费4字节) */
#define W25Q64_PAGES_PER_SECTOR   (W25Q64_SECTOR_SIZE / W25Q64_PAGE_SIZE)    /* 16页/扇区 */

/* 每扇区记录数：使用页对齐容量，避免地址溢出到下一扇区 */
/* 理论值 4096/12=341，但页对齐实际值 = 16页 × 21条/页 = 336条 */
#define W25Q64_RECORDS_PER_SECTOR (W25Q64_PAGES_PER_SECTOR * W25Q64_RECORDS_PER_PAGE)  /* 336条/扇区 */

/* 最大记录数限制：65000条 */
#define W25Q64_MAX_RECORDS 65000
#define W25Q64_MAX_SECTORS_LIMIT ((W25Q64_MAX_RECORDS + W25Q64_RECORDS_PER_SECTOR - 1) / W25Q64_RECORDS_PER_SECTOR)

/* CS 引脚定义（引用 board_pins.h）*/
#define W25Q64_CS_PIN PIN_SPI0_CS

/**
 * @brief 初始化 W25Q64 Flash
 * @return 0 成功，负数错误码
 */
int w25q64_init(void);

/**
 * @brief 检查 W25Q64 是否已就绪
 * @return true 已就绪，false 未就绪
 */
bool w25q64_is_ready(void);

/**
 * @brief 进入深度掉电模式（低功耗）
 */
void w25q64_sleep(void);

/**
 * @brief 从深度掉电模式唤醒
 */
void w25q64_wakeup(void);

/**
 * @brief 等待 Flash 就绪
 * @return 0 成功，负数错误码
 */
int w25q64_wait_ready(void);

/**
 * @brief 写使能
 * @return 0 成功，负数错误码
 */
int w25q64_write_enable(void);

/**
 * @brief 读取状态寄存器1
 * @param status 状态输出
 * @return 0 成功，负数错误码
 */
int w25q64_read_status_reg1(uint8_t *status);

/**
 * @brief 检查写保护状态
 * @return 0 成功，负数错误码
 */
int w25q64_check_write_protect(void);

/**
 * @brief 清除块保护
 * @return 0 成功，负数错误码
 */
int w25q64_clear_block_protect(void);

/**
 * @brief 读取数据
 * @param addr 起始地址
 * @param data 数据缓冲区
 * @param len 数据长度（最大256字节）
 * @return 0 成功，负数错误码
 */
int w25q64_read(uint32_t addr, uint8_t *data, size_t len);

/**
 * @brief 页编程（写入数据）
 * @param addr 起始地址（必须页对齐）
 * @param data 数据缓冲区
 * @param len 数据长度（最大256字节，不能跨页）
 * @return 0 成功，负数错误码
 */
int w25q64_page_program(uint32_t addr, const uint8_t *data, size_t len);

/**
 * @brief 扇区擦除（4KB）
 * @param addr 扇区地址（会自动对齐）
 * @return 0 成功，负数错误码
 */
int w25q64_sector_erase(uint32_t addr);

/**
 * @brief 获取 W25Q64 互斥锁
 * @return 互斥锁指针
 */
struct k_mutex *w25q64_get_mutex(void);

#ifdef __cplusplus
}
#endif

#endif /* STORAGE_W25Q64_H_ */
