package com.webrtc_phone.android.core.net

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.channels.SocketChannel
import javax.net.SocketFactory

/**
 * 规避基于 SNI 的中间设备（DPI）拦截。
 *
 * 实测部分网络会在「携带域名的 TLS ClientHello 落在单个 TCP 包内」时直接断开连接；
 * 浏览器因发送 ~1.5KB 的后量子 ClientHello（天然跨 2 个包）不受影响，
 * 而 Android 的 ClientHello 约 0.5KB、单包发出，于是 App 无法登录、网页却正常。
 *
 * 做法：TLS 连接建立后的第一次写入（即 ClientHello）拆成两个 TCP 段、间隔 10ms 发送。
 * 只影响握手首包，之后的数据原样直通；每个新连接额外开销约 10ms。
 *
 * 注意：Conscrypt 对“有文件描述符”的底层 socket 会绕过 Java 流直接读写 fd，
 * 本类以未连接的独立 Socket 作为外壳（自身无 fd），迫使 TLS 层走 getOutputStream()。
 */
class FragmentingSocketFactory(private val delegate: SocketFactory = getDefault()) : SocketFactory() {

    override fun createSocket(): Socket = FragmentingSocket(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = wrapConnected { delegate.createSocket(host, port) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        wrapConnected { delegate.createSocket(host, port, localHost, localPort) }
    override fun createSocket(host: InetAddress?, port: Int): Socket = wrapConnected { delegate.createSocket(host, port) }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        wrapConnected { delegate.createSocket(address, port, localAddress, localPort) }

    private inline fun wrapConnected(block: () -> Socket) = FragmentingSocket(block())

    companion object {
        const val SPLIT_AT = 1
        const val DELAY_MS = 10L
    }
}

private class FragmentingSocket(private val s: Socket) : Socket() {

    private val out: OutputStream by lazy {
        object : FilterOutputStream(s.getOutputStream()) {
            @Volatile private var first = true

            override fun write(b: ByteArray, off: Int, len: Int) {
                // 仅拆分握手首包（TLS record type 0x16），其余写入直通
                if (first && len > FragmentingSocketFactory.SPLIT_AT && b[off] == 0x16.toByte()) {
                    first = false
                    val cut = FragmentingSocketFactory.SPLIT_AT
                    out.write(b, off, cut)
                    out.flush()
                    try {
                        Thread.sleep(FragmentingSocketFactory.DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    out.write(b, off + cut, len - cut)
                    return
                }
                first = false
                out.write(b, off, len)
            }

            override fun write(b: Int) {
                first = false
                out.write(b)
            }
        }
    }

    override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)
    override fun connect(endpoint: SocketAddress?, timeout: Int) {
        s.connect(endpoint, timeout)
        // 关闭 Nagle，保证两段真正分开发送
        s.tcpNoDelay = true
    }

    override fun bind(bindpoint: SocketAddress?) = s.bind(bindpoint)
    override fun getInetAddress(): InetAddress? = s.inetAddress
    override fun getLocalAddress(): InetAddress? = s.localAddress
    override fun getPort(): Int = s.port
    override fun getLocalPort(): Int = s.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = s.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = s.localSocketAddress
    override fun getChannel(): SocketChannel? = null
    override fun getInputStream(): InputStream = s.getInputStream()
    override fun getOutputStream(): OutputStream = out
    override fun setTcpNoDelay(on: Boolean) { s.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = s.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) = s.setSoLinger(on, linger)
    override fun getSoLinger(): Int = s.soLinger
    override fun sendUrgentData(data: Int) = s.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { s.oobInline = on }
    override fun getOOBInline(): Boolean = s.oobInline
    override fun setSoTimeout(timeout: Int) { s.soTimeout = timeout }
    override fun getSoTimeout(): Int = s.soTimeout
    override fun setSendBufferSize(size: Int) { s.sendBufferSize = size }
    override fun getSendBufferSize(): Int = s.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { s.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = s.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { s.keepAlive = on }
    override fun getKeepAlive(): Boolean = s.keepAlive
    override fun setTrafficClass(tc: Int) { s.trafficClass = tc }
    override fun getTrafficClass(): Int = s.trafficClass
    override fun setReuseAddress(on: Boolean) { s.reuseAddress = on }
    override fun getReuseAddress(): Boolean = s.reuseAddress
    override fun close() = s.close()
    override fun shutdownInput() = s.shutdownInput()
    override fun shutdownOutput() = s.shutdownOutput()
    override fun toString(): String = "Fragmenting($s)"
    override fun isConnected(): Boolean = s.isConnected
    override fun isBound(): Boolean = s.isBound
    override fun isClosed(): Boolean = s.isClosed
    override fun isInputShutdown(): Boolean = s.isInputShutdown
    override fun isOutputShutdown(): Boolean = s.isOutputShutdown
}
