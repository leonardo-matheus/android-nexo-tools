package br.com.nexotools.network

import androidx.annotation.Keep

@Keep
object NativeTunnel {
    init { System.loadLibrary("nexo-tunnel") }
    @JvmStatic external fun prepare(): Long
    @JvmStatic external fun run(config: String, tunFd: Int, mirrorFd: Int, token: Long): Int
    @JvmStatic external fun stop(token: Long)
    @JvmStatic external fun dropped(): Long
}
