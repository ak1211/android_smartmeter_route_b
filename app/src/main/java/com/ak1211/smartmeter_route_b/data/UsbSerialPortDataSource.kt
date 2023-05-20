package com.ak1211.smartmeter_route_b.data

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.firstOrNone
import arrow.core.flatMap
import arrow.core.flatten
import arrow.core.raise.either
import arrow.core.toOption
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.newCoroutineContext
import kotlin.IllegalStateException

typealias Incoming = Either<Throwable, ByteArray>

class UsbSerialPortDataSource(private val usbManager: UsbManager) {
    val READ_WAIT_MILLIS: Int = 600
    val WRITE_WAIT_MILLIS: Int = 600
    private var optUsbConnection: Option<UsbDeviceConnection> = None
    private var optUsbSerialPort: Option<UsbSerialPort> = None
    private var _receiveSerialPort: ReceiveChannel<Incoming>? = null
    private var _isConnectedMutableStateFlow = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> get() = _isConnectedMutableStateFlow.asStateFlow()

    //
    fun disconnect() {
        _receiveSerialPort?.cancel()
        _receiveSerialPort = null
        optUsbSerialPort.map { it.close() }
        optUsbSerialPort = None
        optUsbConnection.map { it.close() }
        optUsbConnection = None
        _isConnectedMutableStateFlow.update { false }
    }

    //
    fun connect(
        externalScope: CoroutineScope,
        usbSerialDriver: UsbSerialDriver
    ): Either<Throwable, ReceiveChannel<Incoming>> {
        disconnect()
        // USBデバイスをオープンする
        val openedUsbConnection =
            Either.catch { usbManager.openDevice(usbSerialDriver.device).toOption() }
                .flatMap {
                    it.fold(
                        { Either.Left(RuntimeException("connection failed")) },
                        { conn -> Either.Right(conn) })
                }

        // シリアルポートをオープンする
        val result = either {
            val connection = openedUsbConnection.bind()
            // 接続するシリアルポートは先頭の物を選択する
            val port = usbSerialDriver.ports.firstOrNone()
                .toEither { RuntimeException("open port failed") }.bind()
            Either.catch {
                port.open(connection)
                port.setParameters(
                    115200,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
            }
            // シリアルポート読み込み用コルーチン
            val receiveChannel: ReceiveChannel<Incoming> =
                externalScope.produce(externalScope.newCoroutineContext(Dispatchers.IO)) {
                    val buffer = ByteArray(16384)
                    var cont = true
                    while (cont) {
                        Either.runCatching {
                            port.read(buffer, READ_WAIT_MILLIS)
                        }.onSuccess { length ->
                            if (length > 0) {
                                val data = buffer.sliceArray(0..length).clone()
                                send(Either.Right(data))
                            }
                        }.onFailure {
                            send(Either.Left(it))
                            cont = false
                            disconnect()
                        }
                    }
                }
            // 成功
            optUsbConnection = Some(connection)
            optUsbSerialPort = Some(port)
            _receiveSerialPort = receiveChannel
            _isConnectedMutableStateFlow.value = true
            Either.Right(receiveChannel)
        }.flatten()
        // 結果を返却する
        return result.mapLeft { ex ->
            // 失敗したとき
            disconnect()
            if (usbManager.hasPermission(usbSerialDriver.device)) {
                ex
            } else {
                // 権限がない
                RuntimeException("connection failed: permission is not granted")
            }
        }
    }

    //
    fun write(bytes: ByteArray): Either<Throwable, Unit> =
        optUsbSerialPort
            .toEither { IllegalStateException("port is closed") }
            .flatMap { Either.catch { it.write(bytes, WRITE_WAIT_MILLIS) } }

    //
    suspend fun write2(bytes: ByteArray): Deferred<Either<Throwable, Unit>> =
        coroutineScope {
            async(Dispatchers.IO) {
                optUsbSerialPort
                    .toEither { IllegalStateException("port is closed") }
                    .flatMap { Either.catch { it.write(bytes, WRITE_WAIT_MILLIS) } }
            }
        }
}

