package com.ak1211.smartmeter_route_b.ui.home

import android.content.Context.USB_SERVICE
import android.hardware.usb.UsbManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.flatMap
import com.ak1211.smartmeter_route_b.MyApplication
import com.ak1211.smartmeter_route_b.UsbPermissionGrant
import com.ak1211.smartmeter_route_b.data.AppPreferences
import com.ak1211.smartmeter_route_b.data.AppPreferencesRepository
import com.ak1211.smartmeter_route_b.data.Incoming
import com.ak1211.smartmeter_route_b.data.ProbedUsbSerialDriversListRepository
import com.ak1211.smartmeter_route_b.data.SmartWhmRouteBRepository
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 *
 */
class HomeViewModel(
    private val myApplication: MyApplication,
    savedStateHandle: SavedStateHandle,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    private val TAG: String = "HomeViewModel"
    private val appPreferencesRepository: AppPreferencesRepository by lazy { myApplication.appPreferencesRepository }
    private val smartWhmRouteBRepository: SmartWhmRouteBRepository by lazy { myApplication.smartWhmRouteBRepository }
    private val probedUsbSerialDriversListRepository: ProbedUsbSerialDriversListRepository by lazy { myApplication.probedUsbSerialDriversListRepository }
    private val usbManager = myApplication.getSystemService(USB_SERVICE) as UsbManager

    val appPreferencesFlow: Flow<AppPreferences> get() = appPreferencesRepository.appPreferences

    val uiStateMutableStateFlow: MutableStateFlow<HomeUiState> =
        MutableStateFlow(HomeUiState(None, null, false, ""))
    val uiStateFlow: StateFlow<HomeUiState> get() = uiStateMutableStateFlow.asStateFlow()

    val isConnectedPana get() = smartWhmRouteBRepository.isConnectedPana

    fun updateUiSteateSnackbarMessage(value: Option<String>) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(snackbarMessage = value) }

    fun updateUiSteateUsbDeviceName(value: String) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(usbDeviceName = value) }

    fun updateUiSteateIsConnectedPana(value: Boolean) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(isConnectedPana = value) }

    fun updateUiSteateIncomingData(value: String) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(incomingData = value) }

    fun sendCommand(command: String): Either<Throwable, Unit> =
        when (isConnectedPana.value) {
            true -> smartWhmRouteBRepository.write((command + "\r\n").toByteArray())
            false -> Either.Left(RuntimeException("ポートが閉じています"))
        }

    // 受信チャンネル読み込みループ
    private fun launchReadingLoop(channel: ReceiveChannel<Incoming>): Job =
        viewModelScope.launch(defaultDispatcher) {
            channel.consumeEach {
                it.fold(
                    { ex -> updateUiSteateSnackbarMessage(Some(ex.toString())) },
                    {
                        updateUiSteateIncomingData(uiStateFlow.value.incomingData + it.decodeToString())
                    }
                )
            }
        }


    suspend fun buttonOpenOnClick(): Either<Throwable, ReceiveChannel<Incoming>> {
        //
        return when (isConnectedPana.value) {
            true -> Either.Left(RuntimeException("すでに開かれています"))
            false -> Either.Right(Unit)
        }.flatMap { getUsbSerialDeviceName() }
            .flatMap { getSerialDriver(it) }
            .flatMap { getGrant(it) }
            .flatMap { smartWhmRouteBRepository.startPanaSession(viewModelScope, it) }
            .flatMap { launchReadingLoop(it);Either.Right(it) }
    }


    //
    private suspend fun getUsbSerialDeviceName(): Either<Throwable, String> {
        return appPreferencesRepository
            .appPreferences
            .map { appPref ->
                appPref.useUsbSerialDeviceName.fold(
                    { Either.Left(RuntimeException("設定からUSBシリアル変換器を選択してください")) },
                    { Either.Right(it) }
                )
            }.first()
    }

    //
    private suspend fun getSerialDriver(deviceName: String): Either<Throwable, UsbSerialDriver> {
        return probedUsbSerialDriversListRepository
            .findUsbSerialDriverByDeviceName(deviceName)
            .map {
                it.fold(
                    { Either.Left(RuntimeException("USBシリアル変換器が見つかりません")) },
                    { Either.Right(it) }
                )
            }.first()
    }

    //
    private suspend fun getGrant(usbSerialDriver: UsbSerialDriver): Either<Throwable, UsbSerialDriver> {
        return if (usbManager.hasPermission(usbSerialDriver.device)) {
            // 権限があるならそのまま返す
            Either.Right(usbSerialDriver)
        } else {
            // 権限を要求するダイアログをだしてもらう
            UsbPermissionGrant.getPermission(myApplication, usbSerialDriver.device)
                .await()
                .let {
                    when (it) {
                        UsbPermissionGrant.UsbPermission.Granted ->
                            Either.Right(usbSerialDriver) // 権限を取得できた
                        UsbPermissionGrant.UsbPermission.Denied ->
                            Either.Left(RuntimeException("接続には権限が必要です")) // 権限を受け取れなかった
                    }
                }
        }
    }

    // 開くボタンのハンドラー
    suspend fun handleOnClickButtonOpen() {
        //
        val onSuccess: (ReceiveChannel<Incoming>) -> Unit = {
            updateUiSteateSnackbarMessage(Some("開きました"))
        }
        //
        val onFailure: (Throwable) -> Unit = { ex ->
            val msg: String = ex.message ?: ex.toString()
            updateUiSteateSnackbarMessage(Some(msg))
        }
        //
        buttonOpenOnClick().fold(onFailure, onSuccess)
    }

    // 閉じるボタンのハンドラー
    fun handleOnClickButtonClose() =
        smartWhmRouteBRepository.terminatePanaSession()

    // Define ViewModel factory in a companion object
    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                // Get the Application object from extras
                val application = checkNotNull(extras[APPLICATION_KEY])
                // Create a SavedStateHandle for this ViewModel from extras
                val savedStateHandle = extras.createSavedStateHandle()

                return HomeViewModel(
                    application as MyApplication,
                    savedStateHandle
                ) as T
            }
        }
    }
}
