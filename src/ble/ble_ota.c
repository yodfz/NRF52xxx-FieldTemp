/**
 * @file ble_ota.c
 * @brief 自定义极简 BLE OTA 实现（重活已搬出 GATT 回调）
 *
 * 线程模型（2026-09-13 重构）
 * --------------------------
 * 早先版本把整槽擦除、Flash 写入、NVS 落盘、整镜像 SHA-256 **全部直接做在
 * BT RX 上下文**里，实测把 `rx_thread_stack` 推到 968/1024（只剩 56 B）。
 * 加栈容量只能"不爆"，解决不了"长时间卡住蓝牙接收线程"。
 *
 * 现在：
 *
 *   GATT 回调（BT RX 线程）        系统工作队列（sys_work_q）
 *   ─────────────────────────      ─────────────────────────────
 *   校验长度 / 状态 / 密钥          整槽擦除（START，约 1~2 s）
 *   拷贝分片进 512 B 环形缓冲  ──▶  Flash 写入（排空环形缓冲）
 *   k_work_submit()                 续传状态落盘（NVS）
 *                                  整镜像 SHA-256（END）
 *                                  保存写头 + boot_request_upgrade + 重启
 *
 * 复用现有系统工作队列的理由：OTA 期间历史落盘与普通 NVS 写入已被
 * ble_ota_in_progress() 暂停（见 main.c），队列不会与它们争用。
 *
 * 背压：Write Without Response 在 ATT 层没有背压，手机只能靠 Status 通知
 *       （每 8 包一次，报"已写入 Flash 的连续偏移"）自我限速。若环形缓冲仍被打满，
 *       设备**不静默丢数据**（那会产出校验不过的坏镜像），而是置 err=OVERRUN 中止
 *       会话，让手机重新 START 续传。
 *
 * 处理顺序（与需求一致）：
 *   1) 进入 OTA 模式 → 暂停历史落盘、延迟历史/NVS 写入
 *   2) 检查文件大小不超过次级槽容量
 *   3) 只擦除次级槽分区（分区边界保证不会碰到 0x28000 之后的 NVS 与历史）
 *   4) 分片写入（Write Without Response，纯顺序追加）
 *   5) 校验字节数 / magic / 版本 / SHA-256
 *   6) 保存历史写头与配置 → boot_request_upgrade() → 通知 App → sys_reboot()
 *   7) MCUboot 负责 ECDSA 验签与覆盖主槽
 */

#include <zephyr/kernel.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/storage/flash_map.h>
#include <zephyr/storage/stream_flash.h>
#include <zephyr/dfu/flash_img.h>
#include <zephyr/dfu/mcuboot.h>
#include <zephyr/sys/reboot.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/util.h>
#include <string.h>

#include "ble_ota.h"
#include "../storage/nvs_config.h"
#include "../storage/w25q64.h"

/* 发布态 CONFIG_LOG=n / CONFIG_PRINTK=n。这里**不能**依赖 LOG_MODULE_REGISTER
 * 和 <zephyr/logging/log.h>：日志关掉时它们会引入不必要的段与符号。
 * 调试时临时打开 CONFIG_LOG=y 即可恢复真实日志。 */
#ifndef CONFIG_LOG
#define LOG_INF(...) do { } while (false)
#define LOG_WRN(...) do { } while (false)
#define LOG_ERR(...) do { } while (false)
#endif

/* 每收到这么多包，用 Status 通知一次"已写入 Flash 的连续偏移"。
 * 不逐包确认是刻意的：逐包 notify 会把 ACL 缓冲和射频时间抢走，反而拖慢传输。 */
#define OTA_ACK_PACKETS   8U

/* BT_GATT_SERVICE_DEFINE(ota_service, ...) 生成的是
 *   const struct bt_gatt_attr attr_ota_service[];
 * 而不是 `ota_service.attrs`；且它定义在**文件末尾**，因此这里必须先声明，
 * 供上面的通知函数使用。下标见服务定义处的注释。 */
