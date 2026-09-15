/**
 * @file nvs_config.c
 * @brief NVS configuration stored in the PM nvs_storage partition.
 */

#include "nvs_config.h"
#include "w25q64.h"

#include <string.h>

#include <zephyr/devicetree.h>
#include <zephyr/fs/nvs.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/sys/printk.h>

/* NVS is a partition on the same SPI NOR used by history storage. */
static struct nvs_fs nvs_fs;
static const struct flash_area *nvs_area;
static bool nvs_ready;

static void nvs_lock(void)
{
	k_mutex_lock(w25q64_get_mutex(), K_FOREVER);
}

static void nvs_unlock(void)
{
	k_mutex_unlock(w25q64_get_mutex());
}

static int nvs_write_position_locked(const storage_position_t *pos)
{
	int ret = nvs_write(&nvs_fs, NVS_ID_NEXT_SECTOR,
				    &pos->next_sector, sizeof(pos->next_sector));
	if (ret >= 0) {
		ret = nvs_write(&nvs_fs, NVS_ID_OLDEST_SECTOR,
					&pos->oldest_sector, sizeof(pos->oldest_sector));
	}
	if (ret >= 0) {
		ret = nvs_write(&nvs_fs, NVS_ID_NEXT_RECORD,
					&pos->next_record_in_sector,
					sizeof(pos->next_record_in_sector));
	}
	return ret;
}

static int nvs_clear_position_locked(void)
{
	const uint16_t zero = 0U;
	int ret = nvs_write(&nvs_fs, NVS_ID_NEXT_SECTOR, &zero, sizeof(zero));
	if (ret >= 0) {
		ret = nvs_write(&nvs_fs, NVS_ID_OLDEST_SECTOR, &zero, sizeof(zero));
	}
	if (ret >= 0) {
		ret = nvs_write(&nvs_fs, NVS_ID_NEXT_RECORD, &zero, sizeof(zero));
	}
	return ret;
}

/* 获取 NVS 文件系统指针 */
struct nvs_fs *nvs_get_fs(void)
{
	return &nvs_fs;
}

/* 检查 NVS 是否就绪 */
bool nvs_config_is_ready(void)
{
	return nvs_ready;
}

/* 初始化 NVS：分区地址和大小来自 PM 生成的 nvs_storage。 */
int nvs_config_init(void)
{
	int ret = flash_area_open(FIXED_PARTITION_ID(nvs_storage), &nvs_area);
	if (ret != 0 || nvs_area == NULL) {
		printk("[NVS] 无法打开 PM nvs_storage 分区: %d\r\n", ret);
		return ret != 0 ? ret : -ENODEV;
	}

	if ((nvs_area->fa_off % NVS_SECTOR_SIZE) != 0U ||
		(nvs_area->fa_size < (2U * NVS_SECTOR_SIZE)) ||
		(nvs_area->fa_size % NVS_SECTOR_SIZE) != 0U) {
		printk("[NVS] 分区未按 4 KiB 对齐: off=0x%lx size=0x%lx\r\n",
		       (unsigned long)nvs_area->fa_off,
		       (unsigned long)nvs_area->fa_size);
		return -EINVAL;
	}

	nvs_fs.flash_device = nvs_area->fa_dev;
	nvs_fs.offset = nvs_area->fa_off;
	nvs_fs.sector_size = NVS_SECTOR_SIZE;
	nvs_fs.sector_count = nvs_area->fa_size / NVS_SECTOR_SIZE;

	nvs_lock();
	ret = nvs_mount(&nvs_fs);
	nvs_unlock();
	if (ret != 0) {
		printk("[NVS] 挂载失败: %d (off=0x%lx size=%lu)\r\n",
		       ret, (unsigned long)nvs_area->fa_off,
		       (unsigned long)nvs_area->fa_size);
		return ret;
	}

	nvs_ready = true;
	printk("[NVS] 已挂载 W25Q64 分区 (off=0x%lx size=%lu)\r\n",
	       (unsigned long)nvs_area->fa_off,
	       (unsigned long)nvs_area->fa_size);
	return 0;
}

