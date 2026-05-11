package com.androwall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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

class VpnTrackerService : VpnService() {

    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()
    private var initialized = false

    @Volatile private var cachedRules: List<FilterRule> = emptyList()

    companion object {
        private const val TAG        = "AndroWall"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIF_ID   = 1
        private const val DNS_SERVER = "8.8.8.8"
        private const val DNS_PORT   = 53
        private const val PREFS_NAME = "androwall_prefs"
        private const val KEY_MODE   = "filter_mode"

        // ── Intent actions ────────────────────────────────────────────────────
        const val ACTION_START_VPN = "com.androwall.ACTION_START_VPN"
        const val ACTION_STOP_VPN  = "com.androwall.ACTION_STOP_VPN"

        /**
         * Tears down the VPN tunnel, removes the notification, and stops the
         * service process entirely. This is the only way to fully dismiss
         * the notification — the user cannot swipe it away.
         */
        const val ACTION_CLOSE = "com.androwall.ACTION_CLOSE"

        // ── Shared observable state ───────────────────────────────────────────
        private val _isRunning  = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _filterMode = MutableStateFlow(FilterMode.BLACKLIST)
        val filterMode: StateFlow<FilterMode> = _filterMode

        fun loadPersistedMode(context: Context) {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MODE, FilterMode.BLACKLIST.name) ?: FilterMode.BLACKLIST.name
            _filterMode.value = FilterMode.valueOf(raw)
        }