extern const struct bt_gatt_attr attr_ota_service[];
#define OTA_STATUS_ATTR_IDX 8U

/* MCUboot 镜像头（与 bootloader/mcuboot/bootutil/include/image.h 一致） */
#define IMAGE_MAGIC       0x96f3b83dU
#define IH_HDR_SIZE_OFF   8U    /* uint16 */
#define IH_IMG_SIZE_OFF   12U   /* uint32 */
#define IH_VER_OFF        20U   /* 8 bytes: major u8 / minor u8 / revision u16 / build u32 */
#define IH_VER_LEN        8U

#define OTA_SLOT_ID       FIXED_PARTITION_ID(mcuboot_secondary)

/* 环形缓冲：2 × 256 B = 512 B。
 * 生产者在 BT RX 线程（GATT 回调），消费者在系统工作队列。
 * 槽大小取 CONFIG_IMG_BLOCK_BUF_SIZE(256) = W25Q64 页大小，一次页编程正好写满。 */
#define OTA_RING_SLOTS      2U
#define OTA_RING_CHUNK      ((uint16_t)CONFIG_IMG_BLOCK_BUF_SIZE)

/* ------------------------------------------------------------------ */
/* 状态                                                                */
/* ------------------------------------------------------------------ */
static struct flash_img_context ota_ctx;
static const struct flash_area *ota_fa;
static bool   ota_slot_open;

static uint8_t  ota_state = OTA_STATE_IDLE;
static uint8_t  ota_err   = OTA_ERR_NONE;
static bool     ota_busy;       /* 有 OTA 会话在跑（含 START 的准备期） */
static uint32_t ota_total;      /* 声明的总字节数 */
static uint32_t ota_base;       /* 本会话起始偏移（续传时 > 0） */
static uint32_t ota_recv;       /* 已接受字节数（含续传前缀） */
static uint8_t  ota_ih_ver[IH_VER_LEN];
static uint8_t  ota_exp_sha[OTA_SHA256_LEN];   /* 手机端声明的整文件 SHA-256 */
static uint16_t ota_pkt_since_ack;
static bool     ota_ack_wanted;

static uint8_t  ota_ring[OTA_RING_SLOTS][OTA_RING_CHUNK];
static uint16_t ota_ring_len[OTA_RING_SLOTS];
static uint8_t  ota_ring_head;          /* 生产者（GATT 回调）正在填充的槽 */
static struct k_spinlock ota_ring_lock;

/* ------------------------------------------------------------------ */
/* 工作队列                                                            */
/* ------------------------------------------------------------------ */
enum ota_req {
	OTA_REQ_NONE = 0,
	OTA_REQ_START,
	OTA_REQ_END,
	OTA_REQ_CANCEL,
	OTA_REQ_TRIGGER,
};

static struct k_work ota_work;
static uint8_t ota_pending_req;

static void ota_work_handler(struct k_work *work);

static void ota_submit(uint8_t req)
{
	if (req != OTA_REQ_NONE) {
		ota_pending_req = req;
	}
	/* k_work_submit 对已在队列中的 work 是幂等的，逐包调用没有开销问题。 */
	(void)k_work_submit(&ota_work);
}

/* ------------------------------------------------------------------ */
/* GATT                                                                */
/* ------------------------------------------------------------------ */
static struct bt_uuid_128 ota_service_uuid = BT_UUID_INIT_128(BT_UUID_OTA_SERVICE_VAL);
static struct bt_uuid_128 ota_ctrl_uuid    = BT_UUID_INIT_128(BT_UUID_OTA_CONTROL_VAL);
static struct bt_uuid_128 ota_data_uuid    = BT_UUID_INIT_128(BT_UUID_OTA_DATA_VAL);
static struct bt_uuid_128 ota_status_uuid  = BT_UUID_INIT_128(BT_UUID_OTA_STATUS_VAL);

static void ota_status_ccc_cfg_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);
	LOG_INF("OTA status notify %s", value == BT_GATT_CCC_NOTIFY ? "on" : "off");
}

