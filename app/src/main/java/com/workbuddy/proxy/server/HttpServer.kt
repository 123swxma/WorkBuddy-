package com.workbuddy.proxy.server

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP/1.1 服务器。
 *
 * 为什么不用现成框架：手机端只需要「几条路由 + 一条 SSE 长连接」，一个
 * ServerSocket 就够了；不引第三方库让 APK 保持在几百 KB，也让构建不依赖
 * 外网仓库。取舍是只支持 HTTP/1.1 的最小子集（Content-Length / chunked 请求体，
 * 响应一律 Connection: close），这对 OpenAI / Anthropic 客户端完全够用。
 */
class HttpServer(
    private val port: Int,
    private val bindLan: Boolean,
    private val handler: (HttpReq) -> Unit
) {

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var pool: java.util.concurrent.ExecutorService? = null
    @Volatile var activePort: Int = 0
        private set
    @Volatile var lastError: String = ""
        private set

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        return try {
            val addr = if (bindLan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(addr, port), 64)
            serverSocket = ss
            activePort = ss.localPort
            pool = Executors.newCachedThreadPool { r ->
                Thread(r, "wb-http").apply { isDaemon = true }
            }
            running = true
            lastError = ""
            Thread({ acceptLoop(ss) }, "wb-accept").apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            running = false
            false
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { pool?.shutdownNow() }
        pool = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Thread.sleep(50)
                continue
            }
            runCatching {
                pool?.submit { handle(socket) } ?: socket.close()
            }.onFailure { runCatching { socket.close() } }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                // 同名头按 RFC 合并
                headers[name] = if (headers.containsKey(name)) headers[name] + ", " + value else value
            }

            val body = readBody(input, headers, method)
            val path = target.substringBefore('?')
            val queryString = target.substringAfter('?', "")
            val query = parseQuery(queryString)

            val req = HttpReq(
                method = method,
                path = path,
                query = query,
                headers = headers,
                body = body,
                remoteIp = runCatching { socket.inetAddress?.hostAddress ?: "" }.getOrDefault(""),
                output = output
            )
            handler(req)
            req.finishIfNeeded()
        } catch (e: IOException) {
            // 客户端提前断开（SSE 取消）属于常态，不记日志
        } catch (e: Exception) {
            com.workbuddy.proxy.core.Logs.error("server: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>, method: String): ByteArray {
        if (method == "GET" || method == "HEAD") return ByteArray(0)
        val te = headers["transfer-encoding"] ?: ""
        if (te.contains("chunked", ignoreCase = true)) {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input) ?: break
                val size = sizeLine.trim().substringBefore(';').toIntOrNull(16) ?: break
                if (size == 0) {
                    readLine(input)
                    break
                }
                val buf = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(buf, read, size - read)
                    if (n < 0) break
                    read += n
                }
                out.write(buf, 0, read)
                readLine(input) // 行尾 CRLF
            }
            return out.toByteArray()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return if (read == len) buf else buf.copyOf(read)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        var c = input.read()
        if (c == -1) return null
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16_384) break
            c = input.read()
        }
        return sb.toString()
    }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in qs.split('&')) {
            if (pair.isBlank()) continue
            val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            out[k] = v
        }
        return out
    }
}

/** 一次请求/响应。响应只能写一次，写完打标记。 */
class HttpReq(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String,
    private val output: OutputStream
) {

    private val sent = AtomicBoolean(false)
    private var sseWriter: ChunkedWriter? = null

    val bodyText: String get() = String(body, Charsets.UTF_8)

    fun header(name: String): String? = headers[name.lowercase()]

    fun bearerToken(): String {
        val auth = header("authorization") ?: ""
        return if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
    }

    fun isLocalClient(): Boolean =
        remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp == "::ffff:127.0.0.1"

    fun sendJson(code: Int, json: String) =
        sendText(code, json, "application/json; charset=utf-8")

    fun sendError(code: Int, message: String) {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        sendJson(code, """{"error":{"message":"$escaped","type":"proxy_error","code":$code}}""")
    }

    fun sendEmpty(code: Int) {
        if (!sent.compareAndSet(false, true)) return
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray())
            output.flush()
        }
    }

    fun sendText(code: Int, text: String, contentType: String = "text/plain; charset=utf-8") {
        if (!sent.compareAndSet(false, true)) return
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray())
            output.write(bytes)
            output.flush()
        }
    }

    fun sendBytes(code: Int, bytes: ByteArray, contentType: String) {
        if (!sent.compareAndSet(false, true)) return
        val head = buildString {
            append("HTTP/1.1 $code ${reason(code)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray())
            output.write(bytes)
            output.flush()
        }
    }

    /** 开一条 chunked 流（SSE 用）。 */
    fun startStream(contentType: String, extraHeaders: Map<String, String> = emptyMap()): ChunkedWriter {
        if (!sent.compareAndSet(false, true)) {
            return ChunkedWriter(NoOpStream)
        }
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            for ((k, v) in extraHeaders) append("$k: $v\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray())
        output.flush()
        val w = ChunkedWriter(output)
        sseWriter = w
        return w
    }

    fun finishIfNeeded() {
        runCatching { sseWriter?.end() }
        runCatching { output.flush() }
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "OK"
    }
}

/** HTTP chunked 写入器。每次 write 立即 flush，保证 SSE 逐字到达客户端。 */
class ChunkedWriter(private val out: OutputStream) {
    @Volatile private var closed = false

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        val header = (Integer.toHexString(bytes.size) + "\r\n").toByteArray(Charsets.US_ASCII)
        out.write(header)
        out.write(bytes)
        out.write(CRLF)
        out.flush()
    }

    fun end() {
        if (closed) return
        closed = true
        runCatching {
            out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
    }
}

/** API 24 上还没有 OutputStream.nullOutputStream()，自带一个丢弃器。 */
private object NoOpStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
