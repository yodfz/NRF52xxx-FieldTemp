/**
 * @file vdd.h
 * @brief 电池电压采样（SAADC 内部 VDD 通道，无需任何外部电路）
 *
 * 硬件事实：本板**没有**电压采样/分压电路。nRF52810 的 SAADC 可直接测 VDD，
 * 因此零硬件即可拿到电池电压 —— 前提是 VCC 直接由电池供电（中间无 LDO/稳压）。
 *
 * 线程/上下文约定（**重要**）：
 *   - 采样只允许在 `vdd_periodic_tick()` → 系统工作队列（1 Hz 采集路径）里做；
 *   - BLE 侧**只能**调 `vdd_cached_mv()` 读缓存，
 *     **禁止**在 GATT 回调里调用 `adc_read()`（会把 ADC 与校准时间塞进 BT RX 上下文）。
 *
 * 编译开关：CONFIG_ADC（见 prj.conf）。关闭时本模块为空，对镜像零影响。
 */

#ifndef VDD_H_
#define VDD_H_

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 电压无效哨兵值 = 0xFFFF（65535 mV）。
 *
 * App **必须**把它显示为 `--`，绝不能显示成 65.535 V。
 * 选 0xFFFF 而不是 0：0 mV 是个"看起来合法"的值，会被渲染成 0.000 V，
 * 等于把"没有数据"伪装成"电池耗尽的读数"。
 */
#define VDD_INVALID_MV        0xFFFFU

/** 采样周期（秒）。有效范围 60~300，默认 60（见 vdd.c 的编译期钳位）。 */
#define VDD_SAMPLE_PERIOD_S   60

#ifdef CONFIG_ADC

/** 缓存值：BLE 只读它。未采过样或连续失败时等于 VDD_INVALID_MV。 */
extern uint16_t g_vdd_mv;

/** 诊断用：观察到的电压区间。可反映电池内阻在 BLE 发射突发下的跌落。 */
extern uint16_t g_vdd_min_mv;
extern uint16_t g_vdd_max_mv;

/**
 * @brief 立即采一次并更新缓存/区间。
 * @param mv 非空时回填本次读数；可为 NULL。
 * @return 0 成功；负值为 errno。
 */
int vdd_sample_mv(uint16_t *mv);

/** @brief 更新缓存 + 区间统计（采样失败时按连续失败计数决定是否置无效）。 */
void vdd_refresh(void);

/** @brief 由 1 Hz 采集路径每拍调用一次；内部按 VDD_SAMPLE_PERIOD_S 节流。 */
void vdd_periodic_tick(void);

/** @brief 读缓存（绝不触发 ADC）。BLE 回调只允许用这个。 */
uint16_t vdd_cached_mv(void);

#endif /* CONFIG_ADC */

#ifdef __cplusplus
}
#endif

#endif /* VDD_H_ */
