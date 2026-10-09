#ifndef HISTORY_CODEC_H
#define HISTORY_CODEC_H
#include <stdbool.h>
#include <stdint.h>
#define HISTORY_PRESSURE_MASK 0x0003ffffU
#define HISTORY_TAG_MASK      0xc0000000U
#define HISTORY_COMMITTED     0x80000000U
#define HISTORY_STAGED        0xc0000000U
#define HISTORY_INVALID_MV    0xffffU
static inline uint32_t history_pack_pressure(uint32_t pa, uint16_t mv)
{
    if (pa < 10000U || pa > 200000U) return HISTORY_STAGED;
    uint32_t voltage = mv < 4095U ? mv : 4095U;
    return HISTORY_COMMITTED | (voltage << 18) | pa;
}
static inline bool history_decode_pressure(uint32_t stored, uint32_t *pa, uint16_t *mv)
{
    uint32_t tag = stored & HISTORY_TAG_MASK;
    if (tag == 0U) {
        *pa = stored;
        *mv = HISTORY_INVALID_MV;
    } else if (tag == HISTORY_COMMITTED) {
        *pa = stored & HISTORY_PRESSURE_MASK;
        uint16_t voltage = (stored >> 18) & 0xfffU;
        *mv = voltage == 4095U ? HISTORY_INVALID_MV : voltage;
    } else {
        return false;
    }
    return *pa >= 10000U && *pa <= 200000U;
}
static inline bool history_bytes_erased(const void *record, unsigned length)
{
    const uint8_t *p = record;
    for (unsigned i = 0; i < length; ++i) {
        if (p[i] != 0xffU) return false;
    }
    return true;
}
#endif
