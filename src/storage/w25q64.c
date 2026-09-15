/**
 * @file w25q64.c
 * @brief W25Q64 access through Zephyr's jedec,spi-nor flash driver.
 */

#include "w25q64.h"

#include <zephyr/devicetree.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/sys/printk.h>

#include <errno.h>

#if DT_NODE_EXISTS(DT_NODELABEL(w25q64)) && \
	DT_NODE_HAS_STATUS(DT_NODELABEL(w25q64), okay)
#define W25Q64_NODE DT_NODELABEL(w25q64)
#endif

static const struct device *w25q64_dev;
static bool w25q64_ready;

/* Shared by history access and the NVS partition on the same SPI NOR. */
K_MUTEX_DEFINE(w25q64_mutex);

struct k_mutex *w25q64_get_mutex(void)
{
	return &w25q64_mutex;
}

bool w25q64_is_ready(void)
{
	return w25q64_ready && w25q64_dev != NULL &&
		device_is_ready(w25q64_dev);
}

int w25q64_wait_ready(void)
{
	return w25q64_is_ready() ? 0 : -ENODEV;
}

int w25q64_read(uint32_t addr, uint8_t *data, size_t len)
{
	if (!w25q64_is_ready()) {
		return -ENODEV;
	}
	if (data == NULL && len != 0U) {
		return -EINVAL;
	}
	if (len > 256U) {
		return -EINVAL;
	}

	return flash_read(w25q64_dev, (off_t)addr, data, len);
}

int w25q64_page_program(uint32_t addr, const uint8_t *data, size_t len)
{
	if (!w25q64_is_ready()) {
		return -ENODEV;
	}
	if (data == NULL && len != 0U) {
		return -EINVAL;
	}
	if (len > W25Q64_PAGE_SIZE ||
		(addr % W25Q64_PAGE_SIZE) + len > W25Q64_PAGE_SIZE) {
		return -EINVAL;
	}

	/* spi_nor handles WREN, page program and BUSY polling. */
	return flash_write(w25q64_dev, (off_t)addr, data, len);
}

int w25q64_sector_erase(uint32_t addr)
{
	if (!w25q64_is_ready()) {
		return -ENODEV;
	}

	const uint32_t aligned_addr =
		(addr / W25Q64_SECTOR_SIZE) * W25Q64_SECTOR_SIZE;
	if (aligned_addr != addr) {
		printk("[W25Q64] 地址 0x%06X 未对齐，修正为 0x%06X\r\n",
		       addr, aligned_addr);
	}

	/* spi_nor selects the 4 KiB erase operation for this range. */
	return flash_erase(w25q64_dev, (off_t)aligned_addr,
				   W25Q64_SECTOR_SIZE);
}

/*
 * These functions remain as source-compatible shims for old callers. The
 * raw W25Q64 command path was removed; status/WREN/erase polling now belongs
 * to Zephyr's jedec,spi-nor driver.
 */
int w25q64_read_status_reg1(uint8_t *status)
{
	ARG_UNUSED(status);
	return -ENOTSUP;
}

int w25q64_write_enable(void)
{
	return -ENOTSUP;
}

int w25q64_check_write_protect(void)
{
	return -ENOTSUP;
}

int w25q64_clear_block_protect(void)
{
	return -ENOTSUP;
}

/* Deep power-down is intentionally not used: MCUboot and NVS share this DT device. */
void w25q64_sleep(void)
{
}

void w25q64_wakeup(void)
{
}

int w25q64_init(void)
{
#if defined(W25Q64_NODE)
	w25q64_dev = DEVICE_DT_GET(W25Q64_NODE);
#else
	w25q64_dev = NULL;
#endif

	if (w25q64_dev == NULL || !device_is_ready(w25q64_dev)) {
		printk("[W25Q64] jedec,spi-nor 设备未就绪\r\n");
		return -ENODEV;
	}

	uint64_t size = 0U;
	int ret = flash_get_size(w25q64_dev, &size);
	if (ret != 0 || size != W25Q64_TOTAL_SIZE) {
		printk("[W25Q64] 容量检查失败: ret=%d size=%llu\r\n",
		       ret, (unsigned long long)size);
		return ret != 0 ? ret : -EINVAL;
	}

#if defined(CONFIG_FLASH_JESD216_API)
	uint8_t id[3] = {0};
	ret = flash_read_jedec_id(w25q64_dev, id);
	if (ret == 0) {
		printk("[W25Q64] JEDEC ID: %02X %02X %02X\r\n",
		       id[0], id[1], id[2]);
		if (id[0] != 0xEF || id[1] != 0x40 || id[2] != 0x17) {
			printk("[W25Q64] 警告: JEDEC ID 不匹配，期望 EF 40 17\r\n");
		}
	} else {
		printk("[W25Q64] JEDEC ID 读取失败: %d\r\n", ret);
	}
#endif

	w25q64_ready = true;
	printk("[W25Q64] jedec,spi-nor 已就绪，容量=%llu，历史区=0x%06X\r\n",
	       (unsigned long long)size, W25Q64_STORAGE_BASE);
	return 0;
}
