package com.redx.linux.core

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.concurrent.TimeUnit

/**
 * Handles first-time setup:
 *  1. Copies the bundled static PRoot binary from assets to filesDir
 *  2. Downloads Alpine Linux minirootfs (~3 MB)
 *  3. Extracts the rootfs
 *  4. Patches /etc/resolv.conf
 */
class BootstrapManager(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapManager"
        private const val ALPINE_VERSION = "3.19.1"

        private val ALPINE_URLS = mapOf(
            "arm64-v8a"   to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz",
            "armeabi-v7a" to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/armv7/alpine-minirootfs-$ALPINE_VERSION-armv7.tar.gz",
            "x86_64"      to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86_64/alpine-minirootfs-$ALPINE_VERSION-x86_64.tar.gz",
            "x86"         to "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/x86/alpine-minirootfs-$ALPINE_VERSION-x86.tar.gz"
        )
    }

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    val prootBin: File get() = File(context.filesDir, "proot")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun isInstalled(): Boolean {
        return File(context.filesDir, ".bootstrap_done").exists() &&
            rootfsDir.exists() &&
            File(rootfsDir, "bin/sh").exists() &&
            prootBin.exists() &&
            prootBin.canExecute()
    }

    fun install(progressCallback: (String, Int) -> Unit) {
        val abi = detectAbi()
        Log.i(TAG, "Device ABI: $abi")

        // 1 — Install bundled PRoot binary from APK assets
        progressCallback("Installing PRoot for $abi…", 5)
        installProotFromAssets(abi)
        prootBin.setExecutable(true, false)
        Log.i(TAG, "PRoot ready at ${prootBin.absolutePath} (${prootBin.length()} bytes)")
        progressCallback("PRoot installed ✓", 15)

        // 2 — Download Alpine Linux minirootfs
        val alpineUrl = ALPINE_URLS[abi]
            ?: throw IllegalStateException("No Alpine image for ABI: $abi")
        progressCallback("Downloading Alpine Linux $ALPINE_VERSION…", 16)
        val tarGz = File(context.cacheDir, "alpine.tar.gz")
        downloadFile(alpineUrl, tarGz) { prog ->
            progressCallback("Downloading Alpine Linux… ($prog%)", 16 + (prog * 60 / 100))
        }
        progressCallback("Alpine downloaded ✓", 76)

        // 3 — Extract rootfs
        progressCallback("Extracting Alpine rootfs…", 77)
        rootfsDir.mkdirs()
        extractTarGz(tarGz, rootfsDir) { prog ->
            progressCallback("Extracting rootfs… ($prog%)", 77 + prog / 5)
        }
        tarGz.delete()
        progressCallback("Rootfs extracted ✓", 97)

        // 4 — Patch /etc/resolv.conf
        patchResolv()

        // 5 — Mark complete
        File(context.filesDir, ".bootstrap_done").writeText("$ALPINE_VERSION/$abi")
        progressCallback("Setup complete!", 100)
    }

    /**
     * Copies the static PRoot binary bundled in the APK assets for [abi] to filesDir.
     * Asset name convention: "proot-<abi>" (e.g. proot-arm64-v8a, proot-armeabi-v7a).
     */
    private fun installProotFromAssets(abi: String) {
        val assetName = "proot-$abi"
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(prootBin).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied $assetName from assets (${prootBin.length()} bytes)")
        } catch (e: Exception) {
            // If the exact ABI asset is missing, try arm64 as fallback
            val fallback = "proot-arm64-v8a"
            Log.w(TAG, "$assetName not found in assets, trying $fallback")
            try {
                context.assets.open(fallback).use { input ->
                    FileOutputStream(prootBin).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e2: Exception) {
                throw IllegalStateException(
                    "PRoot binary not found in APK assets for ABI '$abi'. " +
                    "Re-build the APK — the CI script bundles PRoot during the build.", e2
                )
            }
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} downloading $url")
            }
            val body = response.body ?: throw Exception("Empty response from $url")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            var downloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16384)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) onProgress(((downloaded * 100) / total).toInt())
                    }
                }
            }
        }
    }

    /**
     * Minimal TAR+GZ extractor — no external libraries, keeps APK small.
     * Handles regular files (type '0'/'\0'), symlinks ('2'), and directories ('5').
     */
    private fun extractTarGz(tarGz: File, destDir: File, onProgress: (Int) -> Unit) {
        var count = 0
        GZIPInputStream(tarGz.inputStream().buffered(65536)).use { gzip ->
            val header = ByteArray(512)
            val dataBuf = ByteArray(65536)

            while (true) {
                // Read 512-byte header block
                var totalRead = 0
                while (totalRead < 512) {
                    val n = gzip.read(header, totalRead, 512 - totalRead)
                    if (n == -1) return@use
                    totalRead += n
                }
                if (header.all { it == 0.toByte() }) break // end of archive

                val name = readNullTermString(header, 0, 100).removePrefix("./").removePrefix("/")
                if (name.isEmpty()) {
                    skipBlocks(gzip, dataBuf, 0)
                    continue
                }

                val sizeStr = readNullTermString(header, 124, 12).trim()
                val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L
                val modeStr = readNullTermString(header, 100, 8).trim()
                val mode = if (modeStr.isNotEmpty()) modeStr.toInt(8) else 0
                val typeFlag = header[156].toInt().toChar()

                val dest = File(destDir, name)

                when (typeFlag) {
                    '0', '\u0000' -> {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { out ->
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(dataBuf.size.toLong(), remaining).toInt()
                                val n = gzip.read(dataBuf, 0, toRead)
                                if (n == -1) break
                                out.write(dataBuf, 0, n)
                                remaining -= n
                            }
                        }
                        if (mode and 0b001001001 != 0) dest.setExecutable(true, false)
                        skipBlocks(gzip, dataBuf, size)
                    }
                    '5' -> {
                        dest.mkdirs()
                    }
                    '2' -> {
                        // Symlink: skip (Android doesn't support symlinks without root)
                        skipBlocks(gzip, dataBuf, 0)
                    }
                    else -> skipBlocks(gzip, dataBuf, size)
                }

                count++
                if (count % 100 == 0) onProgress((count / 20).coerceAtMost(99))
            }
        }
    }

    private fun skipBlocks(stream: GZIPInputStream, buf: ByteArray, dataSize: Long) {
        val blocks = ((dataSize + 511) / 512) * 512 - dataSize
        var remaining = blocks
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = stream.read(buf, 0, toRead)
            if (n == -1) break
            remaining -= n
        }
    }

    private fun readNullTermString(buf: ByteArray, offset: Int, length: Int): String {
        val end = (offset until (offset + length).coerceAtMost(buf.size))
            .firstOrNull { buf[it] == 0.toByte() } ?: (offset + length)
        return String(buf, offset, end - offset, Charsets.UTF_8)
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
        val supported = Build.SUPPORTED_ABIS
        return when {
            supported.contains("arm64-v8a")   -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            supported.contains("x86_64")      -> "x86_64"
            supported.contains("x86")         -> "x86"
            else                              -> "arm64-v8a"
        }
    }
}
