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
import android.os.SystemClock
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
 *  - a blocked domain (or any of its subdomains) gets NXDOMAIN — the check
 *    covers the user's own list AND a built-in blocklist of ~100,000 adult
 *    domains shipped in the app (assets/adult_blocklist.txt), loaded once
 *    per process on a background thread;
 *  - when the keyword block is on, a queried domain containing any keyword
 *    (case-insensitive substring) also gets NXDOMAIN — so keyword blocking
 *    works even if accessibility is off;
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

        /** Broadcast when the VPN blocks a DNS query: PowerBlockService covers the screen. */
        const val ACTION_SITE_BLOCKED = "com.powerlock.app.SITE_BLOCKED"

        /** Extra on [ACTION_SITE_BLOCKED]: the blocked domain. */
        const val EXTRA_BLOCKED_DOMAIN = "blocked_domain"

        /** Built-in adult-domain blocklist asset (one domain per line). */
        private const val BLOCKLIST_ASSET = "adult_blocklist.txt"

        /** The built-in blocklist, loaded once per process on a background thread. */
        @Volatile
        private var blocklist: Set<String> = emptySet()

        /** True once the blocklist load has been started (it runs only once per process). */
        private val blocklistLoadStarted = AtomicBoolean(false)

        /** Guards the one-time blocklist load (async and sync callers share it). */
        private val blocklistLock = Any()

        /** Cached domain count of the blocklist asset (-1 = not counted yet). */
        @Volatile
        private var blocklistCachedCount = -1

        /**
         * Number of domains in the built-in blocklist (0 when the asset is
         * missing or unreadable). Counts the asset's lines once and caches
         * the result; a fast byte scan, no full parse.
         */
        fun blocklistCount(ctx: Context): Int {
            val cached = blocklistCachedCount
            if (cached >= 0) return cached
            val n = try {
                ctx.assets.open(BLOCKLIST_ASSET).use { ins ->
                    var count = 0
                    val buf = ByteArray(8192)
                    var lastByte = -1
                    while (true) {
                        val r = ins.read(buf)
                        if (r <= 0) break
                        for (i in 0 until r) if (buf[i] == '\n'.code.toByte()) count++
                        lastByte = buf[r - 1].toInt()
                    }
                    if (lastByte != -1 && lastByte != '\n'.code) count++ // final line, no trailing newline
                    count
                }
            } catch (e: Exception) {
                Log.e(TAG, "blocklist count failed", e)
                0
            }
            blocklistCachedCount = n
            return n
        }

        /** Starts the one-time background load of the built-in blocklist. */
        private fun ensureBlocklistLoaded(ctx: Context) {
            if (!blocklistLoadStarted.compareAndSet(false, true)) return
            val appCtx = ctx.applicationContext
            val t = Thread({
                loadBlocklistSync(appCtx)
            }, "siteblock-list-load")
            t.isDaemon = true
            t.start()
        }

        /**
         * Loads the blocklist on the calling thread (blocking) and returns
         * it. Safe to call any number of times: the load runs once and later
         * calls return the cached set. Never call on the main thread.
         */
        private fun loadBlocklistSync(ctx: Context): Set<String> {
            synchronized(blocklistLock) {
                val loaded = blocklist
                if (loaded.isNotEmpty()) return loaded
                blocklistLoadStarted.set(true)
                val set = HashSet<String>(110000)
                try {
                    ctx.assets.open(BLOCKLIST_ASSET).bufferedReader().useLines { lines ->
                        for (raw in lines) {
                            val d = raw.trim().lowercase()
                            if (d.isNotEmpty() && !d.startsWith("#")) set.add(d)
                        }
                    }
                    blocklist = set
                    blocklistCachedCount = set.size
                    Log.i(TAG, "blocklist loaded: ${set.size} domains")
                } catch (e: Exception) {
                    Log.e(TAG, "blocklist load failed", e)
                }
                return blocklist
            }
        }

        /** Starts the blocklist load on a background thread (no-op once loaded/loading). */
        fun preloadBlocklist(ctx: Context) = ensureBlocklistLoaded(ctx)

        /**
         * True when [domain] (or one of its parent domains) is in the
         * built-in blocklist. Loads the list synchronously on first use, so
         * callers MUST run this off the main thread. Used by the URL
         * detection in PowerBlockService and by the duplicate check when the
         * user adds a custom domain.
         */
        fun isBlockedByBuiltInList(ctx: Context, domain: String): Boolean {
            val set = loadBlocklistSync(ctx.applicationContext)
            return suffixMatch(set, domain)
        }

        /**
         * Suffix match against a blocklist set: walks up the labels
         * (a.b.com → b.com), so a subdomain of a listed domain matches too.
         * Hash lookups only — fast even with ~100,000 entries. Never matches
         * a bare TLD, so a stray "com" in the list could not match everything.
         */
        private fun suffixMatch(set: Set<String>, domain: String): Boolean {
            if (set.isEmpty()) return false
            var d: String? = domain
            while (d != null) {
                if (set.contains(d)) return true
                val dot = d.indexOf('.')
                if (dot < 0) return false
                d = d.substring(dot + 1)
                if (!d.contains('.')) return false // bare TLD: stop, do not match
            }
            return false
        }

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

    /** Blocked keywords, reloaded on every start (the user may have edited the list). */
    @Volatile
    private var keywords: List<String> = emptyList()

    /** True when the keyword block is on (reloaded on every start). */
    @Volatile
    private var kwOn = false

    /** Throttles the blocked-site broadcast: browsers retry a failed lookup hard. */
    private var lastSiteBroadcastAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            L.load(this)
            blocked = Extra.siteDomains(this)
            keywords = Extra.siteKeywords(this)
            kwOn = Extra.siteKwOn(this)
            ensureBlocklistLoaded(this) // background thread; the VPN never waits for it
            KeywordBlock.preload(this) // built-in keywords load the same way
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
            isBlocked(q.domain, list) -> {
                onSiteBlocked(q.domain)
                buildResponse(pkt, dnsOff, q, rcode = 3, answers = emptyList())
            }
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

    /**
     * A blocked domain was asked for: log the attempt (feeds the temptation
     * counter; logAttempt itself throttles to one entry per kind per 3 s)
     * and tell PowerBlockService to cover the screen — throttled to one
     * broadcast per 3 s. The broadcast is restricted to our own package. If
     * accessibility is off there is no overlay, but the DNS answer is still
     * NXDOMAIN and the attempt is still logged.
     */
    private fun onSiteBlocked(domain: String) {
        try {
            Extra.logAttempt(this, "site")
            val now = SystemClock.uptimeMillis()
            if (now - lastSiteBroadcastAt < 3000) return
            lastSiteBroadcastAt = now
            val i = Intent(ACTION_SITE_BLOCKED)
            i.setPackage(packageName)
            i.putExtra(EXTRA_BLOCKED_DOMAIN, domain)
            sendBroadcast(i)
        } catch (e: Exception) {
            Log.e(TAG, "site blocked notify failed", e)
        }
    }

    /** Exact match or any subdomain of a blocked domain — user list or built-in blocklist — or any keyword in the domain when the keyword block is on. */
    private fun isBlocked(domain: String, list: List<String>): Boolean {
        for (d in list) {
            if (domain == d || domain.endsWith(".$d")) return true
        }
        if (kwOn && KeywordBlock.matches(this, domain, keywords)) return true
        return isInBlocklist(domain)
    }

    /** Suffix match against the built-in blocklist (see [suffixMatch]). */
    private fun isInBlocklist(domain: String): Boolean = suffixMatch(blocklist, domain)

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

