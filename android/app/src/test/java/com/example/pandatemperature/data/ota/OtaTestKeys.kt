package com.example.pandatemperature.data.ota

/**
 * 单元测试用的样本授权密钥（16 个可见 ASCII 字符）。
 *
 * 刻意**不复用** `OtaConstants.DEFAULT_AUTH_KEY`：
 * 后者由构建期从 `ota.properties` 注入，在未配置该文件的环境（CI / 新克隆）
 * 里是空数组，会让测试结果依赖本机配置；且产品密钥属凭据，测试不应与之耦合。
 */
internal val SAMPLE_AUTH_KEY: ByteArray = "SampleKey16Bytes".toByteArray(Charsets.US_ASCII)

/** 样本密钥的十六进制写法，用于验证两种文本形式解析结果一致。 */
internal val SAMPLE_AUTH_KEY_HEX: String =
    SAMPLE_AUTH_KEY.joinToString("") { "%02X".format(it) }
