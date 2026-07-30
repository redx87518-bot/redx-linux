package com.redx.linux

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.redx.linux.core.BootstrapManager
import com.redx.linux.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bootstrapManager: BootstrapManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bootstrapManager = BootstrapManager(this)

        if (bootstrapManager.isInstalled()) {
            launchTerminal()
        } else {
            showSetupScreen()
        }
    }

    private fun showSetupScreen() {
        binding.layoutSetup.visibility = View.VISIBLE
        binding.layoutReady.visibility = View.GONE

        binding.btnInstall.setOnClickListener {
            startInstallation()
        }
    }

    private fun startInstallation() {
        binding.btnInstall.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.setup_initializing)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    bootstrapManager.install { step, percent ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            binding.tvStatus.text = step
                            binding.tvProgress.text = "$percent%"
                            binding.progressBar.progress = percent
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = getString(R.string.setup_complete)
                    binding.tvProgress.text = "100%"
                    binding.progressBar.progress = 100
                    binding.layoutSetup.visibility = View.GONE
                    binding.layoutReady.visibility = View.VISIBLE
                    binding.btnLaunch.setOnClickListener { launchTerminal() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnInstall.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = getString(R.string.setup_error, e.message)
                    Toast.makeText(this@MainActivity, "Setup failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchTerminal() {
        startActivity(Intent(this, TerminalActivity::class.java))
        finish()
    }
}