/**
 * Keyword block: multi-pattern matching shared by the DNS path (this file)
 * and the URL-detection path (PowerBlockService). Two keyword sources are
 * merged into one matcher:
 *
 *  - the built-in list (~5,000 bad words, Bengali + English, shipped in
 *    assets/keyword_blocklist.txt), loaded once per process on a background
 *    thread;
 *  - the user's own list ([Extra.siteKeywords]), which they can still edit
 *    in Settings (add-only while a session runs).
 *
 * The asset carries formatting artifacts from its source lists
 * (surrounding quotes like "4r5e" and asterisks like *fuck*); [cleanKeyword]
 * strips them when loading, so the matcher, the keyword viewer, and the
 * Settings count all agree on the same cleaned, deduplicated list.
 *
 * Matching is an Aho-Corasick automaton built once from both lists: the
 * check runs on every keystroke (build 64, instant), so a naive
 * per-keyword substring scan over ~5,000 keywords per keystroke would be
 * far too slow. The automaton scans the text once, in linear time, and
 * reports the first keyword found.
 *
 * False positives: short keywords (4 characters or fewer) only match on a
 * word boundary — a non-letter/digit on both sides, or the string edge —
 * so "sex" does not match "Essex", "ass" does not match "class", and
 * "anal" does not match "canal". Longer keywords match as substrings,
 * which is what the user asked for. The boundary test uses
 * [Char.isLetterOrDigit], which is Unicode-aware, so Bengali letters
 * count as word characters too.
 *
 * The matcher is rebuilt on a background thread whenever the custom list
 * changes (the fingerprint of the list is compared on every check). Until
 * a matcher is ready, checks fall back to the previous matcher — or, on
 * the very first check, to a small matcher built from the custom list
 * alone — so the starter keywords work immediately, before the asset has
 * even finished loading.
 */
