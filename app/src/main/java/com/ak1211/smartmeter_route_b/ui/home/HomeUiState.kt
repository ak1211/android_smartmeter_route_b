package com.ak1211.smartmeter_route_b.ui.home

import arrow.core.Option


data class HomeUiState(
    val snackbarMessage: Option<String>,
    val usbDeviceName: String?,
    val isConnectedPana: Boolean,
    val incomingData: String
)