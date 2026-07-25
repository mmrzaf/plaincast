package com.plaincast.app.web

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BrowserHttpServer(
    context: Context,
    private val port: Int,
    private val onStarted: () -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val assetCache = ConcurrentHashMap<String, ByteArray>()
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlainCastBrowserHttpAccept")
    }
    private val clientExecutor: ExecutorService = ThreadPoolExecutor(
        MAX_CLIENT_THREADS,
        MAX_CLIENT_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CLIENTS),
        { runnable -> Thread(runnable, "PlainCastBrowserHttpClient") },
        ThreadPoolExecutor.AbortPolicy(),
    )
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        check(!closed.get()) { "Browser server is closed." }
        check(running.compareAndSet(false, true)) { "Browser server is already running." }
        acceptExecutor.execute {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port), ACCEPT_BACKLOG)
                }
                serverSocket = socket
                onStarted()
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (error: SocketException) {
                        if (running.get()) throw error else break
                    }
                    client.soTimeout = CLIENT_TIMEOUT_MS
                    client.tcpNoDelay = true
                    try {
                        clientExecutor.execute { handle(client) }
                    } catch (_: RejectedExecutionException) {
                        runCatching { client.close() }
                    }
                }
            } catch (error: Throwable) {
                if (running.getAndSet(false)) {
                    onError("Could not start browser access on port $port: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { serverSocket?.close() }
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
        runCatching { acceptExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        runCatching { clientExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        assetCache.clear()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = readRequest(input) ?: return
            if (request.method != "GET" && request.method != "HEAD") {
                writeResponse(output, 405, "Method Not Allowed", "text/plain; charset=utf-8", "GET only".toByteArray(), request.method == "HEAD")
                return
            }
            val asset = BrowserAssetRouter.resolve(request.path)
            if (asset == null) {
                writeResponse(output, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray(), request.method == "HEAD")
                return
            }
            val bytes = runCatching {
                assetCache.computeIfAbsent(asset.file) { file ->
                    appContext.assets.open("browser/$file").use { it.readBytes() }
                }
            }.getOrElse { error ->
                    Log.e(TAG, "Missing browser asset ${asset.file}", error)
                    writeResponse(output, 500, "Internal Server Error", "text/plain; charset=utf-8", "Browser app unavailable".toByteArray(), request.method == "HEAD")
                    return
                }
            writeResponse(output, 200, "OK", asset.contentType, bytes, request.method == "HEAD")
        }
    }

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val bytes = ByteArray(MAX_HEADER_BYTES)
        var count = 0
        var matched = 0
        while (count < bytes.size) {
            val value = input.read()
            if (value < 0) return null
            bytes[count++] = value.toByte()
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        if (matched != 4) return null
        val raw = String(bytes, 0, count, StandardCharsets.US_ASCII)
        val first = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val target = parts[1].substringBefore('?')
        if (!target.startsWith('/') || target.contains("..") || target.contains('\\') || target.length > MAX_PATH_BYTES) return null
        return HttpRequest(parts[0], target)
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
    ) {
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Permissions-Policy: camera=(self), microphone=(self), display-capture=(self), geolocation=()\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src ws: wss:; media-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n")
            append("Cross-Origin-Resource-Policy: same-origin\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        if (!headOnly) output.write(body)
        output.flush()
    }

    private data class HttpRequest(val method: String, val path: String)

    companion object {
        private const val TAG = "BrowserHttpServer"
        private const val ACCEPT_BACKLOG = 8
        private const val MAX_CLIENT_THREADS = 4
        private const val MAX_QUEUED_CLIENTS = 8
        private const val CLIENT_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_PATH_BYTES = 512
    }
}

data class BrowserAsset(val file: String, val contentType: String)

object BrowserAssetRouter {
    private val joinPath = Regex("^/join/[A-Z2-9]{4}/?$", RegexOption.IGNORE_CASE)

    fun resolve(path: String): BrowserAsset? = when {
        path == "/" || joinPath.matches(path) -> BrowserAsset("index.html", "text/html; charset=utf-8")
        path == "/app.js" -> BrowserAsset("app.js", "text/javascript; charset=utf-8")
        path == "/styles.css" -> BrowserAsset("styles.css", "text/css; charset=utf-8")
        path == "/audio-worklet.js" -> BrowserAsset("audio-worklet.js", "text/javascript; charset=utf-8")
        path == "/manifest.webmanifest" -> BrowserAsset("manifest.webmanifest", "application/manifest+json; charset=utf-8")
        path == "/favicon.png" -> BrowserAsset("favicon.png", "image/png")
        path == "/icon-192.png" -> BrowserAsset("icon-192.png", "image/png")
        path == "/icon-512.png" -> BrowserAsset("icon-512.png", "image/png")
        else -> null
    }
}
