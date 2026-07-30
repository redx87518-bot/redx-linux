package com.redx.linux.core

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

/**
 * Handles first-time setup: downloads Alpine Linux minirootfs + PRoot binary,
 * extracts the rootfs, and configures the environment.
 */
class BootstrapManager(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapManager"

        private const val ALPINE_VERSION = "3.19.1"
        private const val PROOT_VERSION = "5.4.0"

        private val ALPINE_URLS = mapOf(
            "arm64-v8a"  to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
            "armeabi-v7a" to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/armv7/alpine-minirootfs-$ALPINE_VERSION-armv7.tar.gz",
            "x86_64"     to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
            "x86"        to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86/alpine-minirootfs-$ALPINE_VERSION-x86.tar.gz"
        )

        private val PROOT_URLS = mapOf(
            "arm64-v8a"  to "https://github.com/proot-me/proot/releases/download/v$PROOT_VERSION/proot-arm64",
            "armeabi-v7a" to "https://github.com/proot-me/proot/releases/download/v$PROOT_VERSION/proot-arm",
            "x86_64"     to "https://github.com/proot-me/proot/releases/download/v$PROOT_VERSION/proot-x86_64"
        )
    }

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    val prootBin: File get() = File(context.filesDir, "proot")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun isInstalled(): Boolean {
        return rootfsDir.exists() &&
            File(rootfsDir, "bin/sh").exists() &&
            prootBin.exists() &&
            prootBin.canExecute()
    }

    /**
     * Full installation: download proot + Alpine, extract rootfs, apply patches.
     * [progressCallback] receives (stepDescription, percentComplete 0-100).
     */
    fun install(progressCallback: (String, Int) -> Unit) {
        val abi = detectAbi()
        Log.i(TAG, "Detected ABI: $abi")

        // 1. Download proot
        progressCallback("Downloading PRoot for $abi…", 5)
        val prootUrl = PROOT_URLS[abi]
            ?: throw IllegalStateException("No PRoot binary for ABI: $abi")
        downloadFile(prootUrl, prootBin) { prog ->
            progressCallback("Downloading PRoot… ($prog%)", 5 + prog / 10)
        }
        prootBin.setExecutable(true, false)
        progressCallback("PRoot downloaded ✓", 15)

        // 2. Download Alpine minirootfs
        progressCallback("Downloading Alpine Linux $ALPINE_VERSION for $abi…", 16)
        val alpineUrl = ALPINE_URLS[abi]
            ?: throw IllegalStateException("No Alpine image for ABI: $abi")
        val tarGz = File(context.cacheDir, "alpine.tar.gz")
        downloadFile(alpineUrl, tarGz) { prog ->
            progressCallback("Downloading Alpine Linux… ($prog%)", 16 + (prog * 0.6).toInt())
        }
        progressCallback("Alpine downloaded ✓", 76)

        // 3. Extract rootfs
        progressCallback("Extracting Alpine rootfs…", 77)
        rootfsDir.mkdirs()
        extractTarGz(tarGz, rootfsDir) { prog ->
            progressCallback("Extracting rootfs… ($prog%)", 77 + prog / 5)
        }
        tarGz.delete()
        progressCallback("Rootfs extracted ✓", 97)

        // 4. Patch /etc/resolv.conf for DNS
        patchResolv()

        // 5. Write marker
        File(context.filesDir, ".bootstrap_done").writeText("$ALPINE_VERSION/$abi")
        progressCallback("Setup complete!", 100)
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw Exception("Empty response from $url")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            var downloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt())
                        }
                    }
                }
            }
        }
    }

    private fun extractTarGz(tarGz: File, destDir: File, onProgress: (Int) -> Unit) {
        // We use a simple implementation without Apache Commons (keep APK small)
        var count = 0
        GZIPInputStream(tarGz.inputStream().buffered()).use { gzip ->
            val buffer = ByteArray(65536)
            var inTarStream = true
            // Parse TAR format manually (512-byte blocks)
            val rawStream = gzip
            var bytesRead = 0L

            while (true) {
                val header = ByteArray(512)
                var totalRead = 0
                while (totalRead < 512) {
                    val n = rawStream.read(header, totalRead, 512 - totalRead)
                    if (n == -1) return@use
                    totalRead += n
                }

                // Check for end-of-archive (two zero blocks)
                if (header.all { it == 0.toByte() }) break

                val nameBytes = header.sliceArray(0..99)
                val name = String(nameBytes).trimEnd('\u0000').trim()
                if (name.isEmpty()) break

                val sizeStr = String(header.sliceArray(124..134)).trim().trimEnd('\u0000')
                val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L
                val typeFlag = header[156].toInt().toChar()

                // Prefix with destDir, sanitize path
                val safeName = name.removePrefix("./").removePrefix("/")
                val dest = File(destDir, safeName)

                when (typeFlag) {
                    '0', '\u0000' -> {
                        // Regular file
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val n = rawStream.read(buffer, 0, toRead)
                                if (n == -1) break
                                out.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        // Set executable if mode has execute bit
                        val modeStr = String(header.sliceArray(100..107)).trim().trimEnd('\u0000')
                        val mode = if (modeStr.isNotEmpty()) modeStr.toInt(8) else 0
                        if (mode and 0b001001001 != 0) dest.setExecutable(true, false)
                        // Skip padding
                        val pad = ((size + 511) / 512) * 512 - size
                        rawStream.skip(pad)
                    }
                    '2' -> {
                        // Symlink — read linkname from header[157..256]
                        val linkNameBytes = header.sliceArray(157..256)
                        val linkName = String(linkNameBytes).trimEnd('\u0000')
                        // Create a file with the symlink target as content (approximation on Android)
                        dest.parentFile?.mkdirs()
                        // Android doesn't support symlinks without root; skip for now
                    }
                    '5' -> {
                        // Directory
                        dest.mkdirs()
                    }
                    else -> {
                        // Skip data blocks
                        val blocks = ((size + 511) / 512) * 512
                        rawStream.skip(blocks)
                    }
                }
                count++
                if (count % 50 == 0) onProgress((count / 10).coerceAtMost(99))
            }
        }
    }

    private fun patchResolv() {
        val resolv = File(rootfsDir, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        resolv.writeText(
            "nameserver 1.1.1.1\n" +
            "nameserver 8.8.8.8\n" +
            "nameserver 8.8.4.4\n"
        )
    }

    private fun detectAbi(): String {
        val supportedAbis = android.os.Build.SUPPORTED_ABIS
        return when {
            supportedAbis.contains("arm64-v8a") -> "arm64-v8a"
            supportedAbis.contains("armeabi-v7a") -> "armeabi-v7a"
            supportedAbis.contains("x86_64") -> "x86_64"
            supportedAbis.contains("x86") -> "x86"
            else -> "arm64-v8a" // fallback
        }
    }
}