object KeywordBlock {
    /** Built-in keyword blocklist asset (one keyword per line, lowercase). */
    private const val KEYWORD_ASSET = "keyword_blocklist.txt"

    /** The built-in list, once loaded (null = not loaded yet). */
    @Volatile
    private var assetKeywords: List<String>? = null

    /** True once the asset load has been started (it runs only once per process). */
    private val assetLoadStarted = AtomicBoolean(false)

    /** The matcher currently in use (null = none built yet). */
    @Volatile
    private var matcher: Matcher? = null

    /** Fingerprint ([List.hashCode]) of the custom list the matcher was built from. */
    @Volatile
    private var matcherFingerprint = 0

    /** True when the current matcher includes the built-in asset. */
    @Volatile
    private var matcherHasAsset = false

    /** True while a matcher rebuild is running (only one at a time). */
    private val rebuildRunning = AtomicBoolean(false)

    /** Cached keyword count of the asset (-1 = not counted yet). */
    @Volatile
    private var builtInCachedCount = -1

    /** One trie node of the Aho-Corasick automaton. */
    private class Node {
        val next = HashMap<Char, Int>()
        var fail = 0
        val outputs = ArrayList<String>()
    }

    /**
     * Aho-Corasick multi-pattern matcher over [patterns] (already
     * lowercased). Built once; [findFirst] then scans any text in linear
     * time and returns the first keyword found, or null.
     */
    private class Matcher(patterns: Collection<String>) {
        private val nodes = ArrayList<Node>()

        init {
            nodes.add(Node()) // root
            for (p in patterns) {
                if (p.isEmpty()) continue
                var cur = 0
                for (c in p) {
                    val nxt = nodes[cur].next.getOrPut(c) {
                        nodes.add(Node())
                        nodes.size - 1
                    }
                    cur = nxt
                }
                nodes[cur].outputs.add(p)
            }
            // failure links, breadth-first: a node's fail link is the
            // longest proper suffix that is also a trie prefix; outputs
            // merge down the fail chain so every match is reported
            val q = ArrayDeque<Int>()
            for ((_, child) in nodes[0].next) {
                nodes[child].fail = 0
                q.add(child)
            }
            while (q.isNotEmpty()) {
                val r = q.removeFirst()
                for ((c, child) in nodes[r].next) {
                    q.add(child)
                    var f = nodes[r].fail
                    while (f != 0 && !nodes[f].next.containsKey(c)) f = nodes[f].fail
                    val failTo = nodes[f].next[c] ?: 0
                    nodes[child].fail = failTo
                    nodes[child].outputs.addAll(nodes[failTo].outputs)
                }
            }
        }

        /**
         * The first keyword found in [lower] (already lowercased), or
         * null. Keywords of 4 characters or fewer need a word boundary
         * on both sides (see the [KeywordBlock] docs).
         */
        fun findFirst(lower: String): String? {
            var state = 0
            for (i in lower.indices) {
                val c = lower[i]
                while (state != 0 && !nodes[state].next.containsKey(c)) state = nodes[state].fail
                state = nodes[state].next[c] ?: 0
                val outs = nodes[state].outputs
                if (outs.isEmpty()) continue
                for (kw in outs) {
                    if (kw.length <= 4) {
                        val start = i - kw.length + 1
                        val beforeOk = start == 0 || !lower[start - 1].isLetterOrDigit()
                        val afterOk = i + 1 >= lower.length || !lower[i + 1].isLetterOrDigit()
                        if (!beforeOk || !afterOk) continue
                    }
                    return kw
                }
            }
            return null
        }
    }

    /** Starts the one-time background load of the built-in keyword asset. */
    fun preload(ctx: Context) = ensureAssetLoaded(ctx)

