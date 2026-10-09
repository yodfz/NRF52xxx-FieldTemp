package com.example.pandatemperature.data.device.parser

/**
 * 数据解析器接口
 * 泛型 T 为解析后的数据类型
 */
interface DataParser<T> {
    /**
     * 解析原始字节数据
     * @param data 原始字节数组
     * @return 解析后的数据，解析失败返回 null
     */
    fun parse(data: ByteArray): T?
    
    /**
     * 检查数据是否可以被此解析器解析
     * @param data 原始字节数组
     * @return 如果数据格式匹配返回 true
     */
    fun canParse(data: ByteArray): Boolean
    
    /**
     * 获取此解析器期望的最小数据长度
     */
    val expectedMinLength: Int
}

/**
 * 解析结果封装类
 */
sealed class ParseResult<out T> {
    /** 解析成功 */
    data class Success<T>(val data: T) : ParseResult<T>()
    
    /** 解析失败 */
    data class Error(val message: String, val cause: Throwable? = null) : ParseResult<Nothing>()
    
    /** 数据为空或长度不足 */
    data object InsufficientData : ParseResult<Nothing>()
    
    /** 判断是否成功 */
    val isSuccess: Boolean get() = this is Success
    
    /** 获取数据（如果成功） */
    fun getOrNull(): T? = (this as? Success)?.data
}
