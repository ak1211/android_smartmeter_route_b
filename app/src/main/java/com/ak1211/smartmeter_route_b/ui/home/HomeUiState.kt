package com.ak1211.smartmeter_route_b.ui.home

import arrow.core.Option
import java.time.LocalDateTime


data class HomeUiState(
    val isFloatingActionButtonOpend: Boolean,
    val snackbarMessage: Option<String>,
    val usbDeviceName: String?,
    val isConnectedPana: Boolean,
    val incomingData: String,
    val instantWatt: Option<Pair<LocalDateTime, Int>>
)