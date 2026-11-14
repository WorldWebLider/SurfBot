package uz.nurbek.surfbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.provider.Settings as Sys

class MainActivity : Activity() {
    private val reqCapture = 1
    private val reqModel = 2
    private lateinit var status: TextView
    private val cfg by lazy { Settings(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }

        fun title(t: String) = TextView(this).apply { text = t; textSize = 18f; setPadding(0, 32, 0, 8) }.also { l.addView(it) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { l.addView(it) }
        fun sw(t: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            Switch(this).apply { text = t; isChecked = get(); setOnCheckedChangeListener { _, v -> set(v) } }.also { l.addView(it) }

        TextView(this).apply { text = "SurfBot"; textSize = 26f }.also { l.addView(it) }
        status = TextView(this).apply { textSize = 14f }.also { l.addView(it) }

        title("1. Ruxsatlar (bir marta)")
        btn("Suzuvchi oyna ruxsati") {
            startActivity(Intent(Sys.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("Maxsus imkoniyatlar → SurfBot'ni yoqing") { startActivity(Intent(Sys.ACTION_ACCESSIBILITY_SETTINGS)) }

        title("2. Ishga tushirish")
        btn("Botni ishga tushirish") { start() }
        btn("Botni butunlay to'xtatish") { stopService(Intent(this, BotService::class.java)); refresh() }

        title("3. Sozlamalar")
        sw("Yutqazsa avtomatik qayta boshlash", { cfg.autoRestart }, { cfg.autoRestart = it })
        sw("Kalit bilan davom etish (har o'yinda 1 marta)", { cfg.useKeys }, { cfg.useKeys = it })
        sw("Xavfda skeytbord ishlatish (har o'yinda 1 marta)", { cfg.hoverboard }, { cfg.hoverboard = it })
        sw("Mystery box'larni ochish", { cfg.openBoxes }, { cfg.openBoxes = it })

        title("4. O'rgatish")
        btn("Yozuvlarni yuborish (Google Drive'ga)") { shareRecordings() }
        btn("O'rgatilgan modelni yuklash (model.tflite)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, reqModel)
        }
        btn("Modelni o'chirish (1-versiyaga qaytish)") { File(filesDir, "model.tflite").delete(); refresh() }

        TextView(this).apply {
            textSize = 13f
            setPadding(0, 32, 0, 0)
            text = "Boshqarish:\n" +
                "• Suzuvchi tugmani bosish — yoqish/o'chirish\n" +
                "• Ovoz pasaytirish tugmasini 2 marta tez bosish — yoqish/o'chirish (strimda ko'rinmaydi)\n" +
                "• Suzuvchi tugmani uzoq bosish — AI ↔ REC rejimi\n" +
                "Yashil = bot o'ynayapti, qizil = sizning o'yiningiz yozilyapti, kulrang = o'chiq."
        }.also { l.addView(it) }

        setContentView(ScrollView(this).apply { addView(l) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val overlay = Sys.canDrawOverlays(this)
        val acc = GestureService.instance != null
        val model = File(filesDir, "model.tflite").exists()
        val recs = File(filesDir, "rec").listFiles()?.sumOf { it.length() / (1 + Frame.SMALL_W * Frame.SMALL_H) } ?: 0L
        status.text = "Suzuvchi oyna: ${if (overlay) "✅" else "❌"}\n" +
            "Maxsus imkoniyatlar: ${if (acc) "✅" else "❌"}\n" +
            "Bot: ${if (BotService.instance != null) "ishlayapti" else "o'chiq"}\n" +
            "Miya: ${if (model) "o'rgatilgan model" else "qoidalar (1-versiya)"}\n" +
            "Yozilgan namunalar: $recs"
    }

    private fun start() {
        if (!Sys.canDrawOverlays(this) || GestureService.instance == null) {
            Toast.makeText(this, "Avval 1-bo'limdagi ikkala ruxsatni bering", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            reqCapture -> {
                startForegroundService(Intent(this, BotService::class.java).putExtra(BotService.EXTRA_DATA, data))
                Toast.makeText(this, "Endi Subway Surfers'ni oching", Toast.LENGTH_LONG).show()
                packageManager.getLaunchIntentForPackage(BotService.GAME_PKG)?.let { startActivity(it) }
            }
            reqModel -> {
                val uri = data.data ?: return
                contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(File(filesDir, "model.tflite")).use { inp.copyTo(it) }
                }
                Toast.makeText(this, "Model yuklandi. Botni o'chirib-yoqing.", Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun shareRecordings() {
        val files = File(filesDir, "rec").listFiles()?.filter { it.length() > 0 } ?: emptyList()
        if (files.isEmpty()) {
            Toast.makeText(this, "Hali yozuv yo'q. REC rejimida o'ynang.", Toast.LENGTH_LONG).show()
            return
        }
        val zip = File(cacheDir, "surfbot_yozuvlar.zip")
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            for (f in files) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        val uri = FileProvider.getUriForFile(this, "uz.nurbek.surfbot.files", zip)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Yozuvlarni yuborish"))
    }
}
