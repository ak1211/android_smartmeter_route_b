package com.ak1211.smartmeter_route_b.data

import android.icu.lang.UCharacter.isPrintable
import android.util.Log
import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import arrow.core.raise.option
import arrow.core.toOption
import com.ak1211.smartmeter_route_b.skstack.Epandesc
import com.ak1211.smartmeter_route_b.skstack.IpV6LinkAddress
import com.ak1211.smartmeter_route_b.skstack.PanChannel
import com.ak1211.smartmeter_route_b.skstack.PanId
import com.ak1211.smartmeter_route_b.skstack.RouteBId
import com.ak1211.smartmeter_route_b.skstack.RouteBPassword
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlin.math.ceil
import kotlin.math.pow

/**
 *
 */
class SmartWhmRouteBRepository(private val dataSource: UsbSerialPortDataSource) {
    val TAG = "SmartWhmRouteBRepository"
    val DEFALT_TIMEOUT = 5000L

    val nowOnPanaSessionMutableStateFlow = MutableStateFlow<Boolean>(false)
    val nowOnPanaSessionStateFlow get() = nowOnPanaSessionMutableStateFlow

    fun terminatePanaSession() {
        write(("SKTERM" + "\r\n").toByteArray())
        dataSource.closeSerialPort()
        nowOnPanaSessionStateFlow.update { false }
    }

    // スマートメータとの間でPANAセッションを開始する
    suspend fun startPanaSession(
        externalScope: CoroutineScope,
        usbSerialDriver: UsbSerialDriver,
        routeBId: RouteBId,
        routeBPassword: RouteBPassword,
        panId: PanId,
        panChannel: PanChannel,
        ipV6LinkAddress: IpV6LinkAddress
    ): Either<Throwable, ReceiveChannel<Incoming>> =
        try {
            either {
                if (nowOnPanaSessionStateFlow.value) {
                    Either.Left(RuntimeException("すでに開かれています")) // すでにポートが開かれている場合
                }
                val receiveChannel =
                    dataSource.openSerialPort(externalScope, usbSerialDriver).bind()
                nowOnPanaSessionStateFlow.update { true }

                // SKSETPWD Cを送ってみる
                write("SKSETPWD C ${routeBPassword}\r\n".toByteArray())
                withTimeout(DEFALT_TIMEOUT) { hasOk(receiveChannel).await() }.bind()
                // SKSETRBIDを送ってみる
                write("SKSETRBID ${routeBId}\r\n".toByteArray())
                withTimeout(DEFALT_TIMEOUT) { hasOk(receiveChannel).await() }.bind()

                // 自端末が使用する周波数の論理チャンネル番号を設定する
                write("SKSREG S2 ${panChannel}\r\n".toByteArray())
                withTimeout(DEFALT_TIMEOUT) { hasOk(receiveChannel).await() }.bind()

                // 自端末のPAN IDを設定する
                write("SKSREG S3 ${panId}\r\n".toByteArray())
                withTimeout(DEFALT_TIMEOUT) { hasOk(receiveChannel).await() }.bind()

                // PANA認証
                write("SKJOIN ${ipV6LinkAddress}\r\n".toByteArray())

                // EVENT 25を待つ
                withTimeout(DEFALT_TIMEOUT) {
                    waitForSucceeded(receiveChannel, { it.decodeToString().startsWith("EVENT 25") })
                        .await()
                }.bind()

                // 成功
                receiveChannel
            }
        } catch (ex: Throwable) {
            terminatePanaSession()
            Either.Left(ex)
        }

    fun write(bytes: ByteArray): Either<Throwable, Unit> {
        val logtostring = bytes.fold("") { acc: String, b: Byte ->
            val ch: Int = (b.toUInt() and 0xFFU).toInt()
            if (isPrintable(ch)) {
                acc + ch.toChar()
            } else {
                acc + String.format("\\x%02X", ch)
            }
        }
        Log.v(TAG, "write: $logtostring")
        return dataSource.write(bytes)
    }

