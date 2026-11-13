package com.example.opencodebootstrap

import android.os.Bundle
import android.view.Gravity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 18); setBackgroundColor(0xff101318.toInt()) }
        val title = TextView(this).apply { text = "OpenCode"; textSize = 30f; setTextColor(0xfff1f3f5.toInt()) }
        status = TextView(this).apply { text = "Preparing runtime…"; textSize=14f; setTextColor(0xff9ba8b8.toInt()); setPadding(0,8,0,14) }
        val note = TextView(this).apply { text = "Runtime: OpenCode CLI · Linux ARM64 musl\nNo Ubuntu/Debian rootfs is installed"; textSize=12f; setTextColor(0xff72d6b0.toInt()); setPadding(0, 4, 0, 8) }
        output = TextView(this).apply { textSize=13f; typeface=android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true); setTextColor(0xffdce3eb.toInt()); setPadding(12,12,12,12) }
        val scroll = ScrollView(this).apply { setBackgroundColor(0xff191e25.toInt()); addView(output) }
        input = EditText(this).apply { hint="Ask OpenCode…"; setSingleLine(false); minLines=1; maxLines=4; setTextColor(-1); setHintTextColor(0xff8c98a8.toInt()); imeOptions=EditorInfo.IME_ACTION_SEND }
        sendButton = Button(this).apply { text="Send"; isEnabled=false; setOnClickListener { sendInput() } }
        val copy = Button(this).apply { text="Copy output"; setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OpenCode output", output.text))
            Toast.makeText(this@MainActivity, "Output copied", Toast.LENGTH_SHORT).show()
        } }
        input.setOnEditorActionListener { _, action, _ -> if(action==EditorInfo.IME_ACTION_SEND){sendInput();true}else false }
        root.addView(title); root.addView(note); root.addView(copy); root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; addView(input,LinearLayout.LayoutParams(0,-2,1f));addView(sendButton) }; root.addView(row)
        setContentView(root)
        lifecycleScope.launch {
            try {
                val mgr=BootstrapManager(this@MainActivity)
                withContext(Dispatchers.IO){mgr.ensureInstalled { append(it) }}
                status.text="Starting OpenCode…"
                status.text="Ready · choose a provider in OpenCode config, then send a prompt"
                sendButton.isEnabled=true
            } catch(e:Exception) { append("ERROR: ${e.message}"); status.text="Startup failed" }
        }
    }
    private fun append(line:String) { runOnUiThread { output.append(line+"\n") } }
    private fun sendInput() {
        val prompt=input.text.toString().trim(); if(prompt.isEmpty() || !sendButton.isEnabled) return
        input.text.clear(); sendButton.isEnabled=false; status.text="OpenCode is responding…"
        append("\n> $prompt\n")
        lifecycleScope.launch {
            try {
                val mgr=BootstrapManager(this@MainActivity)
                val result=withContext(Dispatchers.IO) {
                    val p=mgr.runPrompt(prompt)
                    val text=p.inputStream.bufferedReader().use { it.readText() }
                    val code=p.waitFor()
                    code to text
                }
                append(result.second.ifBlank { "(No output)" })
                status.text=if(result.first==0) "Ready" else "Command exited (${result.first}) · check provider configuration"
            } catch(e:Exception) { append("ERROR: ${e.message}"); status.text="Request failed" }
            finally { sendButton.isEnabled=true }
        }
    }
    override fun onDestroy(){ super.onDestroy() }
}
