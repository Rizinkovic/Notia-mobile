package com.notia.mobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.notia.mobile.core.IncomingNote
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.sanitize
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Wi-Fi transfer. Protocol (shared with the desktop app):
 *   POST http://<ip>:8765/notia/v1/notes   header X-Notia-Code: <4 digits shown on the receiver>
 *   body {"v":1,"notes":[{"id","title","html","category","created","modified"}]}  ->  200 {"ok":true,"count":N}
 * Desktop QR codes (token = 32 lowercase hex characters, one per session):
 *   http://<ip>:<port>/<token>/notes   "Sync notes to phone": the phone GETs it and imports {"v":1,"notes":[...]}
 *   http://<ip>:<port>/<token>/inbox   "Receive notes from phone": the phone POSTs {"v":1,"notes":[...]} to it
 *   http://<ip>:<port>/<hex>/          "Wi-Fi share" (the only server in Notia Desktop 1.2.x): GET /<hex>/ is a page listing the exported
 *                                      file(s), GET /<hex>/<n>/ downloads file n. The phone downloads and imports them (md/txt/html become
 *                                      notes, pdf/docx/epub/... go to the Library). Tokens there are 1-16 hex digits, not always 32.
 * A sync address that answers with that page (instead of JSON) is treated as a file share too.
 */
object Net {
    const val PORT = 8765
    private const val MAX_BODY = 20_000_000

    class Target(val host: String, val port: Int, val code: String)

