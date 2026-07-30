package com.redx.linux.core

import android.content.Context
import java.io.File

/**
 * Builds the PRoot command that launches a chroot-like Alpine Linux session
 * on a non-rooted Android device.
 */
class ProotManager(private val context: Context) {

    private val bootstrapManager = BootstrapManager(context)

    /**
     * Returns the full command array to launch Alpine Linux via PRoot.
     * PRoot intercepts system calls to simulate chroot without root.
     */
    fun buildCommand(): Array<String> {
        val rootfs = bootstrapManager.rootfsDir.absolutePath
        val proot = bootstrapManager.prootBin.absolutePath
        val tmpDir = File(context.filesDir, "tmp").also { it.mkdirs() }

        return arrayOf(
            proot,
            "--kill-on-exit",
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${context.filesDir.absolutePath}:/sdcard",
            "--pwd=/root",
            "--env=TERM=xterm-256color",
            "--env=COLORTERM=truecolor",
            "--env=LANG=en_US.UTF-8",
            "--env=LC_ALL=en_US.UTF-8",
            "--env=HOME=/root",
            "--env=USER=root",
            "--env=LOGNAME=root",
            "--env=SHELL=/bin/ash",
            "--env=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "--env=TMPDIR=/tmp",
            "/bin/ash",
            "--login"
        )
    }

    /**
     * Returns a simple fallback command for devices where PRoot isn't available yet.
     * Runs Android's own /system/bin/sh for basic functionality.
     */
    fun buildFallbackCommand(): Array<String> {
        return arrayOf("/system/bin/sh", "-i")
    }

    /**
     * Check if we can actually run PRoot.
     */
    fun isProotReady(): Boolean {
        return bootstrapManager.prootBin.exists() && bootstrapManager.prootBin.canExecute()
    }
}
