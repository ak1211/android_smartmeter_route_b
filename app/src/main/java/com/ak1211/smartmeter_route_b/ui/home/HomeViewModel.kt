package com.ak1211.smartmeter_route_b.ui.home

import android.content.Context.USB_SERVICE
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
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
import arrow.core.raise.either
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
import java.time.LocalDateTime

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
        MutableStateFlow(HomeUiState(false, None, null, false, "", None))
    val uiStateFlow: StateFlow<HomeUiState> get() = uiStateMutableStateFlow.asStateFlow()

    val isConnectedPanaSession get() = smartWhmRouteBRepository.nowOnPanaSessionStateFlow

    fun toggleUiSteateFabOpenOrClose() {
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(isFloatingActionButtonOpend = uiStateFlow.value.isFloatingActionButtonOpend.not()) }
    }

    fun updateUiSteateSnackbarMessage(value: Option<String>) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(snackbarMessage = value) }

    fun updateUiSteateUsbDeviceName(value: String) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(usbDeviceName = value) }

    fun updateUiSteateIsConnectedPana(value: Boolean) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(isConnectedPana = value) }

    fun updateUiSteateIncomingData(value: String) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(incomingData = value) }

    fun updateUiStateInstantWatt(value: Option<Pair<LocalDateTime, Int>>) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(instantWatt = value) }

    fun sendCommand(command: String): Either<Throwable, Unit> =
        when (isConnectedPanaSession.value) {
            true -> smartWhmRouteBRepository.write((command + "\r\n").toByteArray())
            false -> Either.Left(RuntimeException("ポートが閉じています"))
        }

    fun sendSksendto(payload: ByteArray): Either<Throwable, Unit> {
        val p = appPreferencesRepository.appPreferences.value
        val eitherIpV6LinkAddress = p.whmIpv6LinkLocalAddress
            .toEither { IllegalStateException("アクティブスキャンを行ってください") }
        val eitherCommand = eitherIpV6LinkAddress.map { ipV6LinkAddress ->
            String.format("SKSENDTO 1 %s 0E1A 1 %04X ", ipV6LinkAddress, payload.size)
        }
        return eitherCommand.flatMap { cmd ->
            when (isConnectedPanaSession.value) {
                true -> smartWhmRouteBRepository.write(cmd.toByteArray() + payload)
                false -> Either.Left(RuntimeException("ポートが閉じています"))
            }
        }
    }

    // 受信チャンネル読み込みループ
    @RequiresApi(Build.VERSION_CODES.O)
    private fun launchReadingLoop(channel: ReceiveChannel<Incoming>): Job =
        viewModelScope.launch(defaultDispatcher) {
            channel.consumeEach {
                it.fold(
                    { ex -> updateUiSteateSnackbarMessage(Some(ex.toString())) },
                    { bytes ->
                        updateUiSteateIncomingData(uiStateFlow.value.incomingData + bytes.decodeToString())
                        if (bytes.take(6).toByteArray().contentEquals("ERXUDP".toByteArray())) {
                            Log.v(TAG, bytes.decodeToString())
                            val token = bytes.decodeToString().split(" ")
                            if (token.size == 9) {
                                val sender: String = token[1]   // 送信元IPv6アドレス
                                val dest: String = token[2]     // 送信先IPv6アドレス
                                val rport: String = token[3]    // 送信元ポート番号
                                val lport: String = token[4]    // 送信元ポート番号
                                val senderlla: String = token[5]// 送信元のMACアドレス
                                val secured: String =
                                    token[6]  // MACフレームが暗号化されていた または MACフレームが暗号化されていなかった
                                val datalen: Int? = token[7].toIntOrNull(16)  // データの長さ
                                val payload: String = token[8]  // データ
                                //
                                Log.v(TAG, "datalen: $datalen")
                                Log.v(TAG, "payload: $payload")
                                val xs = payload.chunked(2).map {
                                    Log.v(TAG, "it: $it")
                                    it.toIntOrNull(16)
                                }.filterNotNull()
                                if (xs.size >= 18) {
                                    val opc = xs[11]
                                    val epc = xs[12]
                                    Log.v(TAG, "EPC: $epc")
                                    if (epc == 0xE7) {
                                        // 瞬時電力測定値
                                        val pdc = xs[13]
                                        val edt =
                                            (xs[14] and 0xFF) shl 24 or
                                                    (xs[15] and 0xFF) shl 16 or
                                                    (xs[16] and 0xFF) shl 8 or
                                                    (xs[17] and 0xFF)
                                        //
                                        Log.v(TAG, "EDT: $edt Watt")
                                        //
                                        val now = LocalDateTime.now()
                                        updateUiStateInstantWatt(Some(Pair(now, edt)))
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }


    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun buttonOpenOnClick(): Either<Throwable, ReceiveChannel<Incoming>> = either {
        when (isConnectedPanaSession.value) {
            true -> Either.Left(RuntimeException("すでに開かれています"))
            false -> Either.Right(Unit)
        }.bind()

        val usbSerialDriver: UsbSerialDriver = getUsbSerialDeviceName()
            .flatMap { getSerialDriver(it) }
            .flatMap { getGrant(it) }
            .bind()

        // スマートメータとの間でPANAセッションを開始する
        val p = appPreferencesRepository.appPreferences.value
        val exception = RuntimeException("アクティブスキャンを行ってください")
        val routeBId = p.whmRouteBId
        val routeBPassword = p.whmRouteBPassword
        val panId = p.whmPanId.toEither { exception }.bind()
        val panChannel = p.whmPanChannel.toEither { exception }.bind()
        val ipV6LinkAddress = p.whmIpv6LinkLocalAddress.toEither { exception }.bind()
        //
        val receiveChannel =
            smartWhmRouteBRepository.startPanaSession(
                viewModelScope,
                usbSerialDriver,
                routeBId,
                routeBPassword,
                panId,
                panChannel,
                ipV6LinkAddress,
            ).bind()
        launchReadingLoop(receiveChannel)
        receiveChannel
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
