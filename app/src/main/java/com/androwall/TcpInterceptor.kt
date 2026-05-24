package com.androwall

import android.net.VpnService
import android.util.Log
import com.androwall.data.ConnectionType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private const val TCP_TAG  = "TcpInterceptor"
private const val F_FIN    = 0x01
private const val F_SYN    = 0x02
private const val F_RST    = 0x04
private const val F_PSH    = 0x08
private const val F_ACK    = 0x10
private const val INSPECT_LIMIT = 8192
private const val CONN_TIMEOUT  = 5_000
private const val READ_TIMEOUT  = 15_000

class TcpInterceptor(
    private val vpnService:  VpnService,
    private val fos:         FileOutputStream,
    private val writeMutex:  Mutex,
    private val scope:       CoroutineScope,
    /** Called after inspect decision — for DB logging. */
    private val onConnection: suspend (host: String, url: String?, type: ConnectionType, blocked: Boolean) -> Unit,
    /** True → block the connection. */
    private val shouldBlock:  (host: String, url: String?, type: ConnectionType) -> Boolean
) {
    private val conns = ConcurrentHashMap<String, TcpConn>()

    // ── Entry — called from VPN dispatch coroutine ────────────────────────────

    fun handlePacket(
        raw: ByteArray, ipHeaderLen: Int,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seqNum: Long, ackNum: Long, flags: Int
    ) {
        val key = key(srcIp, srcPort, dstIp, dstPort)

        // Calculate payload
        val tcpHdrLen    = ((raw[ipHeaderLen + 12].toInt() and 0xFF) ushr 4) * 4
        val payloadStart = ipHeaderLen + tcpHdrLen
        val payload      = if (payloadStart < raw.size) raw.copyOfRange(payloadStart, raw.size)
        else byteArrayOf()

        when {
            flags and F_RST != 0 -> drop(key)

            flags and F_SYN != 0 && flags and F_ACK == 0 ->
                handleSyn(key, srcIp.copyOf(), srcPort, dstIp.copyOf(), dstPort, seqNum)

            flags and F_FIN != 0 ->
                conns[key]?.let { scope.launch { handleFin(key, it, seqNum) } }

            payload.isNotEmpty() ->
                conns[key]?.let { scope.launch { handleData(key, it, seqNum, payload) } }
        }
    }

    // ── SYN ───────────────────────────────────────────────────────────────────

    private fun handleSyn(
        key: String,
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        clientIsn: Long
    ) {
        val serverIsn = Random.nextLong() and 0xFFFFFFFFL
        val conn = TcpConn(key, srcIp, srcPort, dstIp, dstPort, clientIsn, serverIsn)
        conn.clientExpected.set((clientIsn + 1L) and 0xFFFFFFFFL)
        conn.serverSent.set((serverIsn + 1L) and 0xFFFFFFFFL)
        conns[key] = conn

        scope.launch {
            // Send SYN-ACK
            writeMutex.withLock {
                fos.write(buildPacket(
                    srcIp = dstIp, srcPort = dstPort,
                    dstIp = srcIp, dstPort = srcPort,
                    seq = serverIsn, ack = conn.clientExpected.get(),
                    flags = F_SYN or F_ACK, payload = byteArrayOf()
                ))
            }
            // Open protected socket to real destination
            try {
                val sock = Socket()
                vpnService.protect(sock)
                sock.soTimeout = READ_TIMEOUT
                sock.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), CONN_TIMEOUT)
                conn.socket = sock
                conn.state  = ConnState.ESTABLISHED
                conn.relayJob = scope.launch { relayFromServer(key, conn) }
            } catch (e: Exception) {
                Log.w(TCP_TAG, "Connect failed ${dstIp.toIp()}:$dstPort — ${e.message}")
                writeMutex.withLock {
                    fos.write(buildPacket(
                        srcIp = dstIp, srcPort = dstPort,
                        dstIp = srcIp, dstPort = srcPort,
                        seq = conn.serverSent.get(), ack = conn.clientExpected.get(),
                        flags = F_RST or F_ACK, payload = byteArrayOf()
                    ))
                }
                conns.remove(key)
            }
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private suspend fun handleData(key: String, conn: TcpConn, seqNum: Long, payload: ByteArray) {
        if (conn.state != ConnState.ESTABLISHED) return

        conn.clientExpected.set((seqNum + payload.size) and 0xFFFFFFFFL)

        // ACK
        writeMutex.withLock {
            fos.write(buildPacket(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = conn.serverSent.get(), ack = conn.clientExpected.get(),
                flags = F_ACK, payload = byteArrayOf()
            ))
        }

        val sock = conn.socket ?: return

        conn.mutex.withLock {
            if (conn.blocked) return   // already blocked, discard data

            if (!conn.inspected) {
                conn.inspectBuf.write(payload)
                val buf = conn.inspectBuf.toByteArray()

                val result: InspResult = when (conn.dstPort) {
                    80   -> inspectHttp(buf)
                    443  -> inspectTls(buf)
                    else -> InspResult.Allow(conn.dstIp.toIp(), null, ConnectionType.HTTP)
                }

                when (result) {
                    is InspResult.NeedMore -> return   // wait for more data

                    is InspResult.Block -> {
                        conn.inspected = true
                        conn.blocked   = true
                        if (result.host.isNotBlank())
                            scope.launch { onConnection(result.host, result.url, result.type, true) }
                        writeMutex.withLock {
                            fos.write(buildPacket(
                                srcIp = conn.dstIp, srcPort = conn.dstPort,
                                dstIp = conn.srcIp, dstPort = conn.srcPort,
                                seq = conn.serverSent.get(), ack = conn.clientExpected.get(),
                                flags = F_RST or F_ACK, payload = byteArrayOf()
                            ))
                        }
                        conn.relayJob?.cancel()
                        sock.runCatching { close() }
                        conns.remove(key)
                    }

                    is InspResult.Allow -> {
                        conn.inspected = true
                        if (result.host.isNotBlank())
                            scope.launch { onConnection(result.host, result.url, result.type, false) }
                        runCatching {
                            sock.getOutputStream().write(buf)
                            sock.getOutputStream().flush()
                        }.onFailure { drop(key) }
                    }
                }
            } else {
                // Inspected + allowed — forward directly
                runCatching {
                    sock.getOutputStream().write(payload)
                    sock.getOutputStream().flush()
                }.onFailure { drop(key) }
            }
        }
    }

    // ── FIN ───────────────────────────────────────────────────────────────────

    private suspend fun handleFin(key: String, conn: TcpConn, seqNum: Long) {
        conn.clientExpected.set((seqNum + 1L) and 0xFFFFFFFFL)
        writeMutex.withLock {
            fos.write(buildPacket(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = conn.serverSent.get(), ack = conn.clientExpected.get(),
                flags = F_FIN or F_ACK, payload = byteArrayOf()
            ))
        }
        conn.serverSent.addAndGet(1L)
        drop(key)
    }

    // ── Server → client relay ─────────────────────────────────────────────────

    private suspend fun relayFromServer(key: String, conn: TcpConn) {
        val sock = conn.socket ?: return
        val buf  = ByteArray(4096)
        try {
            val inp = sock.getInputStream()
            while (!sock.isClosed) {
                val n = withContext(Dispatchers.IO) { inp.read(buf) }
                if (n < 0) break
                if (n == 0) continue
                val data = buf.copyOf(n)
                writeMutex.withLock {
                    fos.write(buildPacket(
                        srcIp = conn.dstIp, srcPort = conn.dstPort,
                        dstIp = conn.srcIp, dstPort = conn.srcPort,
                        seq = conn.serverSent.get(), ack = conn.clientExpected.get(),
                        flags = F_PSH or F_ACK, payload = data
                    ))
                }
                conn.serverSent.addAndGet(n.toLong())
            }
            // Server closed — send FIN to client
            writeMutex.withLock {
                fos.write(buildPacket(
                    srcIp = conn.dstIp, srcPort = conn.dstPort,
                    dstIp = conn.srcIp, dstPort = conn.srcPort,
                    seq = conn.serverSent.get(), ack = conn.clientExpected.get(),
                    flags = F_FIN or F_ACK, payload = byteArrayOf()
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TCP_TAG, "Relay error: ${e.message}")
        } finally {
            conns.remove(key)
            sock.runCatching { close() }
        }
    }

    // ── Inspection ────────────────────────────────────────────────────────────

    private fun inspectHttp(data: ByteArray): InspResult {
        if (!String(data, Charsets.ISO_8859_1).contains("\r\n")) return InspResult.NeedMore
        val info = HttpInspector.extract(data)
            ?: return InspResult.Allow("", null, ConnectionType.HTTP)
        val blocked = shouldBlock(info.host, info.url, ConnectionType.HTTP)
        return if (blocked) InspResult.Block(info.host, info.url, ConnectionType.HTTP)
        else         InspResult.Allow(info.host, info.url, ConnectionType.HTTP)
    }

    private fun inspectTls(data: ByteArray): InspResult {
        if (data.isEmpty()) return InspResult.NeedMore
        if (data[0].toInt() and 0xFF != 0x16)
            return InspResult.Allow("", null, ConnectionType.HTTPS)  // not TLS
        if (data.size < 5) return InspResult.NeedMore

        val sni = TlsInspector.extractSni(data)
            ?: return if (data.size < INSPECT_LIMIT) InspResult.NeedMore
            else InspResult.Allow("", null, ConnectionType.HTTPS)

        val url     = "https://$sni"
        val blocked = shouldBlock(sni, url, ConnectionType.HTTPS)
        return if (blocked) InspResult.Block(sni, url, ConnectionType.HTTPS)
        else         InspResult.Allow(sni, url, ConnectionType.HTTPS)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun drop(key: String) {
        conns.remove(key)?.let { conn ->
            conn.relayJob?.cancel()
            conn.socket?.runCatching { close() }
        }
    }

    fun cleanup() = conns.keys.toList().forEach { drop(it) }

    private fun key(a: ByteArray, ap: Int, b: ByteArray, bp: Int) =
        "${a.toIp()}:$ap→${b.toIp()}:$bp"

    private fun ByteArray.toIp() = joinToString(".") { (it.toInt() and 0xFF).toString() }

    // ── Packet builder ────────────────────────────────────────────────────────

    private fun buildPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        seq: Long, ack: Long, flags: Int,
        payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val ipLen  = 20 + tcpLen
        val buf    = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IPv4 header
        buf.put(0x45.toByte()); buf.put(0)
        buf.putShort(ipLen.toShort()); buf.putShort(0); buf.putShort(0x4000.toShort())
        buf.put(64); buf.put(6); buf.putShort(0)     // TTL, TCP, checksum placeholder
        buf.put(srcIp); buf.put(dstIp)
        buf.putShort(10, ipChecksum(buf.array(), 0, 20).toShort())

        // TCP header
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt((seq and 0xFFFFFFFFL).toInt()); buf.putInt((ack and 0xFFFFFFFFL).toInt())
        buf.put(0x50.toByte()); buf.put(flags.toByte())   // data offset = 5 (20 bytes)
        buf.putShort(65535.toShort()); buf.putShort(0); buf.putShort(0)
        buf.put(payload)

        // TCP checksum at offset 36 = 20 (IP) + 16 (within TCP header)
        buf.putShort(36, tcpChecksum(srcIp, dstIp, buf.array(), 20, tcpLen).toShort())
        return buf.array()
    }

    private fun ipChecksum(d: ByteArray, off: Int, len: Int): Int {
        var s = 0; var i = off
        while (i < off + len - 1) { s += ((d[i].toInt() and 0xFF) shl 8) or (d[i+1].toInt() and 0xFF); i += 2 }
        if (len % 2 != 0) s += (d[off + len - 1].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    private fun tcpChecksum(sIp: ByteArray, dIp: ByteArray, d: ByteArray, off: Int, len: Int): Int {
        var s = 0
        s += ((sIp[0].toInt() and 0xFF) shl 8) or (sIp[1].toInt() and 0xFF)
        s += ((sIp[2].toInt() and 0xFF) shl 8) or (sIp[3].toInt() and 0xFF)
        s += ((dIp[0].toInt() and 0xFF) shl 8) or (dIp[1].toInt() and 0xFF)
        s += ((dIp[2].toInt() and 0xFF) shl 8) or (dIp[3].toInt() and 0xFF)
        s += 0x0006; s += len
        var i = off
        while (i < off + len - 1) { s += ((d[i].toInt() and 0xFF) shl 8) or (d[i+1].toInt() and 0xFF); i += 2 }
        if (len % 2 != 0) s += (d[off + len - 1].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }
}

// ── Supporting types ──────────────────────────────────────────────────────────

private sealed class InspResult {
    object NeedMore : InspResult()
    data class Block(val host: String, val url: String?, val type: ConnectionType) : InspResult()
    data class Allow(val host: String, val url: String?, val type: ConnectionType) : InspResult()
}

private enum class ConnState { SYN_RECEIVED, ESTABLISHED }

private class TcpConn(
    val key: String,
    val srcIp: ByteArray, val srcPort: Int,
    val dstIp: ByteArray, val dstPort: Int,
    val clientIsn: Long,  val serverIsn: Long
) {
    val mutex           = Mutex()
    var state           = ConnState.SYN_RECEIVED
    val clientExpected  = AtomicLong(0)
    val serverSent      = AtomicLong(0)
    var socket: Socket? = null
    val inspectBuf      = ByteArrayOutputStream(1024)
    var inspected       = false
    var blocked         = false
    var relayJob: Job?  = null
}