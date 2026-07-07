package com.nfcleser

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLastScan: TextView
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnSave: Button
    private lateinit var lvHistory: ListView

    private val prefs by lazy { getSharedPreferences("nfc_prefs", MODE_PRIVATE) }
    private val history = mutableListOf<String>()
    private lateinit var histAdapter: ArrayAdapter<String>

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus   = findViewById(R.id.tvStatus)
        tvLastScan = findViewById(R.id.tvLastScan)
        etIp       = findViewById(R.id.etIp)
        etPort     = findViewById(R.id.etPort)
        btnSave    = findViewById(R.id.btnSave)
        lvHistory  = findViewById(R.id.lvHistory)

        // Gespeicherte Werte laden
        etIp.setText(prefs.getString("pc_ip", "192.168.1.100"))
        etPort.setText(prefs.getString("pc_port", "8765"))

        btnSave.setOnClickListener {
            val ip   = etIp.text.toString().trim()
            val port = etPort.text.toString().trim()
            if (ip.isEmpty() || port.isEmpty()) {
                Toast.makeText(this, "IP und Port angeben!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("pc_ip", ip)
                .putString("pc_port", port)
                .apply()
            Toast.makeText(this, "✓ Gespeichert", Toast.LENGTH_SHORT).show()
        }

        histAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, history)
        lvHistory.adapter = histAdapter

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    /** Wird aufgerufen, wenn ein NFC-Tag erkannt wird (App ist im Vordergrund). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action ?: return
        if (action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            handleNfcIntent(intent)
        }
    }

    // ── NFC-Foreground-Dispatch aktivieren ──────────────────────────────

    private fun enableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val flags  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getActivity(this, 0, intent, flags)

        // Alle NFC-Technologien akzeptieren
        val techLists = arrayOf(
            arrayOf("android.nfc.tech.Ndef"),
            arrayOf("android.nfc.tech.NdefFormatable"),
            arrayOf("android.nfc.tech.MifareClassic"),
            arrayOf("android.nfc.tech.MifareUltralight"),
            arrayOf("android.nfc.tech.IsoDep"),
            arrayOf("android.nfc.tech.NfcA"),
            arrayOf("android.nfc.tech.NfcB"),
            arrayOf("android.nfc.tech.NfcF"),
            arrayOf("android.nfc.tech.NfcV")
        )
        adapter.enableForegroundDispatch(this, pending, null, techLists)
    }

    // ── NFC Intent verarbeiten ───────────────────────────────────────────

    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return

        val uid = tag.id.joinToString("") { "%02X".format(it) }

        val json = JSONObject().apply {
            put("uid", uid)
            put("timestamp", System.currentTimeMillis())
        }

        // NDEF aus Intent (schnellster Weg)
        @Suppress("DEPRECATION")
        val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMsgs != null && rawMsgs.isNotEmpty()) {
            parseNdef(rawMsgs[0] as NdefMessage, json)
        } else {
            // NDEF direkt vom Tag lesen
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                try {
                    ndef.connect()
                    ndef.ndefMessage?.let { parseNdef(it, json) }
                        ?: json.put("type", "TAG")
                    ndef.close()
                } catch (e: Exception) {
                    json.put("type", "TAG")
                    json.put("note", "NDEF nicht lesbar")
                }
            } else {
                json.put("type", "TAG")
            }
        }

        val display = buildDisplay(json, uid)
        runOnUiThread {
            tvLastScan.text = display
            tvStatus.text   = "✓ Gesendet an PC"
            history.add(0, display)
            if (history.size > 30) history.removeAt(history.size - 1)
            histAdapter.notifyDataSetChanged()
        }

        val ip   = prefs.getString("pc_ip", "192.168.1.100")!!
        val port = prefs.getString("pc_port", "8765")!!
        thread { sendToPC(json.toString(), ip, port) }
    }

    // ── NDEF parsen ─────────────────────────────────────────────────────

    private fun parseNdef(msg: NdefMessage, json: JSONObject) {
        json.put("type", "NDEF")
        val arr = JSONArray()
        for (rec in msg.records) {
            val obj     = JSONObject()
            val payload = rec.payload
            when (rec.tnf) {
                NdefRecord.TNF_WELL_KNOWN -> {
                    when (String(rec.type)) {
                        "U" -> {
                            val prefix = URL_PREFIXES[payload[0].toInt() and 0xFF] ?: ""
                            val url    = prefix + String(payload.copyOfRange(1, payload.size))
                            obj.put("type", "URL")
                            obj.put("content", url)
                        }
                        "T" -> {
                            val langLen = payload[0].toInt() and 0x3F
                            val text    = String(
                                payload.copyOfRange(1 + langLen, payload.size),
                                Charsets.UTF_8
                            )
                            obj.put("type", "TEXT")
                            obj.put("content", text)
                        }
                        else -> {
                            obj.put("type", "WELL_KNOWN")
                            obj.put("content", payload.toHex())
                        }
                    }
                }
                NdefRecord.TNF_MIME_MEDIA -> {
                    obj.put("type", "MIME")
                    obj.put("mime", String(rec.type))
                    obj.put("content", String(payload, Charsets.UTF_8))
                }
                NdefRecord.TNF_ABSOLUTE_URI -> {
                    obj.put("type", "URL")
                    obj.put("content", String(rec.type))
                }
                else -> {
                    obj.put("type", "OTHER")
                    obj.put("content", payload.toHex())
                }
            }
            arr.put(obj)
        }
        json.put("records", arr)
    }

    // ── Anzeigetext bauen ───────────────────────────────────────────────

    private fun buildDisplay(json: JSONObject, uid: String): String {
        val sb = StringBuilder("UID: $uid")
        val recs = json.optJSONArray("records")
        if (recs != null) {
            for (i in 0 until recs.length()) {
                val r = recs.getJSONObject(i)
                sb.append("\n${r.optString("type")}: ${r.optString("content")}")
            }
        } else {
            sb.append("\nTyp: ${json.optString("type", "TAG")}")
            json.optString("note").takeIf { it.isNotEmpty() }?.let {
                sb.append(" ($it)")
            }
        }
        return sb.toString()
    }

    // ── HTTP an PC senden ────────────────────────────────────────────────

    private fun sendToPC(body: String, ip: String, port: String) {
        try {
            val conn = (URL("http://$ip:$port/nfc").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput      = true
                connectTimeout = 5_000
                readTimeout    = 5_000
            }
            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { w -> w.write(body) }
            }
            val code = conn.responseCode
            conn.disconnect()

            runOnUiThread {
                tvStatus.text = if (code == 200)
                    "✓ Erfolgreich an PC gesendet"
                else
                    "⚠ Server antwortete mit Code $code"
            }
        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "✗ Verbindungsfehler – PC erreichbar?\n${e.message}"
            }
        }
    }

    // ── Status-Text ─────────────────────────────────────────────────────

    private fun updateStatus() {
        tvStatus.text = when {
            nfcAdapter == null      -> "⚠ NFC nicht verfügbar auf diesem Gerät"
            !nfcAdapter!!.isEnabled -> "⚠ NFC ist deaktiviert – bitte in Einstellungen aktivieren"
            else                    -> "✓ Bereit – Halte NFC-Tag ans Handy"
        }
    }

    // ── Companion ────────────────────────────────────────────────────────

    companion object {
        /** NFC URI-Präfix-Tabelle (gemäß NFC Forum URI Record Type Definition). */
        val URL_PREFIXES = mapOf(
            0x00 to "", 0x01 to "http://www.", 0x02 to "https://www.",
            0x03 to "http://", 0x04 to "https://", 0x05 to "tel:",
            0x06 to "mailto:", 0x07 to "ftp://anonymous:anonymous@",
            0x08 to "ftp://ftp.", 0x09 to "ftps://", 0x0A to "sftp://",
            0x0B to "smb://", 0x0C to "nfs://", 0x0D to "ftp://",
            0x0E to "dav://", 0x0F to "news:", 0x10 to "telnet://",
            0x11 to "imap:", 0x12 to "rtsp://", 0x13 to "urn:",
            0x14 to "pop:", 0x15 to "sip:", 0x16 to "sips:",
            0x17 to "tftp:", 0x18 to "btspp://", 0x19 to "btl2cap://",
            0x1A to "btgoep://", 0x1B to "tcpobex://", 0x1C to "irdaobex://",
            0x1D to "file://", 0x1E to "urn:epc:id:", 0x1F to "urn:epc:tag:",
            0x20 to "urn:epc:pat:", 0x21 to "urn:epc:raw:", 0x22 to "urn:epc:",
            0x23 to "urn:nfc:"
        )
    }
}

// Extension: ByteArray → Hex-String
private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
