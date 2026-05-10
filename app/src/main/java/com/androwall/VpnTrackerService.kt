package com.androwall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.androwall.data.*
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
    private val writeMutex = Mutex()

    @Volatile private var cachedRules: List<FilterRule> = emptyList()

    companion object {
        private const val TAG = "AndroWall"
        private const val CHANNEL_ID = "vpn_channel"
        private const val DNS_SERVER = "8.8.8.8"
        private const val DNS_PORT = 53
        const val PREFS_NAME = "androwall_prefs"
        const val KEY_FILTER_MODE = "filter_mode"
        private const val KEY_USER_STOPPED = "user_stopped"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        // Reactive filter mode — observed by all composables via collectAsState()
        private val _filterMode = MutableStateFlow(FilterMode.BLACKLIST)
        val filterMode: StateFlow<FilterMode> = _filterMode

        /** Called from UI to change the mode. Persists to SharedPreferences. */
        fun setFilterMode(context: Context, mode: FilterMode) {
            _filterMode.value = mode
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_FILTER_MODE, mode.name).apply()
        }

        /** Load persisted mode at app start. Call once from AndroWallApp. */
        fun loadPersistedMode(context: Context) {
            val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FILTER_MODE, FilterMode.BLACKLIST.name)
            _filterMode.value = runCatching { FilterMode.valueOf(stored ?: "") }
                .getOrDefault(FilterMode.BLACKLIST)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ── STOP action ───────────────────────────────────────────────────────
        if (intent?.action == "STOP") {
            // Set false FIRST so the UI updates immediately, before stopSelf() is even processed
            _isRunning.value = false
            prefs.edit().putBoolean(KEY_USER_STOPPED, true).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // ── Android auto-restart (null intent + START_STICKY) ─────────────────
        // If the user deliberately stopped the service, don't let Android restart it
        if (intent == null && prefs.getBoolean(KEY_USER_STOPPED, false)) {
            Log.d(TAG, "Suppressing Android restart — user stopped the service")
            stopSelf()
            return START_NOT_STICKY
        }

        // ── Normal start ──────────────────────────────────────────────────────
        prefs.edit().putBoolean(KEY_USER_STOPPED, false).apply()
        // Sync filter mode from prefs into the companion StateFlow
        loadPersistedMode(this)

        startForeground(1, createNotification())

        val db = AppDatabase.getDatabase(this)
        serviceScope.launch {
            db.appDao().getAllRules().collect { rules ->
                cachedRules = rules.filter { it.isEnabled }
                Log.d(TAG, "Rules cache: ${cachedRules.size} active " +
                        "(${cachedRules.count { it.action == RuleAction.BLOCK }} block, " +
                        "${cachedRules.count { it.action == RuleAction.ALLOW }} allow)")
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
        // Close fd first — unblocks fis.read() on the VPN thread immediately
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        vpnThread?.interrupt()
        _isRunning.value = false   // Redundant if STOP path was taken, but safe
        vpnThread = null
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "AndroWall Firewall", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
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
                Log.w(TAG, "No apps have filtering enabled — stopping")
                _isRunning.value = false
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal VPN error", e)
        } finally {
            _isRunning.value = false
        }
    }

    private fun setupVpn(): Boolean {
        val db = AppDatabase.getDatabase(this)
        val configs = runBlocking { db.appDao().getAllAppConfigs().first() }
        val enabled = configs.filter { it.isFilteringEnabled }
        if (enabled.isEmpty()) return false

        val builder = Builder()
            .setSession("AndroWall")
            .addAddress("10.0.0.2", 32)
            .addDnsServer(DNS_SERVER)
            .addRoute(DNS_SERVER, 32)
            .setBlocking(true)

        enabled.forEach { cfg ->
            try { builder.addAllowedApplication(cfg.packageName) }
            catch (e: Exception) { Log.w(TAG, "Skipped: ${cfg.packageName}") }
        }

        vpnInterface = builder.establish()
        Log.d(TAG, "VPN established=${vpnInterface != null}, apps=${enabled.size}")
        return vpnInterface != null
    }

    // ── Packet loop ───────────────────────────────────────────────────────────

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
                val packet = readBuf.copyOf(len)
                serviceScope.launch { handlePacket(packet, fos, db) }
            } catch (e: Exception) {
                Log.d(TAG, "Packet loop ending: ${e.message}")
                break
            }
        }
        Log.d(TAG, "Packet loop ended")
    }

    private suspend fun handlePacket(raw: ByteArray, fos: FileOutputStream, db: AppDatabase) {
        val info = parseDnsFromPacket(raw, raw.size) ?: return
        val blocked = isDomainBlocked(info.domain)
        Log.d(TAG, "DNS ${info.domain} → ${if (blocked) "BLOCK [${_filterMode.value}]" else "allow"}")
        logConnection(db, info.domain, blocked)

        val dnsResponse = if (blocked) buildNxdomain(info.dnsPayload)
        else forwardDns(info.dnsPayload) ?: return

        val ipPacket = buildIpUdpPacket(
            srcIp = info.dstIp, srcPort = DNS_PORT,
            dstIp = info.srcIp, dstPort = info.srcPort,
            payload = dnsResponse
        )
        writeMutex.withLock { fos.write(ipPacket) }
    }

    // ── Rule matching — uses companion StateFlow for live mode updates ─────────

    private fun isDomainBlocked(domain: String): Boolean =
        isEffectivelyBlocked(domain, cachedRules, _filterMode.value)

    // ── DNS parsing ───────────────────────────────────────────────────────────

    private data class DnsInfo(
        val domain: String, val dnsPayload: ByteArray,
        val srcIp: ByteArray, val srcPort: Int, val dstIp: ByteArray
    )

    private fun parseDnsFromPacket(raw: ByteArray, len: Int): DnsInfo? {
        if (len < 28) return null
        val pkt = ByteBuffer.wrap(raw, 0, len).order(ByteOrder.BIG_ENDIAN)
        val versionIhl = pkt.get(0).toInt() and 0xFF
        if (versionIhl ushr 4 != 4) return null
        val ihl = (versionIhl and 0x0F) * 4
        if (len < ihl + 8 || pkt.get(9).toInt() and 0xFF != 17) return null

        val srcIp = ByteArray(4).also { pkt.position(12); pkt.get(it) }
        val dstIp = ByteArray(4).also { pkt.get(it) }

        pkt.position(ihl)
        val srcPort    = pkt.short.toInt() and 0xFFFF
        val dstPort    = pkt.short.toInt() and 0xFFFF
        if (dstPort != DNS_PORT) return null
        val udpDataLen = (pkt.short.toInt() and 0xFFFF) - 8
        pkt.short
        if (udpDataLen < 12) return null

        val dnsPayload = ByteArray(udpDataLen).also { pkt.get(it) }
        val domain = parseDnsName(dnsPayload, 12)
        if (domain.isEmpty()) return null
        return DnsInfo(domain, dnsPayload, srcIp, srcPort, dstIp)
    }

    private fun parseDnsName(dns: ByteArray, start: Int): String {
        val sb = StringBuilder(); var pos = start
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

    // ── DNS forwarding ────────────────────────────────────────────────────────

    private fun forwardDns(payload: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { sock ->
            protect(sock)
            sock.soTimeout = 3000
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(DNS_SERVER), DNS_PORT))
            val resp = ByteArray(4096); val pkt = DatagramPacket(resp, resp.size)
            sock.receive(pkt); resp.copyOf(pkt.length)
        }
    }.getOrNull().also { if (it == null) Log.w(TAG, "DNS forward failed") }

    private fun buildNxdomain(query: ByteArray): ByteArray {
        val r = query.copyOf()
        if (r.size >= 4) {
            r[2] = (r[2].toInt() or 0x80).toByte()
            r[3] = ((r[3].toInt() and 0xF0) or 0x03).toByte()
        }
        return r
    }

    // ── Packet building ───────────────────────────────────────────────────────

    private fun buildIpUdpPacket(
        srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size; val ipLen = 20 + udpLen
        val pkt = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)
        pkt.put(0x45.toByte()); pkt.put(0.toByte())
        pkt.putShort(ipLen.toShort()); pkt.putShort(0.toShort())
        pkt.putShort(0x4000.toShort()); pkt.put(64.toByte()); pkt.put(17.toByte())
        pkt.putShort(0.toShort()); pkt.put(srcIp); pkt.put(dstIp)
        pkt.putShort(10, onesComplementChecksum(pkt.array(), 0, 20).toShort())
        pkt.putShort(srcPort.toShort()); pkt.putShort(dstPort.toShort())
        pkt.putShort(udpLen.toShort()); pkt.putShort(0.toShort())
        pkt.put(payload)
        return pkt.array()
    }

    private fun onesComplementChecksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0; var i = offset
        while (i < offset + len - 1) { sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i+1].toInt() and 0xFF); i += 2 }
        if (len % 2 != 0) sum += (data[offset + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun logConnection(db: AppDatabase, domain: String, blocked: Boolean) {
        serviceScope.launch {
            db.appDao().insertLog(ConnectionLog(packageName = "firewall", domain = domain, isBlocked = blocked))
        }
    }
}