package com.ak1211.smartmeter_route_b

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.hoho.android.usbserial.BuildConfig
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

class UsbPermissionGrant {
    enum class UsbPermission(var extraDevice: UsbDevice? = null) { Denied, Granted }

    companion object {
        const val INTENT_ACTION_USB_PERMISSION =
            BuildConfig.LIBRARY_PACKAGE_NAME + ".USB_PERMISSION"

        // callbackをFlowにする
        private fun getPermissionFlow(context: Context, usbDevice: UsbDevice): Flow<UsbPermission> =
            callbackFlow {
                val receiver = object : BroadcastReceiver() {
                    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == INTENT_ACTION_USB_PERMISSION) {
                            synchronized(this) {
                                val granted = intent.getBooleanExtra(
                                    UsbManager.EXTRA_PERMISSION_GRANTED,
                                    false
                                )
                                val permission: UsbPermission =
                                    if (granted) {
                                        UsbPermission.Granted
                                    } else {
                                        UsbPermission.Denied
                                    }
                                permission.extraDevice =
                                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                                trySend(permission)
                                close()
                            }
                        } else {
                            trySend(UsbPermission.Denied)
                            close()
                        }
                    }
                }
                //登録
                context.registerReceiver(receiver, IntentFilter(INTENT_ACTION_USB_PERMISSION))

                // USB接続の許可ダイアログを表示してもらう
                val usbManager: UsbManager = context.getSystemService(USB_SERVICE) as UsbManager
                val permissionIntent =
                    PendingIntent.getBroadcast(context, 0, Intent(INTENT_ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE)
                usbManager.requestPermission(usbDevice, permissionIntent)
                //解除
                awaitClose { context.unregisterReceiver(receiver) }
            }

        // USB接続の許可を得る
        suspend fun getPermission(context: Context, usbDevice: UsbDevice): Deferred<UsbPermission> =
            coroutineScope {
                async { getPermissionFlow(context, usbDevice).first() }
            }
    }
}
