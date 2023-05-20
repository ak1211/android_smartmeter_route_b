package com.ak1211.smartmeter_route_b.data

import android.util.Log
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.flatten
import arrow.core.raise.either
import arrow.core.toOption
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 *
 */
class SmartWhmRouteBRepository(private val dataSource: UsbSerialPortDataSource) {
    enum class ActiveScanProgress { STARTED, ACTIVE_SCANING, DONE }

    val TAG = "SmartWhmRouteBRepository"
    val isConnected get() = dataSource.isConnected

    fun disconnect() = dataSource.disconnect()

    fun connect(scope: CoroutineScope, usbSerialDriver: UsbSerialDriver) =
        dataSource.connect(scope, usbSerialDriver)

    fun write(bytes: ByteArray): Either<Throwable, Unit> {
        Log.v(TAG, "write:${bytes.decodeToString()}")
        return dataSource.write(bytes)
    }

    private suspend fun hasOk(channel: ReceiveChannel<Incoming>): Either<Throwable, Unit> {
        return withContext(Dispatchers.IO) {
            val loop = async {
                var builder = StringBuilder()
                var ok = false
                while (!ok) {
                    Either.catch { channel.receive() }
                        .flatten()
                        .map { arrival -> builder.append(arrival.decodeToString()) }
                    val lines = builder.lines()
                    ok = lines.map { it.startsWith("OK", 0) }.any()
                    builder = lines.lastOrNull()?.let { StringBuilder(it) } ?: StringBuilder()
                }
                ok
            }
            val job = launch { loop.await() }
            // タイムアウトするまでループで待つ
            for (count in 0..10) {
                if (job.isCompleted) {
                    break
                }
                delay(10)
            }
            if (job.isCompleted) {
                Either.Right(Unit)
            } else {
                Either.Left(RuntimeException("失敗しました"))
            }
        }
    }

    // アクティブスキャンを実行して接続対象のスマートメータを探す
    suspend fun doActiveScan(
        appPreferences: AppPreferences,
        usbSerialDriver: UsbSerialDriver
    ): Deferred<Either<Throwable, AppPreferences>> =
        coroutineScope {
            async {
                connect(this, usbSerialDriver).flatMap { channel ->
                    try {
                        either {
                            // SKVERを送ってみる
                            write("SKVER\r\n".toByteArray())
                            hasOk(channel).bind()
                            // SKSETPWD Cを送ってみる
                            val rbpassword = appPreferences.whmRouteBPassword.value
                            write("SKSETPWD C $rbpassword\r\n".toByteArray())
                            hasOk(channel).bind()
                            // SKSETRBIDを送ってみる
                            val rbid = appPreferences.whmRouteBId.value
                            write("SKSETRBID $rbid\r\n".toByteArray())
                            hasOk(channel).bind()
                            // SKSCANを送ってみる
                            write("SKSCAN 2 FFFFFFFF 6\r\n".toByteArray())
                            hasOk(channel).bind()
                            // SKSCANの結果
                            val panDescriptor = getSkscanResult(channel).await().bind()
                            // SKLL64を送ってみる
                            panDescriptor["Addr"]?.let { addr ->
                                write("SKLL64 $addr\r\n".toByteArray())
                            }
                            val ipv6addr = getSkll64Result(channel).await().bind()
                            //
                            disconnect()
                            // 結果
                            appPreferences.copy(
                                whmPanChannel = panDescriptor["Channel"].toOption()
                                    .map { AppPreferences.PanChannel(it) },
                                whmPanId = panDescriptor["Pan ID"].toOption()
                                    .map { AppPreferences.PanId(it) },
                                whmIpv6LinkLocalAddress = ipv6addr.toOption()
                            )
                        }
                    } finally {
                        disconnect()
                    }
                }
            }
        }

    // SKSCANの結果を得る
    // シリアル通信受信待ちで停止する可能性があるのでasyncにした
    private suspend fun getSkscanResult(channel: ReceiveChannel<Incoming>) =
        coroutineScope {
            async {
                either {
                    val hm = HashMap<String, String>()
                    val builder = StringBuilder()
                    for (incoming in channel) {
                        val bytearray = incoming.bind()
                        builder.append(bytearray.decodeToString())
                        // EPANDESC行以降7行取り出す
                        val lines = builder.lines()
                        val epandesc =
                            lines.dropWhile { !it.startsWith("EPANDESC", 0) }.take(7)
                        if (epandesc.size == 7) {
                            val items = epandesc.map { it.split(':').map { it.trim() } }
                                .filter { it.size == 2 }
                            for (i in items) {
                                hm.put(i[0], i[1])
                            }
                            builder.clear()
                            break
                        }
                    }
                    // 実行結果
                    hm
                }
            }
        }

    // SKLL64の実行結果を得る
    // シリアル通信受信待ちで停止する可能性があるのでasyncにした
    private suspend fun getSkll64Result(channel: ReceiveChannel<Incoming>) =
        coroutineScope {
            async {
                either {
                    val builder = StringBuilder()
                    var ipv6addr: String? = null
                    for (incoming in channel) {
                        val bytearray = incoming.bind()
                        builder.append(bytearray.decodeToString())
                        // SKLLの結果を得る
                        val regex =
                            "([0-9a-fA-F]{4}:){7}[0-9a-fA-F]{4}".toRegex(RegexOption.IGNORE_CASE)
                        ipv6addr =
                            builder.lines().filter { regex.find(it) != null }
                                .firstOrNull()
                        if (ipv6addr != null) {
                            break
                        }
                    }
                    ipv6addr
                }
            }
        }
}
