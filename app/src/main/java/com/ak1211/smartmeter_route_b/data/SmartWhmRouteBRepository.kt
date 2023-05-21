package com.ak1211.smartmeter_route_b.data

import android.util.Log
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import arrow.core.toOption
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.math.ceil
import kotlin.math.pow

/**
 *
 */
class SmartWhmRouteBRepository(private val dataSource: UsbSerialPortDataSource) {
    val TAG = "SmartWhmRouteBRepository"
    val isConnectedPana get() = dataSource.isConnected

    fun terminatePanaSession() = dataSource.disconnect()

    fun startPanaSession(externalScope: CoroutineScope, usbSerialDriver: UsbSerialDriver)
            : Either<Throwable, ReceiveChannel<Incoming>> {
        return dataSource.connect(externalScope, usbSerialDriver)
    }

    fun write(bytes: ByteArray): Either<Throwable, Unit> {
        Log.v(TAG, "write:${bytes.decodeToString()}")
        return dataSource.write(bytes)
    }

    private suspend fun hasOk(channel: ReceiveChannel<Incoming>): Deferred<Either<Throwable, Unit>> =
        coroutineScope {
            fun checkIfOk(bytes: ByteArray): Boolean {
                return bytes.decodeToString().indexOf("OK\r\n") == 0
            }

            var result: Either<Throwable, Unit> = Either.Left(RuntimeException("error"))
            for (incoming in channel) {
                when {
                    incoming is Either.Left -> {
                        result = incoming
                        break
                    }

                    incoming is Either.Right && checkIfOk(incoming.value) -> {
                        Log.v(TAG, "OK")
                        result = Either.Right(Unit)
                        break
                    }

                    else -> {} // nothing to do
                }
            }
            async { result }
        }

    // アクティブスキャンを実行して接続対象のスマートメータを探す
    suspend fun doActiveScan(
        appPreferences: AppPreferences,
        usbSerialDriver: UsbSerialDriver
    ): Deferred<Either<Throwable, AppPreferences>> =
        coroutineScope {
            async {
                dataSource.disconnect()
                dataSource.connect(this, usbSerialDriver).flatMap { channel ->
                    try {
                        either {
                            val DEFALT_TIMEOUT = 3000L
                            // SKVERを送ってみる
                            write("SKVER\r\n".toByteArray())
                            withTimeout(DEFALT_TIMEOUT) { hasOk(channel).await() }.bind()
                            // SKSETPWD Cを送ってみる
                            val rbpassword = appPreferences.whmRouteBPassword.value
                            write("SKSETPWD C $rbpassword\r\n".toByteArray())
                            withTimeout(DEFALT_TIMEOUT) { hasOk(channel).await() }.bind()
                            // SKSETRBIDを送ってみる
                            val rbid = appPreferences.whmRouteBId.value
                            write("SKSETRBID $rbid\r\n".toByteArray())
                            hasOk(channel).await().bind()
                            // SKSCANを送ってみる
                            val channelMask: Long = 0xFFFF_FFFF
                            val numberOfChannels = channelMask.countOneBits().toDouble()
                            val duration: Int = 7
                            val scanTimeOfSeconds =
                                numberOfChannels * (0.01 * 2.0.pow(duration.toDouble()) + 1.0)
                            val scanTimeOfMilliSeconds = ceil(scanTimeOfSeconds).toLong() * 1000L
                            val channelMaskString = channelMask.toString(16).uppercase()
                            write("SKSCAN 2 $channelMaskString $duration\r\n".toByteArray())
                            withTimeout(DEFALT_TIMEOUT) { hasOk(channel).await() }.bind()
                            Log.v(TAG, "scanTimeOfSeconds: $scanTimeOfSeconds")
                            // SKSCANの結果
                            // これは待ち時間がながい
                            val panDescriptor =
                                withTimeout(scanTimeOfMilliSeconds) {
                                    getSkscanResult(channel).await()
                                }.bind()
                            // SKLL64を送ってみる
                            panDescriptor["Addr"]?.let { addr ->
                                write("SKLL64 $addr\r\n".toByteArray())
                            }
                            // SKLL64の結果
                            val ipv6addr =
                                withTimeout(DEFALT_TIMEOUT) { getSkll64Result(channel).await() }.bind()
                            //
                            dataSource.disconnect()
                            // 結果
                            appPreferences.copy(
                                whmPanChannel = panDescriptor["Channel"].toOption()
                                    .map { AppPreferences.PanChannel(it) },
                                whmPanId = panDescriptor["Pan ID"].toOption()
                                    .map { AppPreferences.PanId(it) },
                                whmIpv6LinkLocalAddress = ipv6addr.toOption()
                            )
                        }
                    } catch (ex: Throwable) {
                        Either.Left(ex)
                    } finally {
                        Log.v(TAG, "finally")
                        dataSource.disconnect()
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
                    val lines: MutableList<String> = mutableListOf()
                    for (incoming in channel) {
                        val line = incoming.bind().decodeToString()
                        // EVENT
                        if (line.startsWith("EVENT", 0)) {
                            Log.v(TAG, "arrived EVENT is: $line")
                        } else {
                            lines.add(line)
                        }
                        // EPANDESC行以降7行取り出す
                        val epandesc =
                            lines.dropWhile { it.startsWith("EPANDESC", 0).not() }.take(7)
                        if (epandesc.size == 7) {
                            val items = epandesc.map { it.split(':').map { it.trim() } }
                                .filter { it.size == 2 }
                            for (i in items) {
                                hm.put(i[0], i[1])
                            }
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
                    var ipv6addr: String? = null
                    for (incoming in channel) {
                        val line = incoming.bind().decodeToString()
                        // SKLLの結果を得る
                        val regex =
                            "([0-9a-fA-F]{4}:){7}[0-9a-fA-F]{4}".toRegex(RegexOption.IGNORE_CASE)
                        val matchResult = regex.find(line)
                        if (matchResult != null && matchResult.range.start == 0) {
                            ipv6addr = line.trim()
                            break
                        }
                    }
                    ipv6addr
                }
            }
        }
}