    /**
     * Cleans one raw line from the built-in keyword asset: trimmed,
     * lowercased, with the formatting artifacts the source lists
     * carried stripped off — surrounding quotes ("4r5e") and asterisks
     * (*fuck*, arse*) — so the entry actually matches instead of being
     * dead weight (as-is, "*fuck*" would only match the literal string
     * with asterisks). Returns "" for blank/comment lines and for
     * anything shorter than 3 characters after cleaning: a 1–2 letter
     * keyword would match whole country domains like .ge, so those
     * stay out. Shared by the matcher loader, the count, and the
     * keyword viewer, so all three agree on the list.
     */
    fun cleanKeyword(raw: String): String {
        var w = raw.trim().lowercase()
        if (w.isEmpty() || w.startsWith("#")) return ""
        while (w.isNotEmpty() && (w.first() == '"' || w.first() == '\'' || w.first() == '*' || w.first() == '`')) w = w.drop(1)
        while (w.isNotEmpty() && (w.last() == '"' || w.last() == '\'' || w.last() == '*' || w.last() == '`')) w = w.dropLast(1)
        w = w.trim()
        if (w.length < 3) return ""
        return w
    }

    private fun ensureAssetLoaded(ctx: Context) {
        if (!assetLoadStarted.compareAndSet(false, true)) return
        val appCtx = ctx.applicationContext
        val t = Thread({
            try {
                val seen = LinkedHashSet<String>()
                appCtx.assets.open(KEYWORD_ASSET).bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val w = cleanKeyword(raw)
                        if (w.isNotEmpty()) seen.add(w)
                    }
                }
                val list = seen.toList()
                assetKeywords = list
                Log.i(TAG, "keyword blocklist loaded: ${list.size} keywords")
                // build the matcher now, so the first check after a lock
                // start is already covered by the full list
                val custom = Extra.siteKeywords(appCtx)
                triggerRebuild(custom, custom.hashCode())
            } catch (e: Exception) {
                Log.e(TAG, "keyword blocklist load failed", e)
            }
        }, "keyword-list-load")
        t.isDaemon = true
        t.start()
    }

    /**
     * Rebuilds the matcher on a background thread from the asset (if
     * loaded) plus [customKeywords]. No-op while a rebuild is already
     * running — the next check re-triggers it if the list changed again.
     */
    private fun triggerRebuild(customKeywords: List<String>, fingerprint: Int) {
        if (!rebuildRunning.compareAndSet(false, true)) return
        val t = Thread({
            try {
                val asset = assetKeywords
                val patterns = HashSet<String>()
                if (asset != null) patterns.addAll(asset)
                patterns.addAll(customKeywords)
                matcher = Matcher(patterns)
                matcherHasAsset = asset != null
                matcherFingerprint = fingerprint
                Log.i(TAG, "keyword matcher built: ${patterns.size} patterns (asset=${asset != null})")
            } catch (e: Exception) {
                Log.e(TAG, "keyword matcher build failed", e)
            } finally {
                rebuildRunning.set(false)
            }
        }, "keyword-matcher-build")
        t.isDaemon = true
        t.start()
    }

    /**
     * The first keyword found in [text] (built-in list + [customKeywords]),
     * or null. [customKeywords] must be lowercase (see
     * [Extra.normalizeKeyword]); only the text is lowercased here. Safe
     * to call on every keystroke: the automaton scan is linear in the
     * text, and the matcher is built on a background thread.
     */
    fun findMatch(ctx: Context, text: String, customKeywords: List<String>): String? {
        if (text.isEmpty()) return null
        ensureAssetLoaded(ctx)
        val fp = customKeywords.hashCode()
        val assetLoaded = assetKeywords != null
        val m = matcher
        if (m != null && matcherFingerprint == fp && matcherHasAsset == assetLoaded) {
            return m.findFirst(text.lowercase())
        }
        // matcher missing or stale: rebuild in the background, and use
        // what we have right now — the old matcher (stale custom list,
        // still blocks the asset words), or a small matcher built from
        // the custom list alone on the very first check
        triggerRebuild(customKeywords, fp)
        val use = m ?: Matcher(customKeywords)
        return use.findFirst(text.lowercase())
    }

    fun matches(ctx: Context, text: String, customKeywords: List<String>): Boolean =
        findMatch(ctx, text, customKeywords) != null

    /**
     * Number of keywords in the built-in asset (0 when the asset is
     * missing or unreadable). Counts cleaned, deduplicated keywords
     * ([cleanKeyword]) once and caches the result, so the Settings
     * count is the true total the matcher and the viewer use.
     */
    fun builtInCount(ctx: Context): Int {
        val cached = builtInCachedCount
        if (cached >= 0) return cached
        val n = try {
            val seen = HashSet<String>()
            ctx.assets.open(KEYWORD_ASSET).bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val w = cleanKeyword(raw)
                    if (w.isNotEmpty()) seen.add(w)
                }
            }
            seen.size
        } catch (e: Exception) {
            Log.e(TAG, "keyword count failed", e)
            0
        }
        builtInCachedCount = n
        return n
    }
}
