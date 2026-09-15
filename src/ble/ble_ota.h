/**
 * @file ble_ota.h
 * @brief 自定义极简 BLE OTA（Control / Data / Status 三特性）
 *
 * 设计取舍
 * --------
 * 为什么不直接用 mcumgr + ZCBOR：那要拖进 ZCBOR 编解码、mcumgr 工作队列和多个 netbuf，
 * 而本工程 **RAM 是最紧约束**（20,024 / 24,576 B）。这里只用一个
 * `CONFIG_IMG_BLOCK_BUF_SIZE`（256 B）的写入缓冲 + 一个 flash_img 上下文，
 * 其余都是按需链接（实测：只开 IMG_MANAGER 不写调用代码时，链接器把库全回收，
 * App bin 与不加时完全相同）。
 *
 * 安全性边界
 * ----------
 * - 次级槽写入**走分区**（`FIXED_PARTITION_ID(mcuboot_secondary)`），
 *   不是硬编码地址 —— 越界在分区层就被拒绝，结构上不可能碰到 0x28000 之后的 NVS 与历史。
 * - 完整性/合法性最终由 **MCUboot 的 ECDSA-P256 验签**兜底：验不过就不搬运，
 *   设备继续跑旧固件（overwrite-only 下不会变砖）。
 * - `OTA_AUTH_KEY` 只防"没有密钥的手机反复擦写次级槽"，**不防逆向**。
 */

#ifndef BLE_BLE_OTA_H_
#define BLE_BLE_OTA_H_

#include <zephyr/kernel.h>
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- UUID：1234005x 段（现有已用到 12340041）---- */
#define BT_UUID_OTA_SERVICE_VAL \
	BT_UUID_128_ENCODE(0x12340050, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_OTA_CONTROL_VAL \
	BT_UUID_128_ENCODE(0x12340051, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_OTA_DATA_VAL \
	BT_UUID_128_ENCODE(0x12340052, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)
#define BT_UUID_OTA_STATUS_VAL \
	BT_UUID_128_ENCODE(0x12340053, 0x1234, 0x5678, 0x1234, 0x56789ABCDEF0)

/* ---- 授权密钥（16 字节；必须与手机端一致）----
 * ⚠️ 密钥是**凭据**，不入库：真实值放在同目录的 `ota_auth_key.h`
 *    （由 `ota_auth_key.h.example` 复制而来，已被 .gitignore 排除）。
 *    缺少该文件时**编译期报错**，不会静默退回弱默认值。
 *    它防的是"附近任意手机反复擦次级槽"这种拒绝服务，不是防攻击——固件里能读出来，
 *    真正的安全边界是 MCUboot 的 ECDSA-P256 验签。
 *    量产必须换成每型号/每批次独立的值，并与同批次 App 配置一致。 */
#if defined(__has_include)
#  if __has_include("ota_auth_key.h")
#    include "ota_auth_key.h"
#  endif
#endif

#ifndef OTA_AUTH_KEY
#  error "missing src/ble/ota_auth_key.h: copy ota_auth_key.h.example to ota_auth_key.h and fill in the 16-byte OTA auth key (credentials are not committed)"
#endif
#define OTA_AUTH_KEY_LEN 16U

/* ---- Control 操作码 ---- */
#define OTA_CTRL_START   0x01U  /* 开始：key(16) + total(4) + ih_ver(8) + sha256(32) = 61 */
#define OTA_CTRL_END     0x02U  /* 结束并校验 */
#define OTA_CTRL_CANCEL  0x03U  /* 取消 */
#define OTA_CTRL_TRIGGER 0x04U  /* 触发升级并重启 */

/* START 报文总长；1(op) + 16(key) + 4(total) + 8(ih_ver) + 32(sha256) */
#define OTA_START_LEN    61U

/* 手机端对**整个 zephyr.signed.bin** 算的 SHA-256：
 * - 全 32 字节用于 END 时的完整性比对（flash_area_check_int_sha256 比对完整 32 字节）；
 * - 前 16 字节作为 **image ID** 存进 NVS 续传状态。
 *
 * 为什么不能只靠 (total_size, ih_ver) 判定能否续传：
 * 两个**同版本、同大小但内容不同**的镜像（比如改了一行代码却忘了抬版本，
 * 或同版本号被重复用于两个二进制）会被错误拼接，得到一个"大小对、版本对、
 * 但内容是两个镜像各一半"的怪物镜像。绑定 SHA-256 前缀才能排除这种情况。 */
#define OTA_SHA256_LEN   32U
#define OTA_IMG_ID_LEN   16U

/* ---- 状态机 ---- */
enum ota_state {
	OTA_STATE_IDLE     = 0,  /* 空闲 */
	OTA_STATE_READY    = 1,  /* 已擦除、已定位，等待数据 */
	OTA_STATE_RECEIVING= 2,  /* 接收中 */
	OTA_STATE_VERIFY   = 3,  /* 校验中 */
	OTA_STATE_PENDING  = 4,  /* 校验通过、已请求升级 */
	OTA_STATE_ERROR    = 5,
};

/* ---- 错误码 ---- */
enum ota_err {
	OTA_ERR_NONE       = 0,
	OTA_ERR_AUTH       = 1,  /* 密钥不对 */
	OTA_ERR_STATE      = 2,  /* 状态机不允许该操作 */
	OTA_ERR_TOO_LARGE  = 3,  /* 超过次级槽容量 */
	OTA_ERR_FLASH      = 4,  /* 擦/写失败 */
	OTA_ERR_SIZE       = 5,  /* 接收字节数与声明不符 */
	OTA_ERR_MAGIC      = 6,  /* 镜像 magic 不对 */
	OTA_ERR_VERSION    = 7,  /* 镜像版本与声明不符（或版本格式非法） */
	OTA_ERR_HASH       = 8,  /* App 侧 SHA-256 校验失败（仅 IMG_ENABLE_IMAGE_CHECK 时启用） */
	OTA_ERR_LEN        = 9,  /* Control 报文长度非法 */
	OTA_ERR_INTERNAL   = 10,
	OTA_ERR_OVERRUN    = 11, /* 接收环形缓冲被打满：保护性中止，需重新 START（可续传） */
};

/* Status 通知长度：[offset(4)][total(4)][state(1)][err(1)][progress(1)][flags(1)] */
#define OTA_STATUS_LEN 12U

/**
 * @brief 初始化 OTA 服务（GATT 静态定义，无需注册；这里只做状态复位）
 */
void ble_ota_init(void);

/**
 * @brief OTA 是否正在进行
 *
 * main.c 用它**暂停历史落盘与 NVS 写入**。这不是性能优化而是正确性要求：
 * 历史/NVS 与 OTA 共用同一颗 W25Q64、同一条 SPI，并发访问会互相破坏，
 * 而且 SPI 忙会造成 OTA 写入的延迟抖动。
 */
bool ble_ota_in_progress(void);

/**
 * @brief 当前 OTA 状态与错误码（diagnostic）
 */
void ble_ota_get_state(uint8_t *state, uint8_t *err);

#ifdef __cplusplus
}
#endif

#endif /* BLE_BLE_OTA_H_ */
