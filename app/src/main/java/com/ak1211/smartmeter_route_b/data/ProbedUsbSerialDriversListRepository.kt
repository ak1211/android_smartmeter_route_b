package com.ak1211.smartmeter_route_b.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED
import android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.LifecycleCoroutineScope
import arrow.core.Option
import arrow.core.firstOrNone
import arrow.core.toOption
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProbedUsbSerialDriversListRepository(private val context: Context) {
    private val TAG = "ProbedUsbSerialDriversListRepository"
    private val usbManager = context.getSystemService(USB_SERVICE) as UsbManager

    // 検出されたUSBシリアル通信ドライバーのリスト
    private val probedUsbSeialDriversListMutableStateFlow: MutableStateFlow<List<UsbSerialDriver>> =
        MutableStateFlow(listOf())
    val probedUsbSerialDriversList: StateFlow<List<UsbSerialDriver>>
        get() = probedUsbSeialDriversListMutableStateFlow.asStateFlow()

    // デバイス名で検出済みデバイスを探す
    fun findUsbSerialDriverByDeviceName(deviceName: String): Flow<Option<UsbSerialDriver>> =
        probedUsbSerialDriversList.map { list: List<UsbSerialDriver> ->
            list.filter { it.device.deviceName == deviceName }.firstOrNone()
        }

    suspend fun findUsbSerialDriverByDeviceName2(deviceName: String): Option<UsbSerialDriver> =
        probedUsbSerialDriversList.map { drivers: List<UsbSerialDriver> ->
            drivers.find { it.device.deviceName == deviceName }.toOption()
        }.first()

    // USBシリアル変換器をUSBバスから検出する
    suspend fun probeAllUsbSerialDrivers(): Deferred<List<UsbSerialDriver>> =
        coroutineScope {
            async {
                val updateList: List<UsbSerialDriver> =
                    UsbSerialProber
                        .getDefaultProber()
                        .findAllDrivers(usbManager)
                        .sortedBy { it.device.deviceName }
                probedUsbSeialDriversListMutableStateFlow.update { updateList }
                updateList
            }
        }

    fun probeAllUsbSerialDrivers2() =
        flow<List<UsbSerialDriver>> {
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
                .sortedBy { it.device.deviceName }
                .let { updateList ->
                    probedUsbSeialDriversListMutableStateFlow.update { updateList }
                    emit(updateList)
                }
        }

    //
    private var usbBroadcastReceiver: MyUsbBroadcastReceiver? = null

    fun registerReceiver(lifecycleCoroutineScope: LifecycleCoroutineScope) {
        unregisterReceiver()
        usbBroadcastReceiver = MyUsbBroadcastReceiver(lifecycleCoroutineScope)
        context.apply {
            registerReceiver(usbBroadcastReceiver, IntentFilter(ACTION_USB_DEVICE_ATTACHED))
            registerReceiver(usbBroadcastReceiver, IntentFilter(ACTION_USB_DEVICE_DETACHED))
        }
    }

    fun unregisterReceiver() {
        usbBroadcastReceiver?.let { context.unregisterReceiver(it) }
        usbBroadcastReceiver = null
    }

    /**
     *
     */
    inner class MyUsbBroadcastReceiver(private val lifecycleCoroutineScope: LifecycleCoroutineScope) :
        BroadcastReceiver() {
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun onReceive(context: Context, intent: Intent) {
            when {
                intent.action == ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        Log.v(TAG, "attached for device $device")
                    }
                    // バックグラウンドでUSBシリアルドライバーを検出する
                    lifecycleCoroutineScope.launch(Dispatchers.IO) {
                        probeAllUsbSerialDrivers().await()
                    }
                }

                intent.action == ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let {
                        Log.v(TAG, "detached for device $device")
                    }
                    // バックグラウンドでUSBシリアルドライバーを検出する
                    lifecycleCoroutineScope.launch(Dispatchers.IO) {
                        probeAllUsbSerialDrivers().await()
                    }
                }

                else -> {
                    Log.v(TAG, "intent \"${intent.action}\" is ignored")
                }
            }
        }
    }
}
