package com.ak1211.smartmeter_route_b.data

import arrow.core.Option

data class AppPreferences(
    val whmRouteBId: RouteBId,
    val whmRouteBPassword: RouteBPassword,
    val useUsbSerialDeviceName: Option<String>,
    val whmPanChannel: Option<PanChannel>,
    val whmPanId: Option<PanId>,
    val whmIpv6LinkLocalAddress: Option<String>
) {
    @JvmInline
    value class RouteBId(val value: String) {
        fun isEmpty(): Boolean = value.isEmpty()
        fun isNotEmpty(): Boolean = value.isNotEmpty()
        fun isBlank(): Boolean = value.isBlank()
        fun isNotBlank(): Boolean = value.isNotBlank()
        override fun toString(): String = value.toString()
    }

    @JvmInline
    value class RouteBPassword(val value: String) {
        fun isEmpty(): Boolean = value.isEmpty()
        fun isNotEmpty(): Boolean = value.isNotEmpty()
        fun isBlank(): Boolean = value.isBlank()
        fun isNotBlank(): Boolean = value.isNotBlank()
        override fun toString(): String = value.toString()
    }

    @JvmInline
    value class PanChannel(val value: String) {
        fun isEmpty(): Boolean = value.isEmpty()
        fun isNotEmpty(): Boolean = value.isNotEmpty()
        fun isBlank(): Boolean = value.isBlank()
        fun isNotBlank(): Boolean = value.isNotBlank()
        override fun toString(): String = value.toString()
    }

    @JvmInline
    value class PanId(val value: String) {
        fun isEmpty(): Boolean = value.isEmpty()
        fun isNotEmpty(): Boolean = value.isNotEmpty()
        fun isBlank(): Boolean = value.isBlank()
        fun isNotBlank(): Boolean = value.isNotBlank()
        override fun toString(): String = value.toString()
    }
}