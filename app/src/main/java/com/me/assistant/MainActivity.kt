package com.me.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var inp: EditText
    private var tts: TextToSpeech? = null
    private val prefs by lazy { getSharedPreferences("p", Context.MODE_PRIVATE) }

    private val SYS = """Tum Firdaus naam ke Hinglish personal phone assistant ho. Sirf ek JSON object do, aur kuch nahi.
Actions: {"action":"alarm","hour":0-23,"minute":0-59,"label":""} | {"action":"timer","seconds":N,"label":""} |
{"action":"volume_up"} | {"action":"volume_down"} | {"action":"volume_set","percent":0-100} | {"action":"mute"} | {"action":"unmute"} |
{"action":"open_app","name":"app ka naam"} | {"action":"chat","reply":"jawab"}.
Reminder ko alarm banao (label me kaam likho). Sawal ka jawab chat me chhota Hinglish me do. Har JSON me "say":"chhota bolne wala jawab" bhi do."""

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { inp.setText(it); run(it) }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        out = findViewById(R.id.out); inp = findViewById(R.id.inp)
        tts = TextToSpeech(this) { tts?.language = Locale("hi", "IN") }
        findViewById<Button>(R.id.send).setOnClickListener { run(inp.text.toString()) }
        findViewById<Button>(R.id.mic).setOnClickListener {
            speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN"))
        }
        findViewById<Button>(R.id.key).setOnClickListener {
            val e = EditText(this); e.setText(prefs.getString("key", ""))
            android.app.AlertDialog.Builder(this).setTitle("Claude API Key").setView(e)
                .setPositiveButton("Save") { _, _ -> prefs.edit().putString("key", e.text.toString().trim()).apply() }.show()
        }
    }

    private fun show(s: String) = runOnUiThread { out.text = s; tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "x") }

    private fun run(text: String) {
        if (text.isBlank()) return
        val key = prefs.getString("key", "") ?: ""
        if (key.isEmpty()) { show("Pehle API Key daalo."); return }
        out.text = "Soch raha hoon..."
        Thread {
            try {
                val body = JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 300).put("system", SYS)
                    .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", text)))
                val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("x-api-key", key)
                c.setRequestProperty("anthropic-version", "2023-06-01")
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val resp = (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
                if (c.responseCode >= 400) { show("Error: $resp"); return@Thread }
                val t = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
                val j = JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
                runOnUiThread { act(j) }
            } catch (e: Exception) { show("Error: ${e.message}") }
        }.start()
    }

    private fun act(j: JSONObject) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val say = j.optString("say", j.optString("reply", "Ho gaya"))
        try {
            when (j.getString("action")) {
                "alarm" -> startActivity(Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, j.getInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, j.getInt("minute"))
                    .putExtra(AlarmClock.EXTRA_MESSAGE, j.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "timer" -> startActivity(Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, j.getInt("seconds")).putExtra(AlarmClock.EXTRA_MESSAGE, j.optString("label"))
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                "volume_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "volume_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "volume_set" -> am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * j.getInt("percent") / 100, AudioManager.FLAG_SHOW_UI)
                "mute" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0); muteRinger(am, true) }
                "unmute" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0); muteRinger(am, false) }
                "open_app" -> openApp(j.getString("name"))
            }
        } catch (e: Exception) { show("Error: ${e.message}"); return }
        show(say)
    }

    private fun muteRinger(am: AudioManager, mute: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return
        }
        am.ringerMode = if (mute) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val l = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val m = l.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
        if (m == null) { Toast.makeText(this, "App nahi mila: $name", Toast.LENGTH_SHORT).show(); return }
        startActivity(pm.getLaunchIntentForPackage(m.activityInfo.packageName))
    }
}