    fun myIp(): String? {
        var fallback: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) if (a is Inet4Address && a.isSiteLocalAddress) {
                    if (ni.name.startsWith("wlan") || ni.name.startsWith("ap")) return a.hostAddress
                    if (fallback == null) fallback = a.hostAddress
                }
            }
        } catch (e: Exception) {}
        return fallback
    }

    fun payload(notes: List<Note>): String {
        val a = JSONArray()
        for (n in notes) a.put(JSONObject().put("id", n.id).put("title", n.title).put("html", n.html)
            .put("category", Store.cats.firstOrNull { it.id == n.cat }?.name ?: JSONObject.NULL).put("created", n.created).put("modified", n.modified))
        return JSONObject().put("v", 1).put("notes", a).toString()
    }

    fun parseTargets(t: String): List<Target> = t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { l ->
        val parts = l.split(Regex("\\s+"))
        val hp = parts[0].split(':')
        val host = hp[0]
        if (!Regex("^[\\w.\\-]+$").matches(host)) null else Target(host, hp.getOrNull(1)?.toIntOrNull() ?: PORT, parts.getOrElse(1) { "" })
    }

    fun clean(raw: String): List<IncomingNote> {
        val txt = raw.trim().removePrefix("\uFEFF")
        // The envelope is {"v":1,"notes":[...]}, but a bare array or "v" missing / sent as text is accepted too.
        val arr = try {
            if (txt.startsWith("[")) JSONArray(txt)
            else JSONObject(txt).let { o -> if (o.optInt("v", 1) > 1) null else (o.optJSONArray("notes") ?: o.optJSONArray("data")) }
        } catch (e: Exception) { null } ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<IncomingNote>()
        for (i in 0 until Math.min(arr.length(), 200)) {
            val n = arr.optJSONObject(i) ?: continue
            var id = n.optString("id").replace(Regex("[^\\w\\-]"), "").take(40)
            if (id.isEmpty()) id = "d" + Math.abs((n.optString("title") + n.optLong("created", 0)).hashCode())
            fun ts(k: String): Long { val v = n.optLong(k, 0); return if (v > 0) v else now }
            out.add(IncomingNote(id, n.optString("title").take(200), sanitize(n.optString("html").take(5_000_000)),
                (if (n.isNull("category")) "" else n.optString("category")).take(30), ts("created"), ts("modified")))
        }
        return out
    }

    /** HTTP status, or 0 when unreachable. Blocking. */
    fun send(ctx: Context, t: Target, body: String): Int = try {
        val c = open(ctx, URL("http://${t.host}:${t.port}/notia/v1/notes"))
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 10000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("X-Notia-Code", t.code)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        c.disconnect(); code
    } catch (e: Exception) { 0 }

    private fun privateHost(h: String): Boolean {
        if (h.endsWith(".local") || !h.contains('.')) return true
        val m = Regex("^(\\d+)\\.(\\d+)\\.\\d+\\.\\d+$").find(h) ?: return false
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /** What a scanned QR code is: "sync" (desktop gives notes), "inbox" (desktop takes notes) or "files" (desktop file share). */
    class Link(val kind: String, val url: URL)

    // Tokens are lowercase hex. Notia Desktop's Wi-Fi share makes them from a 64-bit number (1 to 16 digits), the sync codes use 32.
    private val RX_SYNC = Regex("^/[a-f0-9]{6,64}/(notes|sync)/?$")
    private val RX_INBOX = Regex("^/[a-f0-9]{6,64}/inbox/?$")
    private val RX_FILES = Regex("^/[a-f0-9]{1,64}/?$")

    /** Classifies a QR value or pasted link. Returns null unless it is a Notia link on a local network. */
    fun parseLink(s: String): Link? = try {
        val u = URL(s.trim())
        if (u.protocol != "http" || !privateHost(u.host)) null
        else when {
            RX_SYNC.matches(u.path) -> Link("sync", u)
            RX_INBOX.matches(u.path) -> Link("inbox", u)
            RX_FILES.matches(u.path) -> Link("files", u)
            else -> null
        }
    } catch (e: Exception) { null }

    /**
     * The Wi-Fi (or Ethernet) network, when there is one. On a Wi-Fi without internet Android keeps mobile data as
     * the default route, so a plain connection to 192.168.x.x would leave through the cellular network and fail.
     */
    @Suppress("DEPRECATION")
    private fun lan(ctx: Context): android.net.Network? = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    } catch (e: Exception) { null }

    private fun open(ctx: Context, u: URL, bound: Boolean): HttpURLConnection =
        ((if (bound) lan(ctx)?.openConnection(u) else null) ?: u.openConnection()) as HttpURLConnection

    private fun open(ctx: Context, u: URL): HttpURLConnection = open(ctx, u, true)

    class HttpError(val code: Int) : java.io.IOException("HTTP $code")
    /** The address answered with a web page, not notes: that is Notia Desktop's file share, not a notes sync. */
    class NotJson : java.io.IOException("not json")

    /**
     * GET with one retry on the other route: first through the Wi-Fi network itself, then through Android's default route
     * (some phones and VPN setups only work with one of the two). Returns content type and body.
     */
    private fun get(ctx: Context, u: URL, limit: Int, readMs: Int = 20000): Pair<String, ByteArray> {
        var last: Exception? = null
        val routes = if (lan(ctx) != null) booleanArrayOf(true, false) else booleanArrayOf(false)
        for (bound in routes) {
            try {
                val c = open(ctx, u, bound)
                c.connectTimeout = 8000; c.readTimeout = readMs; c.instanceFollowRedirects = true
                c.setRequestProperty("Accept", "application/json, */*"); c.setRequestProperty("User-Agent", "NotiaMobile/1.1.2")
                try {
                    val code = c.responseCode
                    if (code != 200) throw HttpError(code)
                    val bos = ByteArrayOutputStream(); val buf = ByteArray(16384)
                    c.inputStream.use { while (true) { val r = it.read(buf); if (r < 0) break; bos.write(buf, 0, r); if (bos.size() > limit) throw java.io.IOException("too large") } }
                    return (c.contentType ?: "") to bos.toByteArray()
                } finally { c.disconnect() }
            } catch (e: HttpError) { throw e } catch (e: Exception) { last = e }
        }
        throw last ?: java.io.IOException("failed")
    }

    /** Downloads the desktop's notes. Throws NotJson for a file-share page, HttpError for a bad status, IOException when unreachable. */
    fun fetchSync(ctx: Context, u: URL): List<IncomingNote> {
        val (ct, body) = get(ctx, u, MAX_BODY)
        val text = String(body, Charsets.UTF_8)
        if (ct.contains("html", true) || text.trimStart().startsWith("<")) throw NotJson()
        return clean(text)
    }

    /** Notia Desktop "Wi-Fi share": http://ip:port/<token>/ lists the exported file(s); /<token>/<n>/ downloads file n. */
    class SharedFile(val index: Int, val name: String)

    fun base(u: URL): URL = URL(u.protocol, u.host, u.port, u.path.substringBeforeLast('/').let { p -> val parts = p.split('/').filter { it.isNotEmpty() }; "/" + (parts.firstOrNull() ?: "") + "/" })

    fun sharedFiles(ctx: Context, u: URL): List<SharedFile> {
        val html = String(get(ctx, base(u), 1_000_000).second, Charsets.UTF_8)
        val out = ArrayList<SharedFile>()
        for (m in Regex("<a\\s+href=\"(\\d+)/[^\"]*\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).findAll(html)) {
            val name = com.notia.mobile.doc.HtmlModel.dec(m.groupValues[2].replace(Regex("<[^>]*>"), "")).trim()
            out.add(SharedFile(m.groupValues[1].toInt(), name.ifEmpty { "file" + m.groupValues[1] }))
        }
        return out.distinctBy { it.index }.take(10)
    }

    fun download(ctx: Context, u: URL, f: SharedFile, limit: Int = 40_000_000): ByteArray =
        get(ctx, URL(base(u), "${f.index}/"), limit, 60000).second

    /** A short, human reason for a failed request, so "sync failed" says why. */
    fun explain(e: Throwable): String = when (e) {
        is HttpError -> when (e.code) {
            404 -> L2("The desktop does not offer notes at this address (HTTP 404). Make sure the code on the desktop is still showing, or use Notia Desktop's “Wi-Fi share” and scan that code.",
                "Le bureau ne propose pas de notes à cette adresse (HTTP 404). Vérifiez que le code est toujours affiché sur le bureau, ou utilisez « Partage Wi-Fi » de Notia Desktop et scannez ce code.")
            else -> L2("The desktop answered with an error (HTTP ${e.code}).", "Le bureau a répondu par une erreur (HTTP ${e.code}).")
        }
        is java.net.SocketTimeoutException -> L2("The desktop did not answer in time. A firewall may be blocking it: allow Notia on private networks.", "Le bureau n’a pas répondu à temps. Un pare-feu le bloque peut-être : autorisez Notia sur les réseaux privés.")
        is java.net.ConnectException -> L2("Connection refused. Is the code still showing on the desktop, and are both devices on the same Wi-Fi?", "Connexion refusée. Le code est-il toujours affiché sur le bureau, et les deux appareils sont-ils sur le même Wi-Fi ?")
        is java.net.UnknownHostException, is java.net.NoRouteToHostException -> L2("The desktop's address cannot be found. Check that both devices are on the same Wi-Fi.", "L’adresse du bureau est introuvable. Vérifiez que les deux appareils sont sur le même Wi-Fi.")
        else -> L2("Could not reach the desktop (${e.message ?: e.javaClass.simpleName}).", "Impossible de joindre le bureau (${e.message ?: e.javaClass.simpleName}).")
    }

    private fun L2(en: String, fr: String) = if (Store.lang == "fr") fr else en

    /** Sends notes to the desktop's "Receive from phone" address. HTTP status, or 0 when unreachable. Blocking. */
    fun postInbox(ctx: Context, u: URL, body: String): Int = try {
        val c = open(ctx, u)
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 20000; c.doOutput = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        c.disconnect(); code
    } catch (e: Exception) { 0 }

    /** Minimal HTTP receiver. onNotes runs on a worker thread; the caller must hop to the main thread. */
    class Server(private val code: String, private val onNotes: (List<IncomingNote>) -> Int, private val onErr: (String) -> Unit) {
        @Volatile private var running = false
        private var ss: ServerSocket? = null

        fun start(): Boolean {
            try { ss = ServerSocket(PORT, 8, InetAddress.getByName("0.0.0.0")).also { it.reuseAddress = true } } catch (e: Exception) { onErr(e.message ?: "bind"); return false }
            running = true
            Thread {
                while (running) try { val s = ss!!.accept(); Thread { handle(s) }.start() } catch (e: Exception) { if (running) onErr(e.message ?: "accept"); break }
            }.start()
            return true
        }

        fun stop() { running = false; try { ss?.close() } catch (e: Exception) {} }

        private fun reply(s: Socket, status: Int, text: String, json: String) {
            val b = json.toByteArray(Charsets.UTF_8)
            s.getOutputStream().apply { write("HTTP/1.1 $status $text\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(b); flush() }
        }

        private fun handle(s: Socket) {
            try {
                s.soTimeout = 10000
                val ins = s.getInputStream()
                val head = ByteArrayOutputStream(); var m = 0
                while (head.size() < 16384) { // read until CRLFCRLF
                    val b = ins.read(); if (b < 0) return
                    head.write(b)
                    m = if (b == (if (m % 2 == 0) 13 else 10)) m + 1 else if (b == 13) 1 else 0
                    if (m == 4) break
                }
                if (m != 4) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                val h = head.toString("ISO-8859-1")
                if (!Regex("^POST /notia/v1/notes ").containsMatchIn(h)) { reply(s, 404, "Not Found", "{\"ok\":false}"); return }
                val given = Regex("x-notia-code:[ \\t]*(\\S*)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1) ?: ""
                if (!java.security.MessageDigest.isEqual(given.toByteArray(), code.toByteArray())) { reply(s, 401, "Unauthorized", "{\"ok\":false}"); return }
                val len = Regex("content-length:[ \\t]*(\\d+)", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (len <= 0 || len > MAX_BODY) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                val body = ByteArray(len.toInt()); var got = 0
                while (got < body.size) { val r = ins.read(body, got, body.size - got); if (r < 0) break; got += r }
                if (got < body.size) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                val list = clean(String(body, Charsets.UTF_8))
                if (list.isEmpty()) { reply(s, 400, "Bad Request", "{\"ok\":false}"); return }
                reply(s, 200, "OK", "{\"ok\":true,\"count\":${onNotes(list)}}")
            } catch (e: Exception) {
            } finally { try { s.close() } catch (e: Exception) {} }
        }
    }
}