        fun setFilterMode(context: Context, mode: FilterMode) {
            _filterMode.value = mode
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode.name).apply()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                stopVpn()
                return START_STICKY
            }
            ACTION_START_VPN -> {
                startVpn()
                return START_STICKY
            }
            ACTION_CLOSE -> {
                // 1. Tear down VPN tunnel
                stopVpn()
                // 2. Remove the persistent notification
                stopForeground(STOP_FOREGROUND_REMOVE)
                // 3. Kill the service — nothing runs in the background after this
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // ── Normal start (from app UI or system restart) ──────────────────────
        if (!initialized) {
            initialized = true
            // Post standby notification immediately to satisfy foreground requirement
            startForeground(NOTIF_ID, buildNotification(running = false))

            // Keep rule cache in sync reactively
            serviceScope.launch {
                AppDatabase.getDatabase(this@VpnTrackerService)
                    .appDao().getAllRules().collect { rules ->
                        cachedRules = rules.filter { it.isEnabled }
                        Log.d(TAG, "Rule cache updated — ${cachedRules.size} active entries")
                    }
            }
        }

        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        vpnThread?.interrupt()
        _isRunning.value = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        initialized  = false
        super.onDestroy()
    }

    // ── VPN start / stop ──────────────────────────────────────────────────────

    private fun startVpn() {
        if (vpnThread?.isAlive == true) return

        Thread({
            try {
                if (setupVpn()) {
                    _isRunning.value = true
                    updateNotification(running = true)
                    processPackets()          // blocks until interrupted
                } else {
                    Log.w(TAG, "startVpn: no enabled apps or establish() returned null")
                    _isRunning.value = false
                    updateNotification(running = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "VPN thread error", e)
            } finally {
                _isRunning.value = false
                runCatching { vpnInterface?.close() }
                vpnInterface = null
                vpnThread    = null
                updateNotification(running = false)
            }
        }, "VpnTrackerThread").also { vpnThread = it }.start()
    }

    /**
     * Closes the TUN interface and stops the packet loop.
     * The foreground service stays alive in standby — notification switches
     * to show START so the user can re-enable without opening the app.
     */
    private fun stopVpn() {
        vpnThread?.interrupt()
        vpnThread = null
        runCatching { vpnInterface?.close() }
        vpnInterface     = null
        _isRunning.value = false
        updateNotification(running = false)
        Log.d(TAG, "VPN tunnel closed — service now in standby")
    }

    // ── VPN setup ─────────────────────────────────────────────────────────────

    private fun setupVpn(): Boolean {
        val db      = AppDatabase.getDatabase(this)
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
            catch (e: Exception) { Log.w(TAG, "Skipped unknown package: ${cfg.packageName}") }
        }

        vpnInterface = builder.establish()
        Log.d(TAG, "VPN established=${vpnInterface != null}, apps=${enabled.size}")
        return vpnInterface != null
    }

    // ── Packet loop ───────────────────────────────────────────────────────────

    private fun processPackets() {
        val fis = FileInputStream(vpnInterface!!.fileDescriptor)
        val fos = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buf = ByteArray(32768)
        val db  = AppDatabase.getDatabase(this)
        Log.d(TAG, "Packet loop started")

        while (!Thread.currentThread().isInterrupted) {
            try {
                val len = fis.read(buf)
                if (len <= 0) continue
                val pkt = buf.copyOf(len)
                serviceScope.launch { handlePacket(pkt, fos, db) }
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) break
                Log.e(TAG, "Read error", e)
            }
        }
        Log.d(TAG, "Packet loop ended")
    }

    private suspend fun handlePacket(raw: ByteArray, fos: FileOutputStream, db: AppDatabase) {
        val info    = parseDns(raw) ?: return
        val blocked = isDomainBlocked(info.domain)
        logConnection(db, info.domain, blocked)

        val resp = if (blocked) buildNxdomain(info.dnsPayload)
        else forwardDns(info.dnsPayload) ?: return

        val reply = buildIpUdpPacket(
            srcIp   = info.dstIp, srcPort = DNS_PORT,
            dstIp   = info.srcIp, dstPort = info.srcPort,
            payload = resp
        )
        writeMutex.withLock { fos.write(reply) }
    }

    // ── DNS parsing ───────────────────────────────────────────────────────────

    private data class DnsInfo(
        val domain: String, val dnsPayload: ByteArray,
        val srcIp: ByteArray, val srcPort: Int, val dstIp: ByteArray
    )

    private fun parseDns(raw: ByteArray): DnsInfo? {
        val len = raw.size
        if (len < 28) return null
        val pkt  = ByteBuffer.wrap(raw, 0, len).order(ByteOrder.BIG_ENDIAN)
        val vIhl = pkt.get(0).toInt() and 0xFF
        if (vIhl ushr 4 != 4) return null
        val ihl = (vIhl and 0x0F) * 4
        if (len < ihl + 8 || pkt.get(9).toInt() and 0xFF != 17) return null

        val srcIp = ByteArray(4).also { pkt.position(12); pkt.get(it) }
        val dstIp = ByteArray(4).also { pkt.get(it) }

        pkt.position(ihl)
        val srcPort    = pkt.short.toInt() and 0xFFFF
        val dstPort    = pkt.short.toInt() and 0xFFFF
        if (dstPort != DNS_PORT) return null
        val udpDataLen = (pkt.short.toInt() and 0xFFFF) - 8
        pkt.short                                         // skip UDP checksum
        if (udpDataLen < 12) return null

        val dns    = ByteArray(udpDataLen).also { pkt.get(it) }
        val domain = parseDnsName(dns, 12)
        if (domain.isEmpty()) return null
        return DnsInfo(domain, dns, srcIp, srcPort, dstIp)
    }

    private fun parseDnsName(dns: ByteArray, start: Int): String {
        val sb = StringBuilder(); var pos = start
        return runCatching {
            while (pos < dns.size) {
                val l = dns[pos].toInt() and 0xFF
                if (l == 0 || l and 0xC0 == 0xC0) break
                pos++
                repeat(l) { if (pos < dns.size) sb.append(dns[pos++].toInt().toChar()) }
                sb.append('.')
            }
            sb.toString().trimEnd('.')
        }.getOrDefault("")
    }

    // ── Domain matching ───────────────────────────────────────────────────────

    private fun isDomainBlocked(domain: String): Boolean {
        val lower = domain.lowercase()
        val match = cachedRules.firstOrNull { rule ->
            matchesDomain(lower, rule.pattern.lowercase(), rule.matchType)
        }
        return when {
            match != null                             -> match.action == RuleAction.BLOCK
            _filterMode.value == FilterMode.WHITELIST -> true   // default-deny
            else                                      -> false  // default-allow
        }
    }

    private fun matchesDomain(domain: String, pattern: String, type: MatchType) = when (type) {
        MatchType.EXACT     -> domain == pattern
        MatchType.SUBDOMAIN -> domain == pattern || domain.endsWith(".$pattern")
        MatchType.CONTAINS  -> domain.contains(pattern)
        MatchType.PREFIX    -> domain.startsWith(pattern)
        MatchType.SUFFIX    -> domain.endsWith(pattern)
    }

    // ── DNS forwarding ────────────────────────────────────────────────────────

    private fun forwardDns(payload: ByteArray): ByteArray? = runCatching {
        DatagramSocket().use { sock ->
            protect(sock)           // bypass tunnel — prevents infinite routing loop
            sock.soTimeout = 3_000
            sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(DNS_SERVER), DNS_PORT))
            val resp = ByteArray(4096)
            val dp   = DatagramPacket(resp, resp.size)
            sock.receive(dp)
            resp.copyOf(dp.length)
        }
    }.getOrNull().also { if (it == null) Log.w(TAG, "DNS forward failed") }

    private fun buildNxdomain(q: ByteArray) = q.copyOf().also {
        if (it.size >= 4) {
            it[2] = (it[2].toInt() or 0x80).toByte()              // QR = 1 (response)
            it[3] = ((it[3].toInt() and 0xF0) or 0x03).toByte()   // RCODE = 3 (NXDOMAIN)
        }
    }

    // ── IP / UDP packet builder ───────────────────────────────────────────────

    private fun buildIpUdpPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen  = 20 + udpLen
        val pkt = ByteBuffer.allocate(ipLen).order(ByteOrder.BIG_ENDIAN)

        pkt.put(0x45.toByte()); pkt.put(0); pkt.putShort(ipLen.toShort())
        pkt.putShort(0);        pkt.putShort(0x4000.toShort())
        pkt.put(64);            pkt.put(17)
        pkt.putShort(0);        pkt.put(srcIp); pkt.put(dstIp)
        pkt.putShort(10, checksum(pkt.array(), 0, 20).toShort())

        pkt.putShort(srcPort.toShort()); pkt.putShort(dstPort.toShort())
        pkt.putShort(udpLen.toShort());  pkt.putShort(0)    // UDP checksum = 0 (optional, RFC 768)
        pkt.put(payload)
        return pkt.array()
    }

    private fun checksum(d: ByteArray, off: Int, len: Int): Int {
        var s = 0; var i = off
        while (i < off + len - 1) {
            s += ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF); i += 2
        }
        if (len % 2 != 0) s += (d[off + len - 1].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv() and 0xFFFF
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun logConnection(db: AppDatabase, domain: String, blocked: Boolean) {
        serviceScope.launch {
            db.appDao().insertLog(
                ConnectionLog(packageName = "firewall", domain = domain, isBlocked = blocked)
            )
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    //
    //  Layout (both states):
    //
    //  [🛡 shield icon]  ANDROWALL // ACTIVE          [STOP]  [CLOSE]
    //  [🛡 shield icon]  ANDROWALL // STANDBY         [START] [CLOSE]
    //
    //  • setOngoing(true)  → swipe-to-dismiss is disabled by the OS.
    //  • Being a foreground-service notification → "Clear all" cannot remove it.
    //  • The only way to remove the notification is pressing CLOSE, which also
    //    stops the VPN tunnel and kills the background service entirely.
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildNotification(running: Boolean): Notification {
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // STOP / START toggle
        val togglePi = PendingIntent.getService(
            this,
            if (running) 0 else 1,          // distinct request codes prevent collision
            Intent(this, VpnTrackerService::class.java).apply {
                action = if (running) ACTION_STOP_VPN else ACTION_START_VPN
            },
            flag
        )

        // CLOSE — removes notification + kills service
        val closePi = PendingIntent.getService(
            this, 2,
            Intent(this, VpnTrackerService::class.java).apply { action = ACTION_CLOSE },
            flag
        )

        // Tap notification body → bring app to foreground
        val openAppPi = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            flag
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (running) "ANDROWALL  //  ACTIVE" else "ANDROWALL  //  STANDBY")
            .setContentText(
                if (running) "DNS intercept active — filtering enabled"
                else         "Engine offline — tap START to protect"
            )
            // ── Shield icon (monochrome vector in res/drawable/ic_shield.xml) ──
            .setSmallIcon(R.drawable.ic_shield)
            // ── Prevents swipe-to-dismiss; foreground service prevents Clear All ──
            .setOngoing(true)
            .setContentIntent(openAppPi)
            // Action 1: STOP or START
            .addAction(
                if (running) android.R.drawable.ic_media_pause
                else         android.R.drawable.ic_media_play,
                if (running) "STOP" else "START",
                togglePi
            )
            // Action 2: CLOSE — the only way to fully dismiss the notification
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "CLOSE",
                closePi
            )
            .build()
    }

    private fun updateNotification(running: Boolean) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, buildNotification(running))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "AndroWall Firewall", NotificationManager.IMPORTANCE_LOW)
                .also { getSystemService(NotificationManager::class.java)?.createNotificationChannel(it) }
        }
    }
}