    // アクティブスキャンを実行して接続対象のスマートメータを探す
    suspend fun doActiveScan(
        routeBId: RouteBId,
        routeBPassword: RouteBPassword,
        usbSerialDriver: UsbSerialDriver
    ): Deferred<Either<Throwable, Pair<Epandesc, IpV6LinkAddress>>> =
        coroutineScope {
            async {
                dataSource.closeSerialPort()
                dataSource.openSerialPort(this, usbSerialDriver).flatMap { channel ->
                    try {
                        either {
                            // SKVERを送ってみる
                            write("SKVER\r\n".toByteArray())
                            withTimeout(DEFALT_TIMEOUT) { hasOk(channel).await() }.bind()
                            // SKSETPWD Cを送ってみる
                            val rbpassword = routeBPassword.value
                            write("SKSETPWD C $rbpassword\r\n".toByteArray())
                            withTimeout(DEFALT_TIMEOUT) { hasOk(channel).await() }.bind()
                            // SKSETRBIDを送ってみる
                            val rbid = routeBId.value
                            write("SKSETRBID $rbid\r\n".toByteArray())
                            withTimeout(DEFALT_TIMEOUT) { hasOk(channel).await() }.bind()
                            // SKSCANを送ってみる
                            val channelMask: Long = 0xFFFF_FFFF
                            val numberOfChannels = channelMask.countOneBits().toDouble()
                            val duration: Int = 7
                            // 予定される実行時間
                            val scanTimeOfSeconds =
                                numberOfChannels * (0.01 * 2.0.pow(duration.toDouble()) + 1.0)
                            val scanTimeOfMilliSeconds =
                                ceil(scanTimeOfSeconds).toLong() * 1000L
                            val channelMaskString = channelMask.toString(16).uppercase()
                            write("SKSCAN 2 $channelMaskString $duration\r\n".toByteArray())
                            withTimeout(DEFALT_TIMEOUT) { hasOk(channel).await() }.bind()
                            Log.v(TAG, "scanTimeOfSeconds: $scanTimeOfSeconds")
                            // SKSCANの結果を得る
                            // スマートメータが存在しないあるいは電波状態が悪い場合があるのでタイムアウトをつけておく
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
                            dataSource.closeSerialPort()
                            // 結果
                            option {
                                val channel = panDescriptor["Channel"].toOption().bind()
                                val panId = panDescriptor["Pan ID"].toOption().bind()
                                val epandesc =
                                    Epandesc(
                                        channel = PanChannel(channel),
                                        channelPage = panDescriptor["Channel Page"].toOption()
                                            .bind(),
                                        panId = PanId(panId),
                                        addr = panDescriptor["Addr"].toOption().bind(),
                                        lqi = panDescriptor["LQI"].toOption().bind(),
                                        pairId = panDescriptor["PairID"].toOption().bind()
                                    )
                                val ipv6localaddr = ipv6addr.toOption().bind()
                                Pair(epandesc, IpV6LinkAddress(ipv6localaddr))
                            }.toEither { IllegalArgumentException("EPANDESCイベントが揃っていない") }
                                .bind()
                        }
                    } catch (ex: Throwable) {
                        dataSource.closeSerialPort()
                        Either.Left(ex)
                    }
                }
            }
        }


    // 結果を得るまで待つ
    private suspend fun waitForSucceeded(
        channel: ReceiveChannel<Incoming>,
        check: (ByteArray) -> Boolean
    ): Deferred<Either<Throwable, ByteArray>> =
        coroutineScope {
            var result: Either<Throwable, ByteArray> = Either.Left(RuntimeException("error"))
            for (incoming in channel) {
                when {
                    incoming is Either.Left -> {
                        result = incoming
                        break
                    }

                    incoming is Either.Right && check(incoming.value) -> {
                        Log.v(TAG, incoming.value.decodeToString())
                        result = incoming
                        break
                    }

                    else -> {
                        delay(10)
                    } // nothing to do
                }
            }
            async { result }
        }

    // OKの結果を待つ
    private suspend fun hasOk(channel: ReceiveChannel<Incoming>): Deferred<Either<Throwable, ByteArray>> =
        waitForSucceeded(channel, { it.decodeToString().startsWith("OK\r\n") })


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
