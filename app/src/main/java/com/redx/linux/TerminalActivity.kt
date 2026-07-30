package com.redx.linux

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.redx.linux.core.ProotManager
import com.redx.linux.databinding.ActivityTerminalBinding
import com.redx.linux.terminal.TerminalSession
import com.redx.linux.terminal.TerminalView

class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding
    private var terminalSession: TerminalSession? = null
    private lateinit var terminalView: TerminalView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen, keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        terminalView = binding.terminalView
        setupSpecialKeys()
        startTerminalSession()
    }

    private fun startTerminalSession() {
        val prootManager = ProotManager(this)
        val command = prootManager.buildCommand()

        terminalSession = TerminalSession(command, filesDir.absolutePath) { output ->
            runOnUiThread {
                terminalView.receiveOutput(output)
            }
        }

        terminalView.setInputCallback { input ->
            terminalSession?.write(input)
        }

        terminalView.setSessionExitCallback {
            runOnUiThread { showSessionEndedDialog() }
        }

        terminalSession?.start()
        terminalView.requestFocus()
    }

    private fun setupSpecialKeys() {
        binding.btnEsc.setOnClickListener { terminalSession?.write("\u001b") }
        binding.btnTab.setOnClickListener { terminalSession?.write("\t") }
        binding.btnCtrl.setOnClickListener { binding.btnCtrl.isActivated = !binding.btnCtrl.isActivated }
        binding.btnUp.setOnClickListener { terminalSession?.write("\u001b[A") }
        binding.btnDown.setOnClickListener { terminalSession?.write("\u001b[B") }
        binding.btnLeft.setOnClickListener { terminalSession?.write("\u001b[D") }
        binding.btnRight.setOnClickListener { terminalSession?.write("\u001b[C") }
        binding.btnHome.setOnClickListener { terminalSession?.write("\u001b[H") }
        binding.btnEnd.setOnClickListener { terminalSession?.write("\u001b[F") }
        binding.btnPgUp.setOnClickListener { terminalSession?.write("\u001b[5~") }
        binding.btnPgDn.setOnClickListener { terminalSession?.write("\u001b[6~") }
        // Note: ViewBinding maps btn_pgUp → btnPgUp, btn_pgDn → btnPgDn
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val ctrlActive = binding.btnCtrl.isActivated
        if (ctrlActive && event != null) {
            val ch = event.unicodeChar.toChar()
            if (ch in 'a'..'z') {
                terminalSession?.write((ch - 'a' + 1).toChar().toString())
                binding.btnCtrl.isActivated = false
                return true
            }
            if (ch in 'A'..'Z') {
                terminalSession?.write((ch - 'A' + 1).toChar().toString())
                binding.btnCtrl.isActivated = false
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showSessionEndedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Session Ended")
            .setMessage("The terminal session has ended.")
            .setPositiveButton("New Session") { _, _ -> startTerminalSession() }
            .setNegativeButton("Exit") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        terminalSession?.stop()
        super.onDestroy()
    }
}
