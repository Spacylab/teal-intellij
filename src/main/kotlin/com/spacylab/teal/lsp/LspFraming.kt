package com.spacylab.teal.lsp

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal LSP stdio message framing (`Content-Length: N\r\n\r\n<N bytes of UTF-8 JSON>`),
 * shared by both directions of the references proxy.
 */
object LspFraming {

    fun readMessage(input: InputStream): String? {
        var contentLength = -1
        while (true) {
            val line = readHeaderLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0 && line.substring(0, separator).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) return null

        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return String(body, StandardCharsets.UTF_8)
    }

    private fun readHeaderLine(input: InputStream): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\r'.code) {
                input.read() // consume the paired '\n'
                return buf.toString()
            }
            if (b == '\n'.code) return buf.toString()
            buf.append(b.toChar())
        }
    }

    fun writeMessage(output: OutputStream, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
        synchronized(output) {
            output.write(header)
            output.write(bytes)
            output.flush()
        }
    }
}