/* ------------------------------------------------------------------ */
/* 内部工具                                                            */
/* ------------------------------------------------------------------ */

/* 已真正写进 Flash 的**连续**字节数（不含缓冲区里还没落盘的）。
 * 这就是给手机做流控的依据——注意它滞后于已接收字节数，这是刻意的。 */
static uint32_t ota_confirmed_bytes(void)
{
	size_t committed = 0;

	if (ota_slot_open) {
		committed = stream_flash_bytes_written(&ota_ctx.stream);
	}
	return ota_base + (uint32_t)committed;
}

static void ota_notify_status(void)
{
	uint8_t buf[OTA_STATUS_LEN];
	uint32_t confirmed = ota_confirmed_bytes();
	uint8_t progress = 0;

	if (ota_total != 0U) {
		progress = (uint8_t)(((uint64_t)confirmed * 100U) / ota_total);
	}

	sys_put_le32(confirmed, &buf[0]);
	sys_put_le32(ota_total, &buf[4]);
	buf[8]  = ota_state;
	buf[9]  = ota_err;
	buf[10] = progress;
	buf[11] = 0U;   /* flags: 预留 */

	/* 下标校验：万一以后调整服务定义顺序，宁可不发通知也不要发错特征 */
	if (attr_ota_service[OTA_STATUS_ATTR_IDX].uuid == &ota_status_uuid.uuid) {
		bt_gatt_notify(NULL, &attr_ota_service[OTA_STATUS_ATTR_IDX],
			       buf, sizeof(buf));
	}
}

static void ota_set_err(uint8_t err)
{
	ota_err = err;
	ota_state = OTA_STATE_ERROR;
	ota_busy = false;
	LOG_WRN("OTA 错误: %u", err);
	ota_notify_status();
}

static void ota_slot_release(void)
{
	if (ota_slot_open) {
		flash_area_close(ota_fa);
		ota_fa = NULL;
		ota_slot_open = false;
	}
}

/* 打开次级槽并从 `resume_off` 处开始写。
 * 用 stream_flash_init() 显式指定 offset，从而支持断点续传；
 * 同时**不**使用 flash_img_init_id()，因为它在内部把 offset 固定为 0。 */
static int ota_slot_prepare(uint32_t resume_off)
{
	int rc;

	if (resume_off != 0U) {
		rc = flash_area_open(OTA_SLOT_ID, &ota_fa);
		if (rc != 0) {
			return rc;
		}
		if (resume_off >= ota_fa->fa_size) {
			flash_area_close(ota_fa);
			ota_fa = NULL;
			return -EINVAL;
		}
		rc = stream_flash_init(&ota_ctx.stream, ota_fa->fa_dev,
				       ota_ctx.buf, sizeof(ota_ctx.buf),
				       ota_fa->fa_off + resume_off,
				       ota_fa->fa_size - resume_off, NULL);
		if (rc != 0) {
			flash_area_close(ota_fa);
			ota_fa = NULL;
			return rc;
		}
		ota_ctx.flash_area = ota_fa;
		ota_slot_open = true;
		return 0;
	}

	rc = flash_img_init_id(&ota_ctx, OTA_SLOT_ID);
	if (rc != 0) {
		return rc;
	}
	ota_fa = ota_ctx.flash_area;
	ota_slot_open = true;
	return 0;
}

/* ------------------------------------------------------------------ */
/* 环形缓冲                                                            */
/* ------------------------------------------------------------------ */
/* 只在持有 ota_ring_lock 时调用。返回 -ENOSPC 表示两个槽都满、
 * 消费者还没排空 —— 调用方必须中止会话，绝不能静默丢弃。 */
static int ota_ring_put_locked(const uint8_t *data, uint16_t len)
{
	while (len > 0U) {
		uint16_t used = ota_ring_len[ota_ring_head];
		uint16_t space = (uint16_t)(OTA_RING_CHUNK - used);

		if (space == 0U) {
			if ((ota_ring_head + 1U) >= OTA_RING_SLOTS) {
				return -ENOSPC;
			}
			ota_ring_head++;
			continue;
		}

		uint16_t n = MIN(len, space);

		memcpy(&ota_ring[ota_ring_head][used], data, n);
		ota_ring_len[ota_ring_head] = (uint16_t)(used + n);
		data += n;
		len = (uint16_t)(len - n);
	}
	return 0;
}

