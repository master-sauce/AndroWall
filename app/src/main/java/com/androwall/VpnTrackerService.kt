package com.androwall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.androwall.data.AppDatabase
import com.androwall.data.ConnectionLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VpnTrackerService : VpnService(), Runnable {

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * One coroutine per packet writes responses concurrently.
     * Mutex prevents interleaved writes on the TUN file descriptor.
     */
    private val writeMutex = Mutex()

    /**
     * Reactive cache of ALL blocked domains (global + per-app).
     * Updated by a coroutine collecting from the DB; read lock-free by the VPN thread.
     */
    @Volatile
    private var cachedBlockedDomains: Set<String> = emptySet()

    companion object {
        private const val TAG = "AndroWall"
        private const val CHANNEL_ID = "vpn_channel"
        private const val DNS_SERVER = "8.8.8.8"
        private const val DNS_PORT = 53

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, createNotification())

        val db = AppDatabase.getDatabase(this)
        serviceScope.launch {
            db.appDao().getAllBlockedDomains().collect { list ->
                cachedBlockedDomains = list.map { it.domain.lowercase().trim() }.toSet()
                Log.d(TAG, "Block cache updated: ${cachedBlockedDomains.size} entries")
            }
        }

        if (vpnThread?.isAlive != true) {
            vpnThread = Thread(this, "VpnTrackerThread").also { it.start() }
        }
        _isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        vpnThread?.interrupt()
        cleanup()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AndroWall Firewall", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndroWall Active")
            .setContentText("Firewall is protecting your apps...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    // ── VPN setup ─────────────────────────────────────────────────────────────

    override fun run() {
        try {
            if (setupVpn()) processPackets()
            else {
                Log.w(TAG, "No apps with filtering enabled — VPN not started")
                _isRunning.value = false
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal VPN error", e)
        } finally {
            cleanup()
        }
    }

    private fun setupVpn(): Boolean {
        val db = AppDatabase.getDatabase(this)
        val configs = runBlocking { db.appDao().getAllAppConfigs().first() }
        val enabledApps = configs.filter { it.isFilteringEnabled }
        if (enabledApps.isEmpty()) return false

        val builder = Builder()
            .setSession("AndroWall")
            .addAddress("10.0.0.2", 32)
            .addDnsServer(DNS_SERVER)
            .addRoute(DNS_SERVER, 32)  // only tunnel DNS-server traffic
            .setBlocking(true)          // blocking read avoids busy-loop

        enabledApps.forEach { cfg ->
            try { builder.addAllowedApplication(cfg.packageName) }
            catch (e: Exception) { Log.w(TAG, "Unknown package skipped: ${cfg.packageName}") }
        }

        vpnInterface = builder.establish()
        Log.d(TAG, "VPN established=${vpnInterface != null}, apps=${enabledApps.size}")
        return vpnInterface != null
    }

    // ── Packet loop ───────────────────────────────────────────────────────────

    /**
     * Reads raw packets on the dedicated VPN thread (blocking reads are fine here).
     * Each packet is immediately copied and dispatched to a Dispatchers.IO coroutine,
     * so a slow upstream DNS response never blocks subsequent packets.
     */
    private fun processPackets() {
        val fis = FileInputStream(vpnInterface!!.fileDescriptor)
        val fos = FileOutputStream(vpnInterface!!.fileDescriptor)
        val readBuf = ByteArray(32768)
        val db = AppDatabase.getDatabase(this)
        Log.d(TAG, "Packet loop started")

        while (!Thread.currentThread().isInterrupted) {
            try {
                val len = fis.read(readBuf)
                if (len <= 0) continue
                val packet = readBuf.copyOf(len)          // snapshot before next read
                serviceScope.launch { handlePacket(packet, fos, db) }
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) break
                Log.e(TAG, "Read error", e)
            }
        }
        Log.d(TAG, "Packet loop ended")
    }

    private suspend fun handlePacket(raw: ByteArray, fos: FileOutputStream, db: AppDatabase) {
        val info = parseDnsFromPacket(raw, raw.size) ?: return
        val blocked = isDomainBlocked(info.domain)
        Log.d(TAG, "DNS ${info.domain} → ${if (blocked) "BLOCKED" else "allow"}")
        logConnection(db, info.domain, blocked)

        val dnsResponse = if (blocked) buildNxdomain(info.dnsPayload)
        else forwardDns(info.dnsPayload) ?: return   // drop on forward failure

        val ipPacket = buildIpUdpPacket(
            srcIp = info.dstIp, srcPort = DNS_PORT,
            dstIp = info.srcIp, dstPort = info.srcPort,
            payload = dnsResponse
        )
        writeMutex.withLock { fos.write(ipPacket) }
    }

    // ── DNS parsing ───────────────────────────────────────────────────────────

    private data class DnsInfo(
        val domain: String,
        val dnsPayload: ByteArray,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray
    )

    private fun parseDnsFromPacket(raw: ByteArray, len: Int): DnsInfo? {
        if (len < 28) return null
        val pkt = ByteBuffer.wrap(raw, 0, len).order(ByteOrder.BIG_ENDIAN)

        val versionIhl = pkt.get(0).toInt() and 0xFF
        if (versionIhl ushr 4 != 4) return null           // IPv4 only
        val ihl = (versionIhl and 0x0F) * 4
        if (len < ihl + 8) return null
        if (pkt.get(9).toInt() and 0xFF != 17) return null // UDP only

        val srcIp = ByteArray(4).also { pkt.position(12); pkt.get(it) }
        val dstIp = ByteArray(4).also { pkt.get(it) }

        pkt.position(ihl)
        val srcPort    = pkt.short.toInt() and 0xFFFF
        val dstPort    = pkt.short.toInt() and 0xFFFF
        if (dstPort != DNS_PORT) return null
        val udpDataLen = (pkt.short.toInt() and 0xFFFF) - 8
        pkt.short                                          // skip UDP checksum
        if (udpDataLen < 12) return null

        val dnsPayload = ByteArray(udpDataLen).also { pkt.get(it) }
        val domain = parseDnsName(dnsPayload, 12)
        if (domain.isEmpty()) return null

        return DnsInfo(domain, dnsPayload, srcIp, srcPort, dstIp)
    }

    private fun parseDnsName(dns: ByteArray, start: Int): String {
        val sb = StringBuilder()
        var pos = start
        return runCatching {
            while (pos < dns.size) {
                val len = dns[pos].toInt() and 0xFF
                if (len == 0 || len and 0xC0 == 0xC0) break
                pos++
                repeat(len) { if (pos < dns.size) sb.append(dns[pos++].toInt().toChar()) }
                sb.append('.')
            }
            sb.toString().trimEnd('.')
        }.getOrDefault("")
    }

    private fun isDomainBlocked(domain: String): Boolean {
        val lower = domain.lowercase()
        return cachedBlockedDomains.any { blocked ->
            lower == blocked || lower.endsWith(".$blocked")
        }
    }

    // ── DNS forwarding ────────────────────────────────────────────────────────

    /**
     * Forwards to 8.8.8.8:53 via a protect()ed socket so the socket itself
     * is NOT routed into the VPN tunnel (which would cause an infinite loop).
     */
    private fun forwardDns(payload: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { sock ->
            protect(sock)          // ← bypass tunnel for this socket — critical
            sock.soTimeout = 3000
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(DNS_SERVER), DNS_PORT))
            val resp = ByteArray(4096)
            val respPkt = DatagramPacket(resp, resp.size)
            sock.receive(respPkt)
            resp.copyOf(respPkt.length)
        }
    }.getOrNull().also { if (it == null) Log.w(TAG, "DNS forward failed") }

    /** Flips QR=1 and RCODE=3 in the query header to produce a minimal NXDOMAIN. */
    private fun buildNxdomain(query: ByteArray): ByteArray {
        val r = query.copyOf()
        if (r.size >= 4) {
            r[2] = (r[2].toInt() or 0x80).toByte()              // QR = 1 (response)
            r[3] = ((r[3].toInt() and 0xF0) or 0x03).toByte()   // RCODE = 3 (NXDOMAIN)
        }
        return r
    }

    // ── Packet building ───────────────────────────────────────────────────────

    /** Wraps DNS payload in a well-formed IPv4/UDP packet for writing back to TUN. */
    private fun buildIpUdpPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen  = 20 + udpLen
        val pkt = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        // IPv4 header
        pkt.put(0x45.toByte())            // Version=4, IHL=5
        pkt.put(0.toByte())               // DSCP/ECN
        pkt.putShort(ipLen.toShort())     // Total length
        pkt.putShort(0.toShort())         // Identification
        pkt.putShort(0x4000.toShort())    // Flags: DF, no fragment
        pkt.put(64.toByte())              // TTL
        pkt.put(17.toByte())              // Protocol: UDP
        pkt.putShort(0.toShort())         // Checksum placeholder
        pkt.put(srcIp)
        pkt.put(dstIp)
        // Fill real checksum at offset 10
        pkt.putShort(10, onesComplementChecksum(pkt.array(), 0, 20).toShort())

        // UDP header
        pkt.putShort(srcPort.toShort())
        pkt.putShort(dstPort.toShort())
        pkt.putShort(udpLen.toShort())
        pkt.putShort(0.toShort())         // UDP checksum = 0 (optional in IPv4 — RFC 768)

        pkt.put(payload)
        return pkt.array()
    }

    /** RFC 1071 one's-complement internet checksum. */
    private fun onesComplementChecksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len % 2 != 0) sum += (data[offset + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun logConnection(db: AppDatabase, domain: String, blocked: Boolean) {
        serviceScope.launch {
            db.appDao().insertLog(ConnectionLog(packageName = "firewall", domain = domain, isBlocked = blocked))
        }
    }

    private fun cleanup() {
        _isRunning.value = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        vpnThread = null
    }
}