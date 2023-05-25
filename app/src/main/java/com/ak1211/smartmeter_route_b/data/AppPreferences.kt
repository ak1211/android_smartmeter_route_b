package com.ak1211.smartmeter_route_b.data

import arrow.core.Option
import com.ak1211.smartmeter_route_b.skstack.IpV6LinkAddress
import com.ak1211.smartmeter_route_b.skstack.PanChannel
import com.ak1211.smartmeter_route_b.skstack.PanId
import com.ak1211.smartmeter_route_b.skstack.RouteBId
import com.ak1211.smartmeter_route_b.skstack.RouteBPassword

data class AppPreferences(
    val whmRouteBId: RouteBId,
    val whmRouteBPassword: RouteBPassword,
    val useUsbSerialDeviceName: Option<String>,
    val whmPanChannel: Option<PanChannel>,
    val whmPanId: Option<PanId>,
    val whmIpv6LinkLocalAddress: Option<IpV6LinkAddress>
)