/* 把环形缓冲里的数据写进 Flash。只在系统工作队列中执行 —— 这里会做 SPI 传输，
 * 绝不能在自旋锁内做（自旋锁会关中断）。先在锁内把槽拷到本地，出锁后再写。 */
static void ota_drain(void)
{
	uint8_t local[OTA_RING_CHUNK];

	for (;;) {
		k_spinlock_key_t key;
		uint16_t n = 0U;
		bool got = false;

		key = k_spin_lock(&ota_ring_lock);
		for (uint8_t i = 0U; i < OTA_RING_SLOTS; i++) {
			if (ota_ring_len[i] != 0U) {
				n = ota_ring_len[i];
				memcpy(local, ota_ring[i], n);
				ota_ring_len[i] = 0U;
				got = true;
				break;
			}
		}
		if (ota_ring_len[0] == 0U && ota_ring_len[1] == 0U) {
			ota_ring_head = 0U;     /* 全排空后从头填 */
		}
		k_spin_unlock(&ota_ring_lock, key);

		if (!got) {
			break;
		}
		if (!ota_slot_open) {
			continue;               /* 槽已释放：丢弃残余数据 */
		}
		if (flash_img_buffered_write(&ota_ctx, local, n, false) != 0) {
			LOG_ERR("OTA 写 Flash 失败");
			ota_slot_release();
			ota_set_err(OTA_ERR_FLASH);
			return;
		}
	}
}

/* ------------------------------------------------------------------ */
/* 状态持久化（续传用）                                                */
/* ------------------------------------------------------------------ */
typedef struct {
	uint32_t offset;
	uint32_t total;
	uint8_t  ih_ver[IH_VER_LEN];
	uint8_t  img_id[OTA_IMG_ID_LEN];   /* SHA-256 前 16 字节：续传身份的**决定性**依据 */
	uint8_t  valid;
} ota_resume_t;

/* 直接走 nvs_get_fs()，并用与 nvs_config.c **同一个** SPI 互斥量串行化
 * （历史/NVS/OTA 共用一颗 W25Q64，必须互斥）。OTA 期间 main.c 会暂停自己的
 * NVS 写入，这里的访问因此是唯一的写者。以下三个函数只在**工作队列**中调用。 */
static int ota_resume_load(ota_resume_t *r)
{
	struct nvs_fs *fs = nvs_get_fs();
	struct k_mutex *m = w25q64_get_mutex();
	ssize_t n;

	if (fs == NULL || m == NULL) {
		return -ENOENT;
	}
	k_mutex_lock(m, K_FOREVER);
	n = nvs_read(fs, NVS_ID_OTA, r, sizeof(*r));
	k_mutex_unlock(m);
	return (n == (ssize_t)sizeof(*r)) ? 0 : -ENOENT;
}

static int ota_resume_save(const ota_resume_t *r)
{
	struct nvs_fs *fs = nvs_get_fs();
	struct k_mutex *m = w25q64_get_mutex();
	ssize_t n;

	if (fs == NULL || m == NULL) {
		return -ENOENT;
	}
	k_mutex_lock(m, K_FOREVER);
	n = nvs_write(fs, NVS_ID_OTA, r, sizeof(*r));
	k_mutex_unlock(m);
	return (n == (ssize_t)sizeof(*r)) ? 0 : -EIO;
}

static int ota_resume_clear(void)
{
	struct nvs_fs *fs = nvs_get_fs();
	struct k_mutex *m = w25q64_get_mutex();
	int rc;

	if (fs == NULL || m == NULL) {
		return -ENOENT;
	}
	k_mutex_lock(m, K_FOREVER);
	rc = nvs_delete(fs, NVS_ID_OTA);
	k_mutex_unlock(m);
	return rc;
}

