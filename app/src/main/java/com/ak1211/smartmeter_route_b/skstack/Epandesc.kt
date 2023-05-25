package com.ak1211.smartmeter_route_b.skstack

data class Epandesc(
    val channel: PanChannel,
    val channelPage: String,
    val panId: PanId,
    val addr: String,
    val lqi: String,
    val pairId: String
)
