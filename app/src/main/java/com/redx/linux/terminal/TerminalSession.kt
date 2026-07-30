package com.redx.linux.terminal

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Manages the lifecycle of a shell process and bridges its I/O to the terminal view.
 */
class TerminalSession(
    private val command: Array<String>,
    private val workingDir: String,
    private val outputCallback: (ByteArray, Int) -> Unit
) {
    constructor(
        command: Array<String>,
        workingDir: String,
        textCallback: (String) -> Unit
    ) : this(command, workingDir, { bytes, len ->
        textCallback(String(bytes, 0, len, Charsets.UTF_8))
    })

    private var process: Process? = null
    private var outputThread: Thread? = null
    private var errorThread: Thread? = null
    private var stdin: OutputStream? = null

    @Volatile
    private var running = false

    fun start() {
        try {
            val pb = ProcessBuilder(*command)
            pb.directory(java.io.File(workingDir))
            pb.environment().apply {
                put("TERM", "xterm-256color")
                put("COLORTERM", "truecolor")
                put("LANG", "en_US.UTF-8")
                put("LC_ALL", "en_US.UTF-8")
                put("HOME", "/root")
                put("USER", "root")
                put("LOGNAME", "root")
                put("SHELL", "/bin/sh")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            }
            pb.redirectErrorStream(false)

            process = pb.start()
            stdin = process!!.outputStream
            running = true

            outputThread = startReaderThread("stdout", process!!.inputStream)
            errorThread = startReaderThread("stderr", process!!.errorStream)

        } catch (e: Exception) {
            Log.e("TerminalSession", "Failed to start process", e)
            val msg = "\r\n\u001b[31mFailed to start terminal: ${e.message}\u001b[0m\r\n"
            outputCallback(msg.toByteArray(), msg.length)
        }
    }

    private fun startReaderThread(name: String, stream: InputStream): Thread {
        return Thread({
            val buffer = ByteArray(8192)
            try {
                while (running) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        val copy = buffer.copyOf(n)
                        outputCallback(copy, n)
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w("TerminalSession", "$name read error", e)
            } finally {
                running = false
            }
        }, "terminal-$name").also { it.isDaemon = true; it.start() }
    }

    fun write(data: String) {
        try {
            stdin?.let {
                it.write(data.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        } catch (e: Exception) {
            Log.w("TerminalSession", "Write failed", e)
        }
    }

    fun stop() {
        running = false
        try {
            stdin?.close()
            process?.destroy()
        } catch (_: Exception) {}
    }

    val isRunning get() = running && process?.isAlive == true
}
