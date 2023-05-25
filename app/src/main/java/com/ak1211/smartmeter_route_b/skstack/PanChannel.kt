package com.ak1211.smartmeter_route_b.skstack

@JvmInline
value class PanChannel(val value: String) {
    fun isEmpty(): Boolean = value.isEmpty()
    fun isNotEmpty(): Boolean = value.isNotEmpty()
    fun isBlank(): Boolean = value.isBlank()
    fun isNotBlank(): Boolean = value.isNotBlank()
    override fun toString(): String = value.toString()
}