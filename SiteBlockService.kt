package com.powerlock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Request code for the system VPN-consent dialog (handled in MainActivity). */
const val REQ_VPN_CONSENT = 1001

/**
 * Site block: a DNS-only VPN.
 *
 * It NEVER sees HTTPS or any other app traffic: the tunnel has NO default
 * route, so only packets addressed to the virtual DNS server (10.0.0.1) ever
 * enter it — and those are DNS queries, nothing else. Every other packet
 * keeps flowing over the normal network untouched.
 *
 * For each DNS question:
 *  - a blocked domain (or any of its subdomains) gets NXDOMAIN;
 *  - Google / Bing / DuckDuckGo / YouTube get their SafeSearch / restricted
 *    addresses, so search results stay filtered;
 *  - anything else is forwarded to an upstream resolver (8.8.8.8, then
 *    1.1.1.1) and the answer is relayed back unchanged.
 *
 * The VPN runs ONLY while a lock/session is active: [sync] starts it when a
 * session starts (if the site block is enabled) and stops it when the session
 * ends. It never auto-restarts on boot or service restart without an active
 * lock, because [sync] always checks [Store.isActive] first.
 *
 * Android 14+ note: there is no dedicated VPN foreground-service type, so
 * this service runs as specialUse (declared in the manifest) and a failed
 * startForeground is caught and logged instead of crashing.
 */
class SiteBlockService : VpnService() {