/* ------------------------------------------------------------------ */
/* 重活：只在系统工作队列中执行                                          */
/* ------------------------------------------------------------------ */
static void ota_do_start(void)
{
	ota_resume_t r;
	uint32_t resume_off = 0U;
	int rc;

	/*
	 * 续传判定必须同时比对 **total + ih_ver + SHA-256 前缀**。
	 * 只用 (total, ih_ver) 是不够的：两个同版本、同大小但内容不同的镜像
	 * 会被错误拼接，产出"大小与版本都对、内容却各占一半"的坏镜像。
	 * 任一项不符 → 清掉续传状态、整槽重来。
	 */
	memset(&r, 0, sizeof(r));
	if (ota_resume_load(&r) == 0 && r.valid == 1U &&
	    r.total == ota_total &&
	    memcmp(r.ih_ver, ota_ih_ver, IH_VER_LEN) == 0 &&
	    memcmp(r.img_id, ota_exp_sha, OTA_IMG_ID_LEN) == 0) {
		/* 向下对齐到写入块边界，避免半个缓冲区的残留 */
		resume_off = r.offset & ~((uint32_t)OTA_RING_CHUNK - 1U);
		LOG_INF("OTA 续传：从 %u/%u 继续", resume_off, ota_total);
	} else {
		if (r.valid == 1U) {
			LOG_WRN("OTA 续传身份不匹配，整槽重来");
		}
		(void)ota_resume_clear();
	}

	ota_slot_release();
	if (ota_slot_prepare(resume_off) != 0) {
		ota_set_err(OTA_ERR_FLASH);
		return;
	}
	if (ota_total > ota_fa->fa_size) {
		LOG_WRN("OTA 镜像 %u B 超过次级槽 %u B", ota_total, ota_fa->fa_size);
		ota_slot_release();
		ota_set_err(OTA_ERR_TOO_LARGE);
		return;
	}

	if (resume_off == 0U) {
		/* 整槽擦除：一次做完，换来传输期间不再有擦除抖动。
		 * 只擦分区本身 —— 分区边界保证不会碰到 NVS(0x28000+) 与历史。
		 * 这一步耗时约 1~2 s，正是必须搬出 GATT 回调的原因。 */
		rc = flash_area_erase(ota_fa, 0, ota_fa->fa_size);
		if (rc != 0) {
			LOG_ERR("擦除次级槽失败: %d", rc);
			ota_slot_release();
			ota_set_err(OTA_ERR_FLASH);
			return;
		}
	}

	ota_base = resume_off;
	ota_recv = resume_off;
	ota_err = OTA_ERR_NONE;
	ota_state = OTA_STATE_READY;
	LOG_INF("OTA 开始: total=%u, resume=%u", ota_total, ota_base);
	ota_notify_status();
}

