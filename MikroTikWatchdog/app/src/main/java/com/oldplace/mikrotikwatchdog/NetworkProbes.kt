package com.oldplace.mikrotikwatchdog

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object HostParser {
    data class Endpoint(val host: String, val port: Int = 8729)
    fun parse(input: String): Endpoint {
        val value = input.trim()
        require(value.isNotBlank()) { "Enter a remote IP address or DNS name." }
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            require(end > 1) { "Invalid bracketed IPv6 address." }
            val host = value.substring(1, end)
            val rest = value.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.drop(1).toInt() else 8729
            require(port in 1..65535) { "Port must be between 1 and 65535." }
            return Endpoint(host, port)
        }
        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            val p = value.substringAfterLast(':').toIntOrNull()
            if (p != null) {
                require(p in 1..65535) { "Port must be between 1 and 65535." }
                return Endpoint(value.substringBeforeLast(':'), p)
            }
        }
        return Endpoint(value, 8729)
    }
}

object PingProbe {
    data class Result(val reachable: Boolean, val latencyMs: Long?)
    fun ping(host: String): Result {
        val start = System.nanoTime()
        try {
            val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "2", host)
                .redirectErrorStream(true).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return Result(false, null) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val match = Regex("time[=<]\\s*([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE).find(output)
            if (process.exitValue() == 0 || output.contains("bytes from", true)) {
                val ms = match?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.toLong()
                    ?: TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                return Result(true, ms)
            }
        } catch (_: Exception) { }
        // Fallback: Android may block the ping executable on some builds/networks.
        return try {
            val address = InetAddress.getByName(host)
            val ok = address.isReachable(2000)
            Result(ok, if (ok) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) else null)
        } catch (_: Exception) { Result(false, null) }
    }
}

object RouterOsApi {
    /** Performs a TLS RouterOS API login on the supplied API-SSL port (default 8729). */
    fun checkLogin(host: String, port: Int, username: String, password: String): Boolean {
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        socket.tcpNoDelay = true
        socket.soTimeout = 5000
        try {
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.startHandshake() // Uses Android's normal certificate validation; no trust-all bypass.
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            writeSentence(output, listOf("/login", "=name=$username", "=password=$password"))
            repeat(10) {
                val sentence = readSentence(input) ?: return false
                val first = sentence.firstOrNull() ?: return@repeat
                if (first == "!done") return true
                if (first == "!trap" || first == "!fatal") return false
            }
            return false
        } finally { try { socket.close() } catch (_: Exception) { } }
    }

    private fun writeSentence(out: BufferedOutputStream, words: List<String>) {
        words.forEach { word ->
            val bytes = word.toByteArray(Charsets.UTF_8)
            writeLength(out, bytes.size)
            out.write(bytes)
        }
        out.write(0); out.flush()
    }

    private fun readSentence(input: BufferedInputStream): List<String>? {
        val words = mutableListOf<String>()
        while (true) {
            val length = readLength(input) ?: return null
            if (length == 0) return words
            require(length in 1..65536) { "Unexpected RouterOS API word size" }
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) return null
                offset += count
            }
            words.add(String(bytes, Charsets.UTF_8))
        }
    }

    private fun writeLength(out: BufferedOutputStream, n: Int) {
        when {
            n < 0x80 -> out.write(n)
            n < 0x4000 -> { out.write((n shr 8) or 0x80); out.write(n) }
            n < 0x200000 -> { out.write((n shr 16) or 0xC0); out.write(n shr 8); out.write(n) }
            n < 0x10000000 -> { out.write((n shr 24) or 0xE0); out.write(n shr 16); out.write(n shr 8); out.write(n) }
            else -> { out.write(0xF0); out.write(n shr 24); out.write(n shr 16); out.write(n shr 8); out.write(n) }
        }
    }

    private fun readLength(input: BufferedInputStream): Int? {
        val b = input.read(); if (b < 0) return null
        return when {
            b and 0x80 == 0 -> b
            b and 0xC0 == 0x80 -> ((b and 0x3F) shl 8) or readByte(input)
            b and 0xE0 == 0xC0 -> ((b and 0x1F) shl 16) or (readByte(input) shl 8) or readByte(input)
            b and 0xF0 == 0xE0 -> ((b and 0x0F) shl 24) or (readByte(input) shl 16) or (readByte(input) shl 8) or readByte(input)
            else -> (readByte(input) shl 24) or (readByte(input) shl 16) or (readByte(input) shl 8) or readByte(input)
        }
    }
    private fun readByte(input: BufferedInputStream): Int = input.read().also { if (it < 0) throw java.io.EOFException() }
}
