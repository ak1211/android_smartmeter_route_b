package com.ak1211.smartmeter_route_b.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import arrow.core.None
import arrow.core.Option
import arrow.core.raise.option
import arrow.core.toOption
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class AppPreferencesRepository(private val dataStore: DataStore<Preferences>) {
    val WHM_ROUTEB_ID_KEY = stringPreferencesKey("whm_route_b_id")
    val WHM_ROUTEB_PASSWORD_KEY = stringPreferencesKey("whm_route_b_password")
    val USB_SERIAL_DEVICE_NAME_KEY = stringPreferencesKey("usb_serial_device_name")
    val WHM_PAN_CHANNEL_KEY = stringPreferencesKey("whm_pan_channel")
    val WHM_PAN_ID_KEY = stringPreferencesKey("whm_pan_id")
    val WHM_IPV6_LINK_LOCAL_ADDRESS_KEY = stringPreferencesKey("whm_ipv6_link_local_address")

    // 設定情報
    private val appPreferencesMutableStateFlow: MutableStateFlow<AppPreferences> =
        MutableStateFlow(
            AppPreferences(
                AppPreferences.RouteBId(""),
                AppPreferences.RouteBPassword(""),
                None,
                None,
                None,
                None
            )
        )
    val appPreferences: StateFlow<AppPreferences> get() = appPreferencesMutableStateFlow.asStateFlow()

    fun updateAppPreferences(appPreferences: AppPreferences) =
        appPreferencesMutableStateFlow.update { appPreferences }

    // 設定情報を読み込んでリポジトリに回復する
    suspend fun restoreAppPreferences(): Deferred<Unit> =
        coroutineScope {
            async {
                loadAppPreferences()
                    .first()
                    .onSome { updateAppPreferences(it) }
                Unit
            }
        }

    fun restoreAppPreferences2(): Flow<Unit> =
        loadAppPreferences().map { optional ->
            optional.map { updateAppPreferences(it) }
        }

    suspend fun saveAppPreferences(appPreferences: AppPreferences) {
        dataStore.edit { p ->
            p.clear()
            p[WHM_ROUTEB_ID_KEY] = appPreferences.whmRouteBId.value
            p[WHM_ROUTEB_PASSWORD_KEY] = appPreferences.whmRouteBPassword.value
            appPreferences.useUsbSerialDeviceName.map { p[USB_SERIAL_DEVICE_NAME_KEY] = it }
            appPreferences.whmPanChannel.map { p[WHM_PAN_CHANNEL_KEY] = it.value }
            appPreferences.whmPanId.map { p[WHM_PAN_ID_KEY] = it.value }
            appPreferences.whmIpv6LinkLocalAddress.map {
                p[WHM_IPV6_LINK_LOCAL_ADDRESS_KEY] = it
            }
        }
    }

    fun loadAppPreferences(): Flow<Option<AppPreferences>> =
        dataStore.data.map {
            option {
                val whmRouteBId: String = it[WHM_ROUTEB_ID_KEY].toOption().bind()
                val whmRouteBPassword: String = it[WHM_ROUTEB_PASSWORD_KEY].toOption().bind()
                val usbSerialDeviceName: Option<String> = it[USB_SERIAL_DEVICE_NAME_KEY].toOption()
                val whmPanChannel: Option<String> = it[WHM_PAN_CHANNEL_KEY].toOption()
                val whmPanId: Option<String> = it[WHM_PAN_ID_KEY].toOption()
                val whmIpv6LinkLocalAddress: Option<String> =
                    it[WHM_IPV6_LINK_LOCAL_ADDRESS_KEY].toOption()

                AppPreferences(
                    AppPreferences.RouteBId(whmRouteBId),
                    AppPreferences.RouteBPassword(whmRouteBPassword),
                    usbSerialDeviceName,
                    whmPanChannel.map { AppPreferences.PanChannel(it) },
                    whmPanId.map { AppPreferences.PanId(it) },
                    whmIpv6LinkLocalAddress
                )
            }
        }
}