/* 从 NVS 加载存储位置 */
void nvs_load_storage_position(storage_position_t *pos)
{
	if (!nvs_ready || pos == NULL) {
		return;
	}

	uint16_t v;
	ssize_t len;
	bool any = false;

	nvs_lock();
	len = nvs_read(&nvs_fs, NVS_ID_NEXT_SECTOR, &v, sizeof(v));
	if (len == sizeof(v)) {
		pos->next_sector = v;
		any = true;
	}
	len = nvs_read(&nvs_fs, NVS_ID_OLDEST_SECTOR, &v, sizeof(v));
	if (len == sizeof(v)) {
		pos->oldest_sector = v;
		any = true;
	}
	len = nvs_read(&nvs_fs, NVS_ID_NEXT_RECORD, &v, sizeof(v));
	if (len == sizeof(v)) {
		pos->next_record_in_sector = v;
		any = true;
	}
	nvs_unlock();

	if (any) {
		printk("[NVS] 位置已加载 next=%d oldest=%d rec=%d\r\n",
		       pos->next_sector, pos->oldest_sector,
		       pos->next_record_in_sector);
	} else {
		printk("[NVS] 无位置缓存，使用默认值\r\n");
	}
}

/* 保存存储位置到 NVS */
void nvs_save_storage_position(const storage_position_t *pos)
{
	if (!nvs_ready || pos == NULL) {
		return;
	}

	nvs_lock();
	int ret = nvs_write_position_locked(pos);
	nvs_unlock();

	if (ret >= 0) {
		printk("[NVS] 位置已写入 next=%d oldest=%d rec=%d\r\n",
		       pos->next_sector, pos->oldest_sector,
		       pos->next_record_in_sector);
	} else {
		printk("[NVS] 位置写入失败: %d\r\n", ret);
	}
}

/* 清空存储位置信息 */
void nvs_clear_storage_position(void)
{
	if (!nvs_ready) {
		return;
	}

	nvs_lock();
	int ret = nvs_clear_position_locked();
	nvs_unlock();

	if (ret >= 0) {
		printk("[NVS] 位置信息已清空\r\n");
	} else {
		printk("[NVS] 位置信息清空失败: %d\r\n", ret);
	}
}

/* 从 NVS 加载时间基准 */
void nvs_load_time(uint32_t *timestamp, bool *time_synced)
{
	if (timestamp == NULL || time_synced == NULL) {
		return;
	}
	if (!nvs_ready) {
		*time_synced = false;
		*timestamp = 0;
		return;
	}

	uint32_t saved_timestamp;
	nvs_lock();
	ssize_t len = nvs_read(&nvs_fs, NVS_ID_TIME_BASE,
					   &saved_timestamp, sizeof(saved_timestamp));
	nvs_unlock();

	if (len == sizeof(saved_timestamp) && saved_timestamp > 946684800U) {
		*timestamp = saved_timestamp;
		*time_synced = true;

		uint32_t days = saved_timestamp / 86400U;
		uint32_t seconds = saved_timestamp % 86400U;
		uint32_t hours = seconds / 3600U;
		uint32_t minutes = (seconds % 3600U) / 60U;
		uint32_t secs = seconds % 60U;

		printk("[NVS] 时间已加载 时间戳: %u\r\n", saved_timestamp);
		printk("[NVS] UTC 时间: 自1970-01-01起 %u 天 %02u:%02u:%02u\r\n",
		       days, hours, minutes, secs);
	} else {
		printk("[NVS] 无时间缓存，等待时间同步\r\n");
		*time_synced = false;
		*timestamp = 0;
	}
}

/* 保存时间到 NVS */
int nvs_save_time(uint32_t timestamp)
{
	if (!nvs_ready) {
		return -ENODEV;
	}

	nvs_lock();
	int ret = nvs_write(&nvs_fs, NVS_ID_TIME_BASE,
					&timestamp, sizeof(timestamp));
	nvs_unlock();

	if (ret >= 0) {
		printk("[NVS] 时间已保存: %u\r\n", timestamp);
		return 0;
	}
	printk("[NVS] 时间保存失败: %d\r\n", ret);
	return ret;
}

/* 从 NVS 加载设备配置
 *
 * 格式兼容策略（历史演进）：
 *   18 字节 = 当前格式（含 history_interval）
 *   16 字节 = 固件 v2（无 history_interval）= 当前格式去掉 history_interval 后的尾部子集
 *   14 字节 = 更早格式（v2 去掉 firmware_version）
 *    8 字节 = 最早格式（仅 sample/max/min 可信）
 * v2 布局是"字段顺序相近的独立布局"，**不能**按当前结构体直接强转，必须用 device_config_v2_t 重新解释；
 * 低长度版本则都是 v2 的前缀，按实际长度逐字段取用。
 *
 * 迁移语义：旧固件里 sample_interval 事实上就是"落盘周期"，因此把它迁移为新的 history_interval，
 * 避免升级后设备的记录节奏发生变化；采样周期则统一固定为 SAMPLE_INTERVAL_FIXED。
 */
