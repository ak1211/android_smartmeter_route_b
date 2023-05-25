package com.ak1211.smartmeter_route_b.data

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import arrow.core.Either
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.firstOrNone
import arrow.core.flatMap
import arrow.core.raise.either
import arrow.core.toOption
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

typealias Incoming = Either<Throwable, ByteArray>

class UsbSerialPortDataSource(
    private val usbManager: UsbManager,
    private val defaultDispacher: CoroutineDispatcher = Dispatchers.Default
) {
    val READ_WAIT_MILLIS: Int = 600
    val WRITE_WAIT_MILLIS: Int = 600
    private var optUsbConnection: Option<UsbDeviceConnection> = None
    private var optUsbSerialPort: Option<UsbSerialPort> = None
    private var _receiveSerialChannel: ReceiveChannel<Incoming>? = null
    private var _isOpenedSerialPortMutableStateFlow = MutableStateFlow<Boolean>(false)
    val isOpenedSerialPortStateFlow: StateFlow<Boolean> get() = _isOpenedSerialPortMutableStateFlow.asStateFlow()

    //
    fun closeSerialPort() {
        _receiveSerialChannel?.cancel()
        _receiveSerialChannel = null
        optUsbSerialPort.map { it.close() }
        optUsbSerialPort = None
        optUsbConnection.map { it.close() }
        optUsbConnection = None
        _isOpenedSerialPortMutableStateFlow.update { false }
    }

    //
    fun openSerialPort(
        externalScope: CoroutineScope,
        usbSerialDriver: UsbSerialDriver
    ): Either<Throwable, ReceiveChannel<Incoming>> = either {
        if (isOpenedSerialPortStateFlow.value) {
            Either.Left(RuntimeException("すでに開かれています")) // すでにオープンしている
        }
        val connection = openUsbDevice(usbSerialDriver.device).bind()
        // 接続するシリアルポートは先頭の物を選択する
        val port = usbSerialDriver.ports.firstOrNone()
            .toEither { RuntimeException("open port failed") }.bind()
        // シリアルポートをオープンする
        Either.catch {
            port.open(connection)
            port.setParameters(
                115200,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
        }
        // 成功
        optUsbConnection = Some(connection)
        optUsbSerialPort = Some(port)
        _isOpenedSerialPortMutableStateFlow.update { true }
        // シリアルポート読み込み用コルーチンを起動する
        readingCoroutine(externalScope, port).also {
            _receiveSerialChannel = it
        }
    }.fold({ ex -> // 失敗したとき
        closeSerialPort()
        when (usbManager.hasPermission(usbSerialDriver.device)) {
            //権限がある
            true -> Either.Left(ex)
            //権限がない
            false -> Either.Left(RuntimeException("connection failed: permission is not granted"))
        }
    }, { receiveChannel: ReceiveChannel<Incoming> ->
        Either.Right(receiveChannel)
    })

    // USBデバイスをオープンする
    private fun openUsbDevice(device: UsbDevice): Either<Throwable, UsbDeviceConnection> =
        Either.catch { usbManager.openDevice(device).toOption() }
            .flatMap {
                it.fold(
                    { Either.Left(RuntimeException("connection failed")) },
                    { conn -> Either.Right(conn) })
            }

    // シリアルポート読み込み用コルーチン
    private fun readingCoroutine(
        externalScope: CoroutineScope,
        port: UsbSerialPort
    ): ReceiveChannel<Incoming> =
        externalScope.produce<Incoming>(defaultDispacher) {
            val remains = ArrayDeque<Byte>(4096)
            try {
                val buffer = ByteArray(4096)
                while (!isClosedForSend) {
                    val length = port.read(buffer, READ_WAIT_MILLIS)
                    if (length > 0) {
                        // 一行未満のデータと今受信したデータを結合する
                        for (idx in 0..(length - 1)) {
                            remains.add(buffer[idx])
                        }
                    }
                    if (remains.size > 0) {
                        val position = remains.indexOf('\n'.code.toByte())
                        if (position >= 0) {
                            var line = mutableListOf<Byte>()
                            for (idx in 0..position) {
                                line.add(remains.removeFirst())
                            }
                            // 一行送信する
                            send(Either.Right(line.toByteArray()))
                        }
                    }
                }
                closeSerialPort()
            } catch (ex: CancellationException) {
                if (remains.size > 0) {
                    send(Either.Right(remains.toByteArray()))
                }
                throw (ex)
            } catch (ex: Throwable) {
                // 残りを送信して
                if (remains.size > 0) {
                    send(Either.Right(remains.toByteArray()))
                }
                send(Either.Left(ex))
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
            async(defaultDispacher) {
                optUsbSerialPort
                    .toEither { IllegalStateException("port is closed") }
                    .flatMap { Either.catch { it.write(bytes, WRITE_WAIT_MILLIS) } }
            }
        }
}