    companion object {
        /** True while the tunnel is up (used by the Settings card). */
        @Volatile
        var isRunning = false
            private set

        /** Virtual DNS server: the only address routed into the tunnel. */
        private const val DNS_IP = "10.0.0.1"

        /** Upstream resolvers used to answer allowed queries. */
        private const val UPSTREAM_1 = "8.8.8.8"
        private const val UPSTREAM_2 = "1.1.1.1"
        private const val UPSTREAM_TIMEOUT_MS = 3000

        private const val NOTIF_ID = 3
        private const val CHANNEL_ID = "powerlock_vpn"

        /** True when the user has already granted the VPN permission. */
        fun vpnGranted(ctx: Context): Boolean {
            return try {
                prepare(ctx) == null
            } catch (e: Exception) {
                false
            }
        }

        /** The system consent intent, or null when already granted. */
        fun prepareIntent(ctx: Context): Intent? {
            return try {
                prepare(ctx)
            } catch (e: Exception) {
                Log.e(TAG, "vpn prepare failed", e)
                null
            }
        }

        /**
         * Start the VPN if the site block is on AND a lock is running AND the
         * VPN permission was granted; otherwise make sure it is stopped.
         * Safe to call from anywhere, any number of times.
         */
        fun sync(ctx: Context) {
            try {
                val want = Extra.siteOn(ctx) && Store.isActive(ctx) && vpnGranted(ctx)
                if (want) {
                    ctx.startForegroundService(Intent(ctx, SiteBlockService::class.java))
                } else {
                    ctx.stopService(Intent(ctx, SiteBlockService::class.java))
                }
            } catch (e: Exception) {
                Log.e(TAG, "site block sync failed", e)
            }
        }

        /** Stop the VPN if it is running. Harmless when it is not. */
        fun stopIfRunning(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, SiteBlockService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "site block stop failed", e)
            }
        }
    }

    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    @Volatile
    private var upstreamSock: DatagramSocket? = null

    /** Blocked domains, reloaded on every start (the user may have edited the list). */
    @Volatile
    private var blocked: List<String> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            L.load(this)
            blocked = Extra.siteDomains(this)
            if (running.get()) return START_STICKY // already up: the list is refreshed, nothing else to do
            if (!Extra.siteOn(this) || !Store.isActive(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            startAsForeground()
            if (!establishTunnel()) {
                stopSelf()
                return START_NOT_STICKY
            }
            running.set(true)
            isRunning = true
            val t = Thread({ runLoop() }, "siteblock-dns")
            t.isDaemon = true
            t.start()
            worker = t
        } catch (e: Exception) {
            Log.e(TAG, "SiteBlockService start failed", e)
            stopSelf()
        }
        return START_STICKY
    }

    /** Foreground with the specialUse type; a failure stops the service instead of crashing. */
    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, L.t("Site block", "সাইট ব্লক"), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle(L.t("Site block is on", "সাইট ব্লক চালু আছে"))
            .setContentText(L.t("Blocked sites cannot open while the lock runs", "লক চলাকালীন ব্লক করা সাইট খুলবে না"))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // background-start restriction (e.g. a scheduled lock on Android 12+):
            // log it and stop instead of crashing — the lock itself still works
            Log.e(TAG, "site block foreground failed", e)
            throw e
        }
    }

    /**
     * DNS-only tunnel: no default route, so the only traffic that ever enters
     * the interface is DNS queries for the virtual server 10.0.0.1.
     */
    private fun establishTunnel(): Boolean {
        return try {
            val b = Builder()
            b.setSession("PowerLock 2 site block")
            b.addAddress("10.0.0.2", 24)
            b.addDnsServer(DNS_IP)
            b.addRoute(DNS_IP, 32)
            b.setMtu(1500)
            val fd = b.establish()
            if (fd == null) {
                Log.e(TAG, "site block: establish() returned null")
                return false
            }
            tun = fd
            true
        } catch (e: Exception) {
            Log.e(TAG, "site block establish failed", e)
            false
        }
    }

    private fun runLoop() {
        val fd = tun ?: return
        val input: FileInputStream
        val output: FileOutputStream
        try {
            input = FileInputStream(fd.fileDescriptor)
            output = FileOutputStream(fd.fileDescriptor)
        } catch (e: Exception) {
            Log.e(TAG, "site block stream open failed", e)
            return
        }
        // DNS over UDP can legally carry up to 64 KiB (EDNS); a 4 KiB buffer
        // would truncate large answers and break lookups.
        val buf = ByteArray(65535)
        val sock = try {
            DatagramSocket()
        } catch (e: Exception) {
            Log.e(TAG, "site block socket failed", e)
            return
        }
        upstreamSock = sock
        try {
            sock.soTimeout = UPSTREAM_TIMEOUT_MS
        } catch (e: Exception) {
            Log.e(TAG, "site block timeout failed", e)
        }
        while (running.get()) {
            val len = try {
                input.read(buf)
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "site block read failed", e)
                break
            }
            if (len <= 0) continue
            try {
                val resp = handlePacket(buf, len, sock)
                if (resp != null) output.write(resp)
            } catch (e: Exception) {
                Log.e(TAG, "site block packet failed", e)
            }
        }
        try {
            sock.close()
        } catch (e: Exception) {
            // ignore: shutting down
        }
    }

    // ---------- packet handling ----------

    /** One DNS question: the asked name, its type, and where the question section ends. */
    private data class Question(val domain: String, val qtype: Int, val qEnd: Int)

    private fun handlePacket(pkt: ByteArray, len: Int, sock: DatagramSocket): ByteArray? {
        if (len < 20) return null
        if ((pkt[0].toInt() shr 4) != 4) return null // IPv4 only
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((pkt[9].toInt() and 0xFF) != 17) return null // UDP only
        val totalLen = u16(pkt, 2)
        val ipLen = if (totalLen in ihl..len) totalLen else len
        val udpOff = ihl
        val srcPort = u16(pkt, udpOff)
        val dstPort = u16(pkt, udpOff + 2)
        if (dstPort != 53) return null // DNS queries only
        val udpLen = u16(pkt, udpOff + 4)
        val dnsLen = if (udpLen >= 8 && udpOff + udpLen <= ipLen) udpLen - 8 else ipLen - udpOff - 8
        if (dnsLen < 12) return null
        val dnsOff = udpOff + 8
        val q = parseQuestion(pkt, dnsOff, dnsLen) ?: run {
            // unparsable question (e.g. compressed QNAME): forward untouched
            val ans = forwardUpstream(pkt, dnsOff, dnsLen, sock) ?: return null
            return wrapResponse(pkt, ihl, srcPort, dstPort, ans)
        }
        val list = blocked
        val answer: ByteArray? = when {
            isBlocked(q.domain, list) -> buildResponse(pkt, dnsOff, q, rcode = 3, answers = emptyList())
            else -> {
                val safeIp = safeSearchIp(q.domain)
                when {
                    safeIp != null && q.qtype == 1 ->
                        buildResponse(pkt, dnsOff, q, rcode = 0, answers = listOf(aRecord(safeIp)))
                    safeIp != null && q.qtype == 28 ->
                        // no IPv6 SafeSearch address: answer "no such record"
                        buildResponse(pkt, dnsOff, q, rcode = 0, answers = emptyList())
                    else -> forwardUpstream(pkt, dnsOff, dnsLen, sock)
                }
            }
        }
        if (answer == null) {
            // upstream unreachable: SERVFAIL so the client can retry
            val fail = buildResponse(pkt, dnsOff, q, rcode = 2, answers = emptyList())
            return wrapResponse(pkt, ihl, srcPort, dstPort, fail)
        }
        return wrapResponse(pkt, ihl, srcPort, dstPort, answer)
    }

    /** Exact match or any subdomain of a blocked domain. */
    private fun isBlocked(domain: String, list: List<String>): Boolean {
        for (d in list) {
            if (domain == d || domain.endsWith(".$d")) return true
        }
        return false
    }

    /**
     * SafeSearch / restricted-mode addresses. A query for one of these search
     * domains is answered with the provider's filtering address instead of
     * the real one: Google 216.239.38.120 (forces SafeSearch), Bing
     * 204.79.197.220 (strict), DuckDuckGo 40.89.244.232 (safe),
     * YouTube 216.239.38.119 (restricted mode).
     */
    private fun safeSearchIp(domain: String): String? {
        return when {
            domain == "google.com" || domain.endsWith(".google.com") -> "216.239.38.120"
            domain == "bing.com" || domain.endsWith(".bing.com") -> "204.79.197.220"
            domain == "duckduckgo.com" || domain.endsWith(".duckduckgo.com") -> "40.89.244.232"
            domain == "youtube.com" || domain.endsWith(".youtube.com") -> "216.239.38.119"
            else -> null
        }
    }

    /** Reads the question section. Returns null when it cannot be parsed safely. */
    private fun parseQuestion(pkt: ByteArray, off: Int, len: Int): Question? {
        if (len < 12) return null
        val qdCount = u16(pkt, off + 4)
        if (qdCount < 1) return null
        var pos = off + 12
        val end = off + len
        val labels = ArrayList<String>()
        while (pos < end) {
            val l = pkt[pos].toInt() and 0xFF
            if (l == 0) {
                pos++
                break
            }
            if (l and 0xC0 == 0xC0) return null // compression pointer: not expected in a question
            if (l > 63 || pos + 1 + l > end) return null
            try {
                labels.add(String(pkt, pos + 1, l, Charsets.US_ASCII))
            } catch (e: Exception) {
                return null
            }
            pos += 1 + l
        }
        if (labels.isEmpty() || pos + 4 > end) return null
        val qtype = u16(pkt, pos)
        val domain = labels.joinToString(".").lowercase().trimEnd('.')
        if (domain.isEmpty()) return null
        return Question(domain, qtype, pos + 4 - off)
    }

    /** Sends the raw query to an upstream resolver and returns the raw answer, or null. */
    private fun forwardUpstream(pkt: ByteArray, off: Int, len: Int, sock: DatagramSocket): ByteArray? {
        val query = pkt.copyOfRange(off, off + len)
        val recvBuf = ByteArray(65535)
        for (host in listOf(UPSTREAM_1, UPSTREAM_2)) {
            try {
                val addr = InetAddress.getByName(host)
                sock.send(DatagramPacket(query, query.size, addr, 53))
                val recv = DatagramPacket(recvBuf, recvBuf.size)
                sock.receive(recv)
                if (recv.length > 0) return recvBuf.copyOfRange(0, recv.length)
            } catch (e: Exception) {
                // timeout or network error: try the next upstream
                Log.e(TAG, "site block upstream $host failed", e)
            }
        }
        return null
    }

    /** Builds a DNS response: same ID and question, [rcode], and the given answer records. */
    private fun buildResponse(pkt: ByteArray, off: Int, q: Question, rcode: Int, answers: List<ByteArray>): ByteArray {
        // q.qEnd counts from the DNS header start, so the question section is qEnd - 12 bytes
        val qLen = q.qEnd - 12
        var total = 12 + qLen
        for (a in answers) total += a.size
        val out = ByteArray(total)
        // header: copy the ID, set QR + RA, keep the query's RD bit
        out[0] = pkt[off]
        out[1] = pkt[off + 1]
        val qFlags = u16(pkt, off + 2)
        putU16(out, 2, 0x8000 or 0x0080 or (qFlags and 0x0100) or (rcode and 0x0F))
        putU16(out, 4, 1) // QDCOUNT
        putU16(out, 6, answers.size) // ANCOUNT
        putU16(out, 8, 0)
        putU16(out, 10, 0)
        // question, copied verbatim
        System.arraycopy(pkt, off + 12, out, 12, qLen)
        var pos = 12 + qLen
        for (a in answers) {
            System.arraycopy(a, 0, out, pos, a.size)
            pos += a.size
        }
        return out
    }

    /** One A record answering the question (name = pointer to the question name, TTL 5 min). */
    private fun aRecord(ip: String): ByteArray {
        val parts = ip.split(".").map { it.toIntOrNull() ?: 0 }
        val out = ByteArray(16)
        out[0] = 0xC0.toByte()
        out[1] = 0x0C.toByte() // pointer to offset 12: the question name
        putU16(out, 2, 1) // TYPE A
        putU16(out, 4, 1) // CLASS IN
        putU32(out, 6, 300) // TTL 300 s
        putU16(out, 10, 4) // RDLENGTH
        for (i in 0 until 4) out[12 + i] = (parts.getOrElse(i) { 0 } and 0xFF).toByte()
        return out
    }

    /** Wraps a DNS payload in a UDP/IPv4 packet addressed back to the asker. */
    private fun wrapResponse(pkt: ByteArray, ihl: Int, srcPort: Int, dstPort: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val total = 20 + udpLen
        val out = ByteArray(total)
        // IPv4 header (no options)
        out[0] = 0x45
        out[1] = 0
        putU16(out, 2, total)
        putU16(out, 4, (ipId++ and 0xFFFF))
        putU16(out, 6, 0x4000) // don't fragment
        out[8] = 64 // TTL
        out[9] = 17 // UDP
        // source = the address the query was sent to, dest = the asker
        System.arraycopy(pkt, 16, out, 12, 4)
        System.arraycopy(pkt, 12, out, 16, 4)
        putU16(out, 10, ipChecksum(out, 20))
        // UDP header: ports swapped, checksum computed over the pseudo-header
        putU16(out, 20, dstPort)
        putU16(out, 22, srcPort)
        putU16(out, 24, udpLen)
        putU16(out, 26, 0)
        System.arraycopy(dns, 0, out, 28, dns.size)
        putU16(out, 26, udpChecksum(out, 12, 16, 20, udpLen))
        return out
    }

    private var ipId = 1

    // ---------- byte helpers ----------

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v shr 24) and 0xFF).toByte()
        b[off + 1] = ((v shr 16) and 0xFF).toByte()
        b[off + 2] = ((v shr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun ipChecksum(header: ByteArray, len: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < len) {
            sum += u16(header, i).toLong()
            i += 2
        }
        if (i < len) sum += (header[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** UDP checksum over the IPv4 pseudo-header + UDP segment (0 is sent as 0xFFFF). */
    private fun udpChecksum(pkt: ByteArray, srcOff: Int, dstOff: Int, udpOff: Int, udpLen: Int): Int {
        var sum = 0L
        // pseudo-header: source + dest addresses, zero + protocol, UDP length
        sum += u16(pkt, srcOff).toLong()
        sum += u16(pkt, srcOff + 2).toLong()
        sum += u16(pkt, dstOff).toLong()
        sum += u16(pkt, dstOff + 2).toLong()
        sum += 17L
        sum += udpLen.toLong()
        var i = 0
        while (i + 1 < udpLen) {
            sum += u16(pkt, udpOff + i).toLong()
            i += 2
        }
        if (i < udpLen) sum += (pkt[udpOff + i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = (sum.inv() and 0xFFFF).toInt()
        return if (c == 0) 0xFFFF else c
    }

    override fun onRevoke() {
        // the system (or the user, or another VPN app) took the tunnel away
        Log.e(TAG, "site block revoked")
        try {
            stopSelf()
        } catch (e: Exception) {
            // ignore: shutting down
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        try {
            upstreamSock?.close()
        } catch (e: Exception) {
            // ignore: shutting down
        }
        upstreamSock = null
        try {
            tun?.close() // unblocks the reader thread
        } catch (e: Exception) {
            // ignore: shutting down
        }
        tun = null
        try {
            worker?.interrupt()
        } catch (e: Exception) {
            // ignore: shutting down
        }
        worker = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore: shutting down
        }
        super.onDestroy()
    }
}
