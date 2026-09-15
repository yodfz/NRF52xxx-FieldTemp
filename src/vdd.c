/**
 * @file vdd.c
 * @brief 电池电压采样实现（仅 CONFIG_ADC=y 时产生代码）
 *
 * 换算为何不依赖 adc_raw_to_millivolts_dt()：
 *   该函数对 gain 的处理在不同 Zephyr 版本间语义有差异，容易把 1/6 增益算错一倍。
 *   这里按数据手册直接算，链路完全透明：
 *     VDD --[增益 1/6]--> SAADC --[内部基准 0.6 V, 12 bit]--> raw
 *     raw = (VDD / 6) / 0.6 * 4096   =>   VDD_mV = raw * 3600 / 4096   （量程 3.6 V）
 *
 * 实测校验：调试器供 3.3 V 时读数 3325 mV（raw 3784），与标称一致，
 * 说明基准/增益比例正确（若比例错 6 倍会读到 ~20 V 或 ~0.55 V）。
 *
 * 功耗：Zephyr 的 nrfx SAADC 驱动在每次转换 DONE 后调用 nrfy_saadc_disable()，
 *   即**空闲时外设关闭**、不产生静态电流，只有采样那几毫秒耗电 —— 对 CR2032 友好。
 *   因此不需要额外的电源管理代码。
 */

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/adc.h>

#include "vdd.h"

#ifdef CONFIG_ADC

/* ---- 编译期钳位：周期必须落在 60~300 秒 ---- */
#if VDD_SAMPLE_PERIOD_S < 60
#undef VDD_SAMPLE_PERIOD_S
#define VDD_SAMPLE_PERIOD_S 60
#elif VDD_SAMPLE_PERIOD_S > 300
#undef VDD_SAMPLE_PERIOD_S
#define VDD_SAMPLE_PERIOD_S 300
#endif

#define VDD_GAIN_NUMERATOR   3600   /* 0.6 V 基准 × 6（1/6 增益）= 3.6 V 满量程 */
#define VDD_RAW_MAX          4096   /* 12 bit */
#define VDD_MV_CEILING       0xFFFEU /* 上限；**不得**等于 VDD_INVALID_MV 以免语义混淆 */
#define VDD_FAIL_LIMIT       3U     /* 连续失败这么多次才判定"无数据" */

static const struct adc_dt_spec vdd_spec =
	ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), 0);

static int16_t  vdd_raw_buf;         /* EasyDMA 采样缓冲，必须在 RAM */
static bool     vdd_channel_ready;
static uint8_t  vdd_fail_count;

uint16_t g_vdd_mv     = VDD_INVALID_MV;
uint16_t g_vdd_min_mv = VDD_INVALID_MV;
uint16_t g_vdd_max_mv;

int vdd_sample_mv(uint16_t *mv)
{
	struct adc_sequence seq = {
		.buffer      = &vdd_raw_buf,
		.buffer_size = sizeof(vdd_raw_buf),
	};
	int rc;
	int32_t raw;
	uint32_t mv32;

	if (!device_is_ready(vdd_spec.dev)) {
		return -ENODEV;
	}

	/* 通道只配置一次：SAADC 是独占外设，本项目没有别的使用者。
	 * 读取失败时清标志，下次重新下发配置，避免一次失败后永久失效。 */
	if (!vdd_channel_ready) {
		rc = adc_channel_setup_dt(&vdd_spec);
		if (rc != 0) {
			return rc;
		}
		vdd_channel_ready = true;
	}

	rc = adc_sequence_init_dt(&vdd_spec, &seq);
	if (rc != 0) {
		return rc;
	}

	rc = adc_read(vdd_spec.dev, &seq);
	if (rc != 0) {
		vdd_channel_ready = false;
		return rc;
	}

	raw = (int32_t)vdd_raw_buf;
	if (raw < 0) {
		raw = 0;
	}

	/* 用 32 位中间量，避免 16 位溢出（raw 最大 4095 → 3599 mV） */
	mv32 = (uint32_t)raw * VDD_GAIN_NUMERATOR / VDD_RAW_MAX;
	if (mv32 > VDD_MV_CEILING) {
		mv32 = VDD_MV_CEILING;
	}

	if (mv != NULL) {
		*mv = (uint16_t)mv32;
	}
	return 0;
}

void vdd_refresh(void)
{
	uint16_t mv = 0U;

	if (vdd_sample_mv(&mv) == 0) {
		vdd_fail_count = 0U;
		g_vdd_mv = mv;

		if (g_vdd_min_mv == VDD_INVALID_MV || mv < g_vdd_min_mv) {
			g_vdd_min_mv = mv;
		}
		if (mv > g_vdd_max_mv) {
			g_vdd_max_mv = mv;
		}
		return;
	}

	/* 单次失败不立刻置无效：那会让界面闪成 `--`。
	 * 连续失败到阈值才判定"确实没有数据"，此时 App 显示 `--` 是正确语义。 */
	if (vdd_fail_count < 0xFFU) {
		vdd_fail_count++;
	}
	if (vdd_fail_count >= VDD_FAIL_LIMIT) {
		g_vdd_mv = VDD_INVALID_MV;
	}
}

void vdd_periodic_tick(void)
{
	static uint16_t ticks;

	if (++ticks < VDD_SAMPLE_PERIOD_S) {
		return;
	}
	ticks = 0U;
	vdd_refresh();
}

uint16_t vdd_cached_mv(void)
{
	return g_vdd_mv;
}

#endif /* CONFIG_ADC */
