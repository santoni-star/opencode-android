package com.example.opencodebootstrap

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status = findViewById<TextView>(R.id.status)
        val logView = findViewById<TextView>(R.id.log)
        fun log(msg: String) = runOnUiThread { logView.append(msg + "\n") }

        lifecycleScope.launch {
            status.text = "Installing..."
            try {
                val mgr = BootstrapManager(this@MainActivity)
                val bin = withContext(Dispatchers.IO) { mgr.ensureInstalled(::log) }
                log("installed: ${bin.absolutePath}")
                status.text = "Launching opencode..."
                val out = withContext(Dispatchers.IO) {
                    val p = mgr.launchOpencode(listOf("--version"))
                    val text = p.inputStream.bufferedReader().readText()
                    val code = p.waitFor()
                    "exit=$code\n$text"
                }
                log(out)
                status.text = "Ready"
                // shortcut: replace version-check with an interactive PTY terminal session for real use
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                status.text = "Failed"
            }
        }
    }
}