static void ota_do_end(void)
{
	uint8_t hdr[32];
	uint32_t committed;
	int rc;

	if (ota_state != OTA_STATE_READY && ota_state != OTA_STATE_RECEIVING) {
		ota_set_err(OTA_ERR_STATE);
		return;
	}

	/* 先把环形缓冲排空，再 flush stream 的尾部缓冲 */
	ota_drain();
	rc = flash_img_buffered_write(&ota_ctx, NULL, 0, true);
	if (rc != 0) {
		ota_set_err(OTA_ERR_FLASH);
		return;
	}

	committed = ota_confirmed_bytes();
	if (committed != ota_total) {
		LOG_WRN("OTA 字节数不符: 已写 %u / 声明 %u", committed, ota_total);
		ota_set_err(OTA_ERR_SIZE);
		return;
	}

	rc = flash_area_read(ota_fa, 0, hdr, sizeof(hdr));
	if (rc != 0) {
		ota_set_err(OTA_ERR_FLASH);
		return;
	}
	if (sys_get_le32(&hdr[0]) != IMAGE_MAGIC) {
		LOG_WRN("OTA 镜像 magic 不对: 0x%08x", sys_get_le32(&hdr[0]));
		ota_set_err(OTA_ERR_MAGIC);
		return;
	}
	if (memcmp(&hdr[IH_VER_OFF], ota_ih_ver, IH_VER_LEN) != 0) {
		LOG_WRN("OTA 镜像版本与 START 声明不符");
		ota_set_err(OTA_ERR_VERSION);
		return;
	}

#ifdef CONFIG_IMG_ENABLE_IMAGE_CHECK
	{
		/* 对收到的**全部** total 字节算 SHA-256，与手机在 START 里声明的
		 * 32 字节摘要比对 —— 证明"收到的就是手机那个文件"。
		 *
		 * flash_area_check_int_sha256 的语义（读源码确认，别信注释）：
		 *   clen  = 参与哈希的**字节数**（不是比对长度）
		 *   match = 期望的**完整 32 字节**摘要（内部 memcmp 32 字节）
		 *
		 * 该函数把 mbedtls_sha256_context 与 hash[32] 放在**栈上**，
		 * 而现在它跑在系统工作队列（栈 1280），不是 BT RX 线程。
		 */
		struct flash_area_check fac = {
			.match = ota_exp_sha,
			.clen  = ota_total,
			.off   = 0U,
			.rbuf  = ota_ctx.buf,
			.rblen = sizeof(ota_ctx.buf),
		};

		rc = flash_area_check_int_sha256(ota_fa, &fac);
		if (rc != 0) {
			LOG_WRN("OTA SHA-256 校验失败: %d", rc);
			ota_set_err(OTA_ERR_HASH);
			return;
		}
		LOG_INF("OTA SHA-256 校验通过（与手机声明一致）");
	}
#endif
	LOG_INF("OTA 校验通过: %u B", committed);
	ota_state = OTA_STATE_VERIFY;
	ota_notify_status();
}

static void ota_do_cancel(void)
{
	LOG_INF("OTA 取消");
	ota_slot_release();
	(void)ota_resume_clear();
	ota_total = 0U;
	ota_base = 0U;
	ota_recv = 0U;
	ota_err = OTA_ERR_NONE;
	ota_busy = false;
	memset(ota_exp_sha, 0, sizeof(ota_exp_sha));
	ota_state = OTA_STATE_IDLE;
	ota_notify_status();
}

static void ota_do_trigger(void)
{
	int rc;

	if (ota_state != OTA_STATE_VERIFY) {
		ota_set_err(OTA_ERR_STATE);
		return;
	}
	/*
	 * 顺序上把"保存历史写头与配置"放在 boot_request_upgrade() **之前**：
	 * 需求写的是先 request 再保存，但那样一旦保存失败就带着脏写头重启；
	 * 先保存则最坏情况是"升级没触发、数据没丢"，可以重来。
	 */
	LOG_INF("OTA 清除续传状态并请求升级…");
	(void)ota_resume_clear();

	rc = boot_request_upgrade(BOOT_UPGRADE_PERMANENT);
	if (rc != 0) {
		LOG_ERR("boot_request_upgrade 失败: %d", rc);
		ota_set_err(OTA_ERR_INTERNAL);
		return;
	}
	ota_state = OTA_STATE_PENDING;
	ota_notify_status();

	LOG_INF("OTA 升级已请求，重启…");
	sys_reboot(SYS_REBOOT_WARM);
}

static void ota_work_handler(struct k_work *work)
{
	uint8_t req;

	ARG_UNUSED(work);
	req = ota_pending_req;
	ota_pending_req = OTA_REQ_NONE;

	/* END 必须先把手上的数据排空再校验；其余控制请求要先于排空处理 */
	if (req == OTA_REQ_END) {
		ota_do_end();
		return;
	}
	switch (req) {
	case OTA_REQ_START:
		ota_do_start();
		return;
	case OTA_REQ_CANCEL:
		ota_do_cancel();
		return;
	case OTA_REQ_TRIGGER:
		ota_do_trigger();
		return;
	default:
		break;
	}

	ota_drain();

	if (ota_ack_wanted) {
		ota_resume_t r;

		ota_ack_wanted = false;
		ota_notify_status();

		/* 周期性把"已确认偏移"落盘，供断线后续传 */
		r.offset = ota_confirmed_bytes();
		r.total = ota_total;
		r.valid = 1U;
		memcpy(r.ih_ver, ota_ih_ver, IH_VER_LEN);
		memcpy(r.img_id, ota_exp_sha, OTA_IMG_ID_LEN);
		(void)ota_resume_save(&r);
	}
}

