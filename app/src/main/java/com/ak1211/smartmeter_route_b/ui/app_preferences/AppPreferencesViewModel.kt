package com.ak1211.smartmeter_route_b.ui.app_preferences

import android.content.Context
import android.content.Context.USB_SERVICE
import android.hardware.usb.UsbManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.flatMap
import com.ak1211.smartmeter_route_b.MyApplication
import com.ak1211.smartmeter_route_b.UsbPermissionGrant
import com.ak1211.smartmeter_route_b.data.AppPreferences
import com.ak1211.smartmeter_route_b.skstack.RouteBId
import com.ak1211.smartmeter_route_b.skstack.RouteBPassword
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 *
 */
class AppPreferencesViewModel(
    myApplication: MyApplication,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val appPreferencesRepository = myApplication.appPreferencesRepository
    private val probedUsbSerialDriversListRepository =
        myApplication.probedUsbSerialDriversListRepository
    private val smartWhmRouteBRepository = myApplication.smartWhmRouteBRepository

    fun getAppPreferences(): StateFlow<AppPreferences> = appPreferencesRepository.appPreferences

    // 検出されているUSBシリアル変換器
    fun getProbedUsbSerialDriversList(): StateFlow<List<UsbSerialDriver>> =
        probedUsbSerialDriversListRepository.probedUsbSerialDriversList

    suspend fun probeAllUsbSerialDrivers(): Deferred<List<UsbSerialDriver>> =
        probedUsbSerialDriversListRepository.probeAllUsbSerialDrivers()

    // UI状態
    val uiStateMutableStateFlow: MutableStateFlow<AppPreferencesUiState> =
        MutableStateFlow(
            AppPreferencesUiState(
                None,
                None,
                appPreferencesRepository.appPreferences.value,
                listOf()
            )
        )

    val uiStateFlow: StateFlow<AppPreferencesUiState> get() = uiStateMutableStateFlow.asStateFlow()

    fun updateUiState(value: AppPreferencesUiState) =
        uiStateMutableStateFlow.update { value }

    fun updateUiStateAppPreferences(value: AppPreferences) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(appPref = value) }

    fun updateUiSteateSnackbarMessage(value: Option<String>) =
        uiStateMutableStateFlow.update { uiStateFlow.value.copy(snackbarMessage = value) }

    // ルートＢの設定情報は長いので4文字毎に空白を入れて見やすくする
    fun whmRouteBSettingsWatcher(editText: EditText, updater: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // nothing to do
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 削除の場合は何もしない
                if (before > 0) {
                    return
                }
                if (s != null && s.isNotEmpty()) {
                    // 複数行の場合は考慮していない
                    val original = s.lines().joinToString().filterNot { it.isWhitespace() }
                    // ついでに小文字を大文字にする
                    val capitalized = original.uppercase()
                    // ユーザーに見せるIDは4文字ごとに空白を入れる
                    val fourDigitsSeparated = capitalized.chunked(4).joinToString(" ")
                    // 自身の変更で無限ループに陥るので変更前にリスナから削除する
                    editText.removeTextChangedListener(this)
                    // 変更
                    editText.setTextKeepState(fourDigitsSeparated)
                    // カーソルを移動する
                    val diff = fourDigitsSeparated.length - original.length
                    val st = editText.selectionStart
                    val en = editText.selectionEnd
                    editText.setSelection(
                        clamp(st + diff, 0, editText.length()),
                        clamp(en + diff, 0, editText.length())
                    )
                    // 変更後にリスナに再登録する
                    editText.addTextChangedListener(this)
                }
            }

            override fun afterTextChanged(s: Editable?) {
                //
                // 4文字ごとに空白が入っている文字をユーザーに見せているが
                // リポジトリに設定するIDからは空白を除去する
                //
                val text = (s ?: "").lines().joinToString()
                updater(text.filterNot { it.isWhitespace() })
            }
        }

    // これはlayout.xmlに設定している
    fun showWhmRouteBIdToLayout(): String =
        uiStateMutableStateFlow.value.appPref.whmRouteBId.value

    //
    fun updateWhmRouteBId(value: String) {
        val updateAppPreferences: (AppPreferences) -> AppPreferences = {
            it.copy(whmRouteBId = RouteBId(value))
        }
        uiStateMutableStateFlow.update { it.copy(appPref = updateAppPreferences(it.appPref)) }
    }

    // これはlayout.xmlに設定している
    fun afterTextChangedWhmRouteBPassword(s: Editable) =
        updateWhmRouteBPassword(s.lines().joinToString())

    // これはlayout.xmlに設定している
    fun showWhmRouteBPasswordToLayout(): String =
        uiStateMutableStateFlow.value.appPref.whmRouteBPassword.value

    //
    fun updateWhmRouteBPassword(value: String) {
        val updateAppPreferences: (AppPreferences) -> AppPreferences = {
            it.copy(whmRouteBPassword = RouteBPassword(value))
        }
        uiStateMutableStateFlow.update { it.copy(appPref = updateAppPreferences(it.appPref)) }
    }

    //
    fun updateUseUsbSerialDeviceName(value: Option<String>) {
        val updateAppPreferences: (AppPreferences) -> AppPreferences = {
            it.copy(useUsbSerialDeviceName = value)
        }
        uiStateMutableStateFlow.update { it.copy(appPref = updateAppPreferences(it.appPref)) }
    }

    fun updateProbedUsbSerialDriversList(value: List<UsbSerialDriver>) =
        uiStateMutableStateFlow.update { it.copy(probedUsbSerialDriversList = value) }

    fun updateProgress(value: Option<Int>) =
        uiStateMutableStateFlow.update { it.copy(progress = value) }

    // USBシリアルデバイス名を得る
    fun getUsbSerialDeviceName(appPreferences: AppPreferences): Either<Throwable, String> =
        appPreferences.useUsbSerialDeviceName
            .fold(
                { Either.Left(RuntimeException("設定からUSBシリアル変換器を選択してください")) },
                { Either.Right(it) }
            )

    suspend fun getSerialDriver(deviceName: String): Either<Throwable, UsbSerialDriver> {
        return probedUsbSerialDriversListRepository
            .findUsbSerialDriverByDeviceName(deviceName)
            .map {
                it.fold(
                    { Either.Left(RuntimeException("USBシリアル変換器が見つかりません")) },
                    { Either.Right(it) }
                )
            }.first()
    }

    suspend fun getGrant(
        context: Context,
        usbSerialDriver: UsbSerialDriver
    ): Either<Throwable, UsbSerialDriver> {
        val usbManager: UsbManager = context.getSystemService(USB_SERVICE) as UsbManager
        return if (usbManager.hasPermission(usbSerialDriver.device)) {
            // 権限があるならそのまま返す
            Either.Right(usbSerialDriver)
        } else {
            // 権限を要求するダイアログをだしてもらう
            UsbPermissionGrant.getPermission(context, usbSerialDriver.device)
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

    // アクティブスキャンボタンのハンドラ
    suspend fun handleOnClickActiveScanButton(context: Context): Deferred<Either<Throwable, String>> {
        val appPreferences = uiStateFlow.value.appPref

        return coroutineScope {
            async {
                //
                val usbSerialDriver = getUsbSerialDeviceName(appPreferences)
                    .flatMap { getSerialDriver(it) }
                    .flatMap { getGrant(context, it) }
                //
                updateProgress(Some(0))
                val result = usbSerialDriver.flatMap {
                    smartWhmRouteBRepository.doActiveScan(
                        appPreferences.whmRouteBId,
                        appPreferences.whmRouteBPassword,
                        it
                    ).await()
                }
                updateProgress(None)
                result.map { (epandesc, ipv6address) ->
                    // スキャン結果はそのままリポジトリに入れる
                    val newAppPreferences = appPreferences.copy(
                        whmPanChannel = Some(epandesc.channel),
                        whmPanId = Some(epandesc.panId),
                        whmIpv6LinkLocalAddress = Some(ipv6address)
                    )
                    appPreferencesRepository.updateAppPreferences(newAppPreferences)
                    "アクティブスキャンが完了しました"
                }
            }
        }
    }

    // アクティブスキャン消去ボタンのハンドラ
    fun handleOnClickEraseActiveScanButton() {
        val current = appPreferencesRepository.appPreferences.value
        appPreferencesRepository.updateAppPreferences(
            current.copy(
                whmPanChannel = None,
                whmPanId = None,
                whmIpv6LinkLocalAddress = None
            )
        )
    }

    // 登録ボタンのハンドラ
    fun handleOnClickRegisterButton(): Either<Throwable, Unit> {
        val appPreferences: AppPreferences = uiStateFlow.value.appPref
        //
        val satisfied =
            listOf(
                appPreferences.whmRouteBId.isNotBlank(),
                appPreferences.whmRouteBPassword.isNotBlank()
            ).all { it }
        return when {
            satisfied == true -> {
                appPreferencesRepository.updateAppPreferences(appPreferences)
                Either.Right(Unit)
            }

            appPreferences.whmRouteBId.isBlank() -> Either.Left(IllegalArgumentException("IDを入力してください"))
            appPreferences.whmRouteBPassword.isBlank() -> Either.Left(IllegalArgumentException("パスワードを入力してください"))
            else -> Either.Left(IllegalStateException("Error"))
        }
    }

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

                return AppPreferencesViewModel(
                    application as MyApplication,
                    savedStateHandle
                ) as T
            }
        }
    }
}
