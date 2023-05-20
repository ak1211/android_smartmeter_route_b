package com.ak1211.smartmeter_route_b.ui

import arrow.core.Option

data class UsbDevicesListItem(
    val deviceName: String,
    val productName: Option<String>
)