/* ------------------------------------------------------------------ */
/* GATT 回调：只做校验 / 拷贝 / 投递                                     */
/* ------------------------------------------------------------------ */
static ssize_t ota_ctrl_write_cb(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				 const void *buf, uint16_t len, uint16_t offset,
				 uint8_t flags)
{
	static const uint8_t auth_key[OTA_AUTH_KEY_LEN] = OTA_AUTH_KEY;
	const uint8_t *p = buf;
	uint8_t op;

	ARG_UNUSED(conn);
	ARG_UNUSED(attr);
	ARG_UNUSED(offset);
	ARG_UNUSED(flags);

	if (len < 1U) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	op = p[0];

	if (op == OTA_CTRL_START) {
		/* [op][key 16][total 4][ih_ver 8][sha256 32] = 61 */
		if (len != OTA_START_LEN) {
			ota_set_err(OTA_ERR_LEN);
			return len;
		}
		if (memcmp(&p[1], auth_key, OTA_AUTH_KEY_LEN) != 0) {
			LOG_WRN("OTA 授权失败（密钥不匹配）");
			ota_set_err(OTA_ERR_AUTH);
			return len;
		}
		/* 只拷参数，擦除留给工作队列 */
		ota_total = sys_get_le32(&p[17]);
		memcpy(ota_ih_ver, &p[21], IH_VER_LEN);
		memcpy(ota_exp_sha, &p[29], OTA_SHA256_LEN);
		ota_pkt_since_ack = 0U;
		ota_ack_wanted = false;
		ota_err = OTA_ERR_NONE;
		ota_busy = true;
		ota_state = OTA_STATE_IDLE;   /* 准备期：Data 会被拒，等 READY 通知 */
		ota_submit(OTA_REQ_START);
		return len;
	}

	if (op == OTA_CTRL_END) {
		if (ota_state != OTA_STATE_READY && ota_state != OTA_STATE_RECEIVING) {
			ota_set_err(OTA_ERR_STATE);
			return len;
		}
		ota_submit(OTA_REQ_END);
		return len;
	}

	if (op == OTA_CTRL_CANCEL) {
		ota_submit(OTA_REQ_CANCEL);
		return len;
	}

	if (op == OTA_CTRL_TRIGGER) {
		if (ota_state != OTA_STATE_VERIFY) {
			ota_set_err(OTA_ERR_STATE);
			return len;
		}
		ota_submit(OTA_REQ_TRIGGER);
		return len;
	}

	ota_set_err(OTA_ERR_STATE);
	return len;
}

static ssize_t ota_data_write_cb(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				 const void *buf, uint16_t len, uint16_t offset,
				 uint8_t flags)
{
	k_spinlock_key_t key;
	int rc;

	ARG_UNUSED(conn);
	ARG_UNUSED(attr);
	ARG_UNUSED(offset);
	ARG_UNUSED(flags);

	if (ota_state != OTA_STATE_READY && ota_state != OTA_STATE_RECEIVING) {
		return BT_GATT_ERR(BT_ATT_ERR_UNLIKELY);
	}
	if (len == 0U) {
		return 0;
	}

	key = k_spin_lock(&ota_ring_lock);
	if ((uint64_t)ota_recv + len > ota_total) {
		rc = -EOVERFLOW;
	} else {
		rc = ota_ring_put_locked(buf, len);
	}
	if (rc == 0) {
		ota_recv += len;
		ota_state = OTA_STATE_RECEIVING;
		if (++ota_pkt_since_ack >= OTA_ACK_PACKETS) {
			ota_pkt_since_ack = 0U;
			ota_ack_wanted = true;
		}
	}
	k_spin_unlock(&ota_ring_lock, key);

	if (rc == -EOVERFLOW) {
		LOG_WRN("OTA 数据溢出：接收 %u + %u > 声明 %u", ota_recv, len, ota_total);
		ota_set_err(OTA_ERR_SIZE);
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}
	if (rc != 0) {
		/* 环形缓冲被打满。绝不静默丢数据 —— 那会产出"大小对但内容错"的坏镜像，
		 * END 的 SHA-256 也救不回来（手机会以为传成功了）。直接中止会话，
		 * 让手机重新 START（可从已确认偏移续传）。 */
		LOG_WRN("OTA 接收缓冲溢出（%u 槽已满），中止会话", OTA_RING_SLOTS);
		ota_set_err(OTA_ERR_OVERRUN);
		return BT_GATT_ERR(BT_ATT_ERR_UNLIKELY);
	}

	ota_submit(OTA_REQ_NONE);
	return len;
}

