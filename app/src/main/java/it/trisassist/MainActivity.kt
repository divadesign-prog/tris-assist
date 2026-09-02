package it.trisassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import it.trisassist.capture.CaptureService

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, service)
            status.text = "Assistente attivo. Apri il gioco."
        } else status.text = "Cattura schermo non autorizzata."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Pronto"; textSize = 18f }
        val start = Button(this).apply {
            text = "Avvia assistente"
            setOnClickListener { startAssistant() }
        }
        val stop = Button(this).apply {
            text = "Ferma"
            setOnClickListener {
                startService(Intent(this@MainActivity, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_STOP
                })
                status.text = "Assistente fermato."
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            addView(TextView(context).apply {
                text = "Tris Assist"
                textSize = 30f
            })
            addView(TextView(context).apply {
                text = "Evidenzia le mosse; non tocca il gioco."
                textSize = 16f
            })
            addView(start)
            addView(stop)
            addView(status)
        })
        requestNotificationPermission()
    }

    private fun startAssistant() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            status.text = "Consenti 'Mostra sopra altre app', poi premi Avvia."
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }
}
