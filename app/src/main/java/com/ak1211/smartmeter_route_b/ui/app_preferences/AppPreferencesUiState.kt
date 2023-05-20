package com.ak1211.smartmeter_route_b.ui.app_preferences

import arrow.core.Option
import com.ak1211.smartmeter_route_b.data.AppPreferences
import com.hoho.android.usbserial.driver.UsbSerialDriver

data class AppPreferencesUiState(
    val snackbarMessage: Option<String>,
    val progress: Option<Int>,
    val appPref : AppPreferences,
    val probedUsbSerialDriversList: List<UsbSerialDriver>  // USBデバイスリスト
)