/* ------------------------------------------------------------------ */
/* 服务定义                                                            */
/* ------------------------------------------------------------------ */
/* 属性下标（ota_notify_status 依赖，改动顺序时必须同步）：
 *   [0] service  [1] ctrl char  [2] ctrl value  [3] ctrl CUD
 *   [4] data char  [5] data value  [6] data CUD
 *   [7] status char  [8] status value  [9] status CUD  [10] CCC */
BT_GATT_SERVICE_DEFINE(ota_service,
	BT_GATT_PRIMARY_SERVICE(&ota_service_uuid),
	BT_GATT_CHARACTERISTIC(&ota_ctrl_uuid.uuid,
			       BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_WRITE,
			       NULL, ota_ctrl_write_cb, NULL),
	BT_GATT_CUD("OTA Control", BT_GATT_PERM_READ),
	BT_GATT_CHARACTERISTIC(&ota_data_uuid.uuid,
			       BT_GATT_CHRC_WRITE_WITHOUT_RESP,
			       BT_GATT_PERM_WRITE,
			       NULL, ota_data_write_cb, NULL),
	BT_GATT_CUD("OTA Data", BT_GATT_PERM_READ),
	BT_GATT_CHARACTERISTIC(&ota_status_uuid.uuid,
			       BT_GATT_CHRC_NOTIFY,
			       0,
			       NULL, NULL, NULL),
	BT_GATT_CUD("OTA Status", BT_GATT_PERM_READ),
	BT_GATT_CCC(ota_status_ccc_cfg_changed,
		    BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* ------------------------------------------------------------------ */
/* 对外 API                                                            */
/* ------------------------------------------------------------------ */
void ble_ota_init(void)
{
	ota_state = OTA_STATE_IDLE;
	ota_err = OTA_ERR_NONE;
	ota_busy = false;
	ota_total = 0U;
	ota_base = 0U;
	ota_recv = 0U;
	ota_pkt_since_ack = 0U;
	ota_ack_wanted = false;
	ota_pending_req = OTA_REQ_NONE;
	ota_ring_head = 0U;
	memset(ota_ring_len, 0, sizeof(ota_ring_len));
	ota_slot_open = false;
	ota_fa = NULL;

	k_work_init(&ota_work, ota_work_handler);

	if (attr_ota_service[OTA_STATUS_ATTR_IDX].uuid != &ota_status_uuid.uuid) {
		LOG_WRN("OTA Status 属性下标与预期不符，通知将不可用");
	}
}

bool ble_ota_in_progress(void)
{
	return ota_busy ||
	       (ota_state == OTA_STATE_READY) ||
	       (ota_state == OTA_STATE_RECEIVING) ||
	       (ota_state == OTA_STATE_VERIFY) ||
	       (ota_state == OTA_STATE_PENDING);
}

void ble_ota_get_state(uint8_t *state, uint8_t *err)
{
	if (state != NULL) {
		*state = ota_state;
	}
	if (err != NULL) {
		*err = ota_err;
	}
}