void nvs_load_config(device_config_ctx_t *config)
{
	if (config == NULL) {
		return;
	}

	config->sample_interval = SAMPLE_INTERVAL_FIXED;
	config->history_interval = DEFAULT_HISTORY_INTERVAL;
	config->max_temperature = INT16_MIN;
	config->min_temperature = INT16_MAX;
	config->max_temperature_timestamp = 0;
	config->min_temperature_timestamp = 0;
	config->firmware_version = FIRMWARE_VERSION;
	config->record_count = 0;
	config->config_dirty = false;
	config->position_dirty = false;

	if (!nvs_ready) {
		printk("[配置] NVS 未就绪，使用默认配置\r\n");
		return;
	}

	device_config_t saved;
	device_config_v2_t old;

	memset(&saved, 0, sizeof(saved));
	memset(&old, 0, sizeof(old));

	nvs_lock();
	ssize_t len = nvs_read(&nvs_fs, NVS_ID_CONFIG, &saved, sizeof(saved));
	nvs_unlock();

	/* 统一取出各字段，屏蔽新旧布局差异 */
	uint16_t f_sample;
	int16_t  f_max;
	int16_t  f_min;
	uint32_t f_max_ts = 0;
	uint32_t f_min_ts = 0;
	uint16_t f_fw = FIRMWARE_VERSION;
	bool have_ts = false;

	if (len == (ssize_t)sizeof(device_config_t)) {
		f_sample = saved.sample_interval;
		f_max = saved.max_temperature;
		f_min = saved.min_temperature;
		f_max_ts = saved.max_temperature_timestamp;
		f_min_ts = saved.min_temperature_timestamp;
		f_fw = saved.firmware_version;
		have_ts = true;

		if (saved.history_interval >= MIN_HISTORY_INTERVAL &&
		    saved.history_interval <= MAX_HISTORY_INTERVAL) {
			config->history_interval = saved.history_interval;
		} else {
			printk("[配置] history_interval=%u 超出 [%d,%d]，回退默认 %d 秒\r\n",
			       (unsigned)saved.history_interval,
			       MIN_HISTORY_INTERVAL, MAX_HISTORY_INTERVAL,
			       DEFAULT_HISTORY_INTERVAL);
		}
	} else if (len >= 6) {
		size_t n = ((size_t)len < sizeof(old)) ? (size_t)len : sizeof(old);
		memcpy(&old, &saved, n);

		f_sample = old.sample_interval;
		f_max = old.max_temperature;
		f_min = old.min_temperature;
		if (len >= 14) {
			f_max_ts = old.max_temperature_timestamp;
			f_min_ts = old.min_temperature_timestamp;
			have_ts = true;
		}
		if (len >= 16 && old.firmware_version != 0 &&
		    old.firmware_version != 0xFFFF) {
			f_fw = old.firmware_version;
		}

		/* 旧格式的"采集间隔"即当时的落盘周期，迁移为 history_interval */
		if (f_sample >= MIN_SAMPLE_INTERVAL && f_sample <= MAX_SAMPLE_INTERVAL) {
			uint16_t migrated = (f_sample < MIN_HISTORY_INTERVAL)
					    ? MIN_HISTORY_INTERVAL : f_sample;
			config->history_interval = migrated;
			printk("[配置] 旧格式(%d 字节)迁移：落盘周期 %u 秒 -> history_interval=%u 秒\r\n",
			       (int)len, (unsigned)f_sample, (unsigned)migrated);
		} else {
			printk("[配置] 旧格式(%d 字节)，history_interval 取默认 %d 秒\r\n",
			       (int)len, DEFAULT_HISTORY_INTERVAL);
		}
	} else {
		printk("[配置] 无 NVS 配置，使用默认值\r\n");
		return;
	}

	if (f_sample == 0xFFFF) {
		printk("[配置] NVS 配置未初始化，使用默认值\r\n");
		return;
	}

	if (f_max != 0xFFFF && f_max != 0x0000 &&
		f_max >= -5000 && f_max <= 10000) {
		config->max_temperature = f_max;
		if (have_ts) {
			config->max_temperature_timestamp = f_max_ts;
		}
		printk("[配置] 最高温度: %d.%02d°C\r\n",
		       config->max_temperature / 100,
		       (config->max_temperature >= 0 ? config->max_temperature :
				-config->max_temperature) % 100);
	}

	if (f_min != 0xFFFF && f_min != 0x0000 &&
		f_min >= -5000 && f_min <= 10000) {
		config->min_temperature = f_min;
		if (have_ts) {
			config->min_temperature_timestamp = f_min_ts;
		}
		printk("[配置] 最低温度: %d.%02d°C\r\n",
		       config->min_temperature / 100,
		       (config->min_temperature >= 0 ? config->min_temperature :
				-config->min_temperature) % 100);
	}

	/* 固件版本号以**当前运行的镜像**为准（FIRMWARE_VERSION ← 工程根 VERSION → app_version.h）。
	 *
	 * NVS 里那份只用于识别"这段配置是旧固件写的"，**绝不能反向覆盖当前版本**。
	 * 早期实现是 `config->firmware_version = f_fw;`，后果有两层：
	 *   ① OTA 之后手机通过状态特性读到的仍是上一版的版本号；
	 *   ② 更糟的是 save_config() 会把这个旧值原样写回 NVS，于是错误**自我延续**，
	 *      永远等不到"下一次正确写入"。
	 * 版本号必须反映"现在跑的是什么"，这正是把 VERSION 定为唯一版本源的意义。 */
	if (f_fw != 0 && f_fw != 0xFFFF && f_fw != FIRMWARE_VERSION) {
		printk("[配置] 固件版本变化: NVS 记录 %u -> 当前 %u（配置将重新落盘）\r\n",
		       (unsigned)f_fw, (unsigned)FIRMWARE_VERSION);
		config->config_dirty = true;
	}
	config->firmware_version = FIRMWARE_VERSION;
	printk("[配置] 固件版本: %u（%s）\r\n",
	       (unsigned)config->firmware_version, FIRMWARE_VERSION_STRING);

	config->sample_interval = SAMPLE_INTERVAL_FIXED;
	printk("[配置] 采样固定 %d 秒 / 历史记录间隔 %u 秒（预计保留 %u 天）\r\n",
	       SAMPLE_INTERVAL_FIXED,
	       (unsigned)config->history_interval,
	       (unsigned)(((uint32_t)W25Q64_MAX_RECORDS * config->history_interval) / 86400U));
}

