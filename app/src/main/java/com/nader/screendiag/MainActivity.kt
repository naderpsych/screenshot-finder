package com.nader.screendiag

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var reportView: TextView
    private val report = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val copyBtn = Button(this).apply {
            text = "העתק דוח"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("report", report.toString()))
            }
        }
        reportView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(copyBtn)
        root.addView(reportView)
        setContentView(ScrollView(this).apply { addView(root) })

        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            runDiagnostics()
        } else {
            requestPermissions(arrayOf(perm), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            runDiagnostics()
        } else {
            log("אין הרשאת גישה לתמונות. פתח הגדרות > אפליקציות > בדיקת סקרינשוטים > הרשאות, ואשר גישה לכל התמונות.")
        }
    }

    private fun log(line: String) {
        report.append(line).append('\n')
        runOnUiThread { reportView.text = report.toString() }
    }

    private fun runDiagnostics() = thread {
        log("=== דוח בדיקה ===")
        log("מכשיר: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")

        if (Build.VERSION.SDK_INT >= 34 &&
            checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
        ) {
            log("!! גישה חלקית בלבד לתמונות - יש לאשר גישה מלאה בהגדרות")
        }

        // AICore / Gemini Nano presence
        for (pkg in listOf("com.google.android.aicore", "com.google.android.apps.aicore")) {
            try {
                val info = packageManager.getPackageInfo(pkg, 0)
                log("AICore ($pkg): מותקן, גרסה ${info.versionName}")
            } catch (e: Exception) {
                log("AICore ($pkg): לא נמצא")
            }
        }

        // MediaStore scan
        log("")
        log("--- סריקת סקרינשוטים ---")
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val ids = ArrayList<Long>()
        val names = ArrayList<String>()
        var count = 0
        var totalBytes = 0L
        var minDate = Long.MAX_VALUE
        var maxDate = 0L
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME}=?", arrayOf("Screenshots"),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (c.moveToNext()) {
                    count++
                    totalBytes += c.getLong(iSize)
                    val d = c.getLong(iDate)
                    if (d < minDate) minDate = d
                    if (d > maxDate) maxDate = d
                    if (ids.size < 10) ids.add(c.getLong(iId))
                    if (names.size < 500) names.add(c.getString(iName) ?: "")
                }
            }
        } catch (e: Exception) {
            log("שגיאה בסריקה: ${e.message}")
        }
        val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        log("נמצאו: $count סקרינשוטים")
        log("נפח כולל: ${totalBytes / (1024 * 1024)} MB")
        if (count > 0) log("טווח תאריכים: ${fmt.format(Date(minDate * 1000))} - ${fmt.format(Date(maxDate * 1000))}")

        // Filename patterns -> source app counts
        val appCounts = HashMap<String, Int>()
        for (n in names) {
            val base = n.substringBeforeLast('.')
            val parts = base.split('_')
            if (parts.size >= 4 && parts[0].startsWith("Screenshot", true)) {
                val app = parts.subList(3, parts.size).joinToString("_")
                if (app.isNotBlank() && !app[0].isDigit()) appCounts.merge(app, 1) { a, b -> a + b }
            } else if (parts.size == 3 && parts[0].startsWith("Screenshot", true)) {
                appCounts.merge("(ללא שם אפליקציה)", 1) { a, b -> a + b }
            } else {
                appCounts.merge("(תבנית אחרת: ${base.take(20)})", 1) { a, b -> a + b }
            }
        }
        log("")
        log("--- מקורות לפי שם קובץ (מתוך ${names.size} אחרונים) ---")
        appCounts.entries.sortedByDescending { it.value }.take(15).forEach {
            log("${it.value}  ${it.key}")
        }

        if (ids.isEmpty()) {
            log("")
            log("אין תמונות לבדיקת OCR.")
            log("=== סוף הדוח ===")
            return@thread
        }

        // ML Kit Latin OCR speed test on 10 images
        log("")
        log("--- בדיקת OCR לטיני (ML Kit) על ${ids.size} תמונות ---")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var mlTotalMs = 0L
        var mlChars = 0
        var mlOk = 0
        for (id in ids) {
            try {
                val bmp = loadBitmap(id, 1600) ?: continue
                val t0 = System.currentTimeMillis()
                val res = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
                mlTotalMs += System.currentTimeMillis() - t0
                mlChars += res.text.length
                mlOk++
                bmp.recycle()
            } catch (e: Exception) {
                log("שגיאת ML Kit: ${e.message?.take(80)}")
            }
        }
        if (mlOk > 0) {
            val avg = mlTotalMs / mlOk
            log("הצליחו: $mlOk, ממוצע: $avg מ\"ש לתמונה, סה\"כ תווים: $mlChars")
            log("הערכה ל-$count תמונות: ${"%.1f".format(count * avg / 3600000.0)} שעות")
        }

        // Tesseract heb+ara speed test on 5 images
        log("")
        log("--- בדיקת OCR עברית+ערבית (Tesseract) על 5 תמונות ---")
        try {
            val tessDir = File(filesDir, "tesseract")
            val tessData = File(tessDir, "tessdata")
            tessData.mkdirs()
            for (lang in listOf("heb", "ara")) {
                val f = File(tessData, "$lang.traineddata")
                if (!f.exists()) assets.open("tessdata/$lang.traineddata").use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
            val tess = TessBaseAPI()
            if (!tess.init(tessDir.absolutePath, "heb+ara")) {
                log("אתחול Tesseract נכשל")
            } else {
                var tTotalMs = 0L
                var tChars = 0
                var tOk = 0
                for (id in ids.take(5)) {
                    try {
                        val bmp = loadBitmap(id, 1200) ?: continue
                        val t0 = System.currentTimeMillis()
                        tess.setImage(bmp)
                        val text = tess.utF8Text ?: ""
                        tTotalMs += System.currentTimeMillis() - t0
                        tChars += text.length
                        tOk++
                        bmp.recycle()
                        log("תמונה ${tOk}: ${System.currentTimeMillis() - t0} מ\"ש")
                    } catch (e: Exception) {
                        log("שגיאת Tesseract: ${e.message?.take(80)}")
                    }
                }
                tess.recycle()
                if (tOk > 0) {
                    val avg = tTotalMs / tOk
                    log("ממוצע: $avg מ\"ש לתמונה, סה\"כ תווים: $tChars")
                    log("הערכה ל-$count תמונות (מקרה גרוע): ${"%.1f".format(count * avg / 3600000.0)} שעות")
                }
            }
        } catch (e: Exception) {
            log("שגיאה כללית ב-Tesseract: ${e.message?.take(120)}")
        }

        log("")
        log("=== סוף הדוח - צלם מסך או לחץ 'העתק דוח' ===")
    }

    private fun loadBitmap(id: Long, maxDim: Int): Bitmap? {
        val uri: Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