/* 保存设备配置到 NVS */
void nvs_save_config(device_config_ctx_t *config, bool immediate)
{
	if (config == NULL) {
		return;
	}
	config->config_dirty = true;
	if (immediate) {
		nvs_flush(config, NULL);
	}
}

/* 刷新 NVS；一次锁住共享 SPI NOR，避免与历史读写交错。 */
void nvs_flush(device_config_ctx_t *config, const storage_position_t *pos)
{
	if (!nvs_ready) {
		return;
	}

	nvs_lock();
	if (config != NULL && config->config_dirty) {
		device_config_t saved = {
			.sample_interval = SAMPLE_INTERVAL_FIXED,
			.history_interval = config->history_interval,
			.max_temperature = config->max_temperature,
			.min_temperature = config->min_temperature,
			.max_temperature_timestamp = config->max_temperature_timestamp,
			.min_temperature_timestamp = config->min_temperature_timestamp,
			.firmware_version = config->firmware_version
		};
		int ret = nvs_write(&nvs_fs, NVS_ID_CONFIG, &saved, sizeof(saved));
		if (ret >= 0) {
			config->config_dirty = false;
			printk("[NVS] 配置已写入 (采样 %d 秒 / 历史 %u 秒)\r\n",
			       SAMPLE_INTERVAL_FIXED, (unsigned)saved.history_interval);
		} else {
			printk("[NVS] 配置写入失败: %d\r\n", ret);
		}
	}

	if (pos != NULL && config != NULL && config->position_dirty) {
		int ret = nvs_write_position_locked(pos);
		if (ret >= 0) {
			config->position_dirty = false;
		} else {
			printk("[NVS] 位置写入失败: %d\r\n", ret);
		}
	}
	nvs_unlock();
}
