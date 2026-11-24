package com.bspsms.android

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline Bengali + English OCR. Tesseract is used for printed text and as a best-effort
 * handwriting recognizer; every captured page remains flagged for human review. */
private class HprcBengaliOcr(context: Context) {
    private val tess = TessBaseAPI()
    private val ready: Boolean

    init {
        val app = context.applicationContext
        val dataDir = java.io.File(app.filesDir, "hprc_tess")
        val tessDir = java.io.File(dataDir, "tessdata")
        tessDir.mkdirs()
        ready = runCatching {
            listOf("ben.traineddata", "eng.traineddata").forEach { name ->
                val target = java.io.File(tessDir, name)
                if (!target.exists() || target.length() < 100_000L) {
                    app.assets.open("tessdata/$name").use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                }
            }
            val initialized = tess.init(dataDir.absolutePath, "ben+eng")
            if (initialized) tess.setPageSegMode(3)
            initialized
        }.getOrDefault(false)
    }

    @Synchronized fun recognize(bitmap: Bitmap, rotationDegrees: Int): String {
        if (!ready) { if (!bitmap.isRecycled) bitmap.recycle(); return "" }
        var working = bitmap
        var recognized = ""
        try {
            if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                working = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            tess.setImage(working)
            recognized = tess.getUTF8Text().orEmpty().trim()
        } catch (_: Exception) {
            recognized = ""
        } finally {
            runCatching { tess.clear() }
            if (working !== bitmap && !working.isRecycled) working.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        return recognized
    }

    fun close() { runCatching { tess.end() } }
}

private fun sanitizeHprcOcrText(raw: String): String {
    val excluded = Regex(
        "(?i)date\\s*of\\s*birth|\\bDOB\\b|aadhaar|aadhar|\\bBSP\\s*ID\\b|father|mother|guardian|parent|অভিভাবক|পিতার\\s*নাম|মাতার\\s*নাম|জন্ম\\s*তারিখ|আধার|বিএসপি\\s*আইডি"
    )
    return raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !excluded.containsMatchIn(it) }.joinToString("\n")
}

data class HprcCapturedPage(
    val studentName: String,
    val section: String,
    val rawText: String,
    val capturedAt: String,
    val needsReview: Boolean,
    val rollNo: String = ""
)

object HprcScanStore {
    private fun dataFile(context: Context, ownerKey: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(ownerKey.toByteArray(Charsets.UTF_8))
        val safeKey = digest.joinToString("") { "%02x".format(it) }.take(24)
        return java.io.File(context.filesDir, "hprc_scan_$safeKey.json")
    }

    @Synchronized fun load(context: Context, ownerKey: String): List<HprcCapturedPage> {
        val file = dataFile(context, ownerKey)
        val raw = if (file.exists()) file.readText() else "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val pages = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                HprcCapturedPage(o.optString("studentName"), o.optString("section"), o.optString("rawText"), o.optString("capturedAt"), o.optBoolean("needsReview", true), o.optString("rollNo"))
            }
        }
        if (!file.exists() && pages.isNotEmpty()) persist(context, ownerKey, pages)
        return pages
    }

    @Synchronized fun append(context: Context, ownerKey: String, page: HprcCapturedPage): Boolean {
        val pages = load(context, ownerKey).toMutableList()
        val normalizedNew = page.rawText.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        if (normalizedNew.isNotBlank() && pages.any { it.rawText.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "") == normalizedNew }) return false
        pages.add(page)
        persist(context, ownerKey, pages)
        return true
    }

    @Synchronized fun saveAll(context: Context, ownerKey: String, pages: List<HprcCapturedPage>) = persist(context, ownerKey, pages)

    private fun persist(context: Context, ownerKey: String, pages: List<HprcCapturedPage>) {
        val arr = JSONArray()
        pages.forEach { p -> arr.put(JSONObject().put("studentName", p.studentName).put("section", p.section).put("rawText", p.rawText).put("capturedAt", p.capturedAt).put("needsReview", p.needsReview).put("rollNo", p.rollNo)) }
        val file = dataFile(context, ownerKey)
        val temp = java.io.File(file.parentFile, file.name + ".tmp")
        temp.writeText(arr.toString())
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) temp.copyTo(file, overwrite = true).also { temp.delete() }
    }

    @Synchronized fun clear(context: Context, ownerKey: String) {
        dataFile(context, ownerKey).delete()
    }
}

@Composable
fun HprcScanScreen(
    locale: AppLocale,
    storageOwnerKey: String,
    onCreateExcelDocument: (List<HprcCapturedPage>, String) -> Unit,
    onFill: (List<HprcCapturedPage>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pages = remember(storageOwnerKey) { mutableStateListOf<HprcCapturedPage>().apply { addAll(HprcScanStore.load(context, storageOwnerKey)) } }
    var scanning by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Press SCAN to start continuous camera scanning.") }
    val lastTextHash = remember { java.util.concurrent.atomic.AtomicReference("") }
    val processingFrame = remember { AtomicBoolean(false) }
    val lastStudentName = remember { java.util.concurrent.atomic.AtomicReference(pages.lastOrNull { it.studentName.isNotBlank() }?.studentName.orEmpty()) }
    val lastStudentRoll = remember { java.util.concurrent.atomic.AtomicReference(pages.lastOrNull { it.rollNo.isNotBlank() }?.rollNo.orEmpty()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editStudentName by remember { mutableStateOf("") }
    var editRollNo by remember { mutableStateOf("") }
    var editSection by remember { mutableStateOf("Other / Review") }
    var editRawText by remember { mutableStateOf("") }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val bengaliOcr = remember { HprcBengaliOcr(context) }
    val lastFrameOcrAt = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    val isScanning = remember { AtomicBoolean(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var analysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (!granted) status = "Camera permission is required for scanning."
    }

    fun stopScan() {
        isScanning.set(false)
        scanning = false
        status = "Scanning stopped. ${pages.size} page(s) are saved on this device."
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        onDispose {
            isScanning.set(false)
            analysis?.clearAnalyzer()
            runCatching { recognizer.close() }
            runCatching { bengaliOcr.close() }
            executor.shutdown()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (locale == AppLocale.BN) "HPRC Scan Tool" else "HPRC Scan Tool", fontSize = 18.sp, color = Color(0xFF123B70))
        Text(status, fontSize = 12.sp, color = Color(0xFF475569))
        Card(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(12.dp)) {
            androidx.compose.runtime.key(cameraGranted) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            runCatching {
                                val provider = providerFuture.get()
                                val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis = imageAnalysis
                                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                    val nowMs = SystemClock.elapsedRealtime()
                                    if (!isScanning.get() || nowMs - lastFrameOcrAt.get() < 1200L || !processingFrame.compareAndSet(false, true)) { imageProxy.close(); return@setAnalyzer }
                                    lastFrameOcrAt.set(nowMs)
                                    val mediaImage = imageProxy.image
                                    if (mediaImage == null) { processingFrame.set(false); imageProxy.close(); return@setAnalyzer }
                                    val bengaliText = runCatching { bengaliOcr.recognize(imageProxy.toBitmap(), imageProxy.imageInfo.rotationDegrees) }.getOrDefault("")
                                    val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                    recognizer.process(input)
                                        .addOnSuccessListener { result ->
                                            val text = sanitizeHprcOcrText(listOf(result.text.trim(), bengaliText).filter { it.isNotBlank() }.distinct().joinToString("\n"))
                                            val textHash = text.hashCode().toString()
                                            if (text.length >= 18 && lastTextHash.getAndSet(textHash) != textHash) {
                                                val detectedName = Regex("(?im)(?:student(?:'s)?\\s*name|name\\s*of\\s*student|শিক্ষার্থীর\\s*নাম|ছাত্র(?:ী)?র\\s*নাম)\\s*[:：-]?\\s*([^\\r\\n]+)").find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                                                if (detectedName.isNotBlank()) {
                                                    val previousName = lastStudentName.get()
                                                    if (previousName.isNotBlank() && !previousName.equals(detectedName, ignoreCase = true)) lastStudentRoll.set("")
                                                    lastStudentName.set(detectedName)
                                                }
                                                val name = detectedName.ifBlank { lastStudentName.get() }
                                                val detectedRoll = Regex("(?im)(?:roll(?:\\s*(?:no|number))?|রোল(?:\\s*নম্বর)?)\\s*[:：-]?\\s*([০-৯0-9A-Za-z/-]+)").find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                                                if (detectedRoll.isNotBlank()) lastStudentRoll.set(detectedRoll)
                                                val rollNo = detectedRoll.ifBlank { lastStudentRoll.get() }
                                                val section = when {
                                                    Regex("(?i)formative|F[1-3][A-C]|গঠনমূলক").containsMatchIn(text) -> "Formative"
                                                    Regex("(?i)summative|\\bS\\.?E\\.?\\b|1st\\s*S\\.?E|সমষ্টিগত").containsMatchIn(text) -> "Summative"
                                                    Regex("(?i)LPCD|learning perspective|cognitive domain|শিখন").containsMatchIn(text) -> "LPCD"
                                                    Regex("(?i)BCO|behavioural cognitive outcomes|আচরণ").containsMatchIn(text) -> "BCO"
                                                    else -> "Other / Review"
                                                }
                                                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                                                val added = HprcScanStore.append(context, storageOwnerKey, HprcCapturedPage(name, section, text, now, true, rollNo))
                                                if (added) {
                                                    (context as? android.app.Activity)?.runOnUiThread {
                                                        pages.clear(); pages.addAll(HprcScanStore.load(context, storageOwnerKey))
                                                        status = "Detected and saved ${pages.size} page(s). Keep the next report-card page in view."
                                                    }
                                                }
                                            }
                                        }
                                        .addOnCompleteListener { processingFrame.set(false); imageProxy.close() }
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
                                cameraReady = true
                            }.onFailure { status = "Camera error: ${it.message}" }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), enabled = !scanning && cameraReady, onClick = {
                if (!cameraGranted) cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                else { isScanning.set(true); scanning = true; status = "Live scan running. Hold each page steady in front of the camera." }
            }) { Text("SCAN") }
            OutlinedButton(modifier = Modifier.weight(1f), enabled = scanning, onClick = { stopScan() }) { Text("STOP SCAN") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), enabled = pages.isNotEmpty(), onClick = { onCreateExcelDocument(pages.toList(), "HPRC_Scan_${System.currentTimeMillis()}.xlsx") }) { Text("EXPORT EXCEL") }
            Button(modifier = Modifier.weight(1f), enabled = pages.any { !it.needsReview }, onClick = { onFill(pages.filter { !it.needsReview }) }) { Text("FILL") }
        }
        Text("Saved pages: ${pages.size} · New scans append to this saved session.", fontSize = 11.sp, color = Color(0xFF64748B))
        if (pages.isNotEmpty()) {
            LazyColumn(modifier = Modifier.height(125.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(pages.takeLast(8).reversed()) { page ->
                    Card(modifier = Modifier.clickable {
                        val index = pages.indexOf(page)
                        if (index >= 0) {
                            editingIndex = index
                            editStudentName = page.studentName
                            editRollNo = page.rollNo
                            editSection = page.section
                            editRawText = page.rawText
                        }
                    }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(7.dp)) {
                            Text("${page.studentName.ifBlank { "Student name not detected" }} · ${page.rollNo} · ${page.section}", fontSize = 11.sp, color = Color(0xFF123B70))
                            Text(page.rawText.take(150).replace('\n', ' '), fontSize = 10.sp, color = Color(0xFF475569))
                        }
                    }
                }
            }
        }
        Text("Tap any scanned page to review or correct its text before export or FILL.", fontSize = 10.sp, color = Color(0xFF64748B))
        Text("OCR note: Bengali + English OCR runs offline on this device. Printed text is best supported; handwriting may be inaccurate and every page must be reviewed before FILL.", fontSize = 10.sp, color = Color(0xFF9A3412))
    }

    editingIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { editingIndex = null },
            title = { Text("Review scanned page") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(editStudentName, { editStudentName = it }, label = { Text("Student Name") }, singleLine = true)
                    OutlinedTextField(editRollNo, { editRollNo = it }, label = { Text("Roll No (if available)") }, singleLine = true)
                    OutlinedTextField(editSection, { editSection = it }, label = { Text("Section") }, singleLine = true)
                    OutlinedTextField(editRawText, { editRawText = it }, label = { Text("OCR Text / Correct manually") }, minLines = 5)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (index in pages.indices) {
                        pages[index] = pages[index].copy(studentName = editStudentName.trim(), rollNo = editRollNo.trim(), section = editSection.trim().ifBlank { "Other / Review" }, rawText = editRawText, needsReview = false)
                        HprcScanStore.saveAll(context, storageOwnerKey, pages)
                        lastStudentName.set(editStudentName.trim())
                        lastStudentRoll.set(editRollNo.trim())
                    }
                    editingIndex = null
                }) { Text("Save Review") }
            },
            dismissButton = { TextButton(onClick = { editingIndex = null }) { Text("Cancel") } }
        )
    }
}

object HprcFillMapper {
    fun toJson(pages: List<HprcCapturedPage>): String {
        // Never send unreviewed OCR values to the Portal.
        val verifiedPages = pages.filter { !it.needsReview }
        val grouped = verifiedPages.groupBy { page ->
            page.rollNo.trim().takeIf { it.isNotBlank() }?.let { "roll:$it" }
                ?: "name:${page.studentName.trim().lowercase()}"
        }
        val students = JSONArray()
        grouped.forEach { (_, studentPages) ->
            val name = studentPages.firstOrNull { it.studentName.isNotBlank() }?.studentName.orEmpty()
            val fields = JSONArray()
            studentPages.forEach { page ->
                val section = page.section
                val lines = page.rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
                lines.forEach { line ->
                    val codeMatch = Regex("(?i)^((?:F[1-3][A-C])|(?:1st\\s*S\\.?E\\.?|2nd\\s*S\\.?E\\.?|3rd\\s*S\\.?E\\.?|LPCD|BCO))\\s*[:：-]?\\s*(.+)$").find(line)
                    val labelValue = Regex("^([^:：-]{2,45})\\s*[:：-]\\s*(.{1,80})$").find(line)
                    val match = codeMatch ?: labelValue
                    if (match != null) {
                        val key = match.groupValues[1].trim()
                        val value = match.groupValues[2].trim()
                        if (value.isNotBlank() && !Regex("(?i)date of birth|aadhaar|aadhar|guardian|father|mother|parent|bsp\\s*id").containsMatchIn(key)) {
                            fields.put(JSONObject().put("key", key).put("value", value).put("section", section))
                        }
                    }
                }
            }
            val roll = studentPages.firstOrNull { it.rollNo.isNotBlank() }?.rollNo.orEmpty()
            if ((name.isNotBlank() || roll.isNotBlank()) && fields.length() > 0) students.put(JSONObject().put("studentName", name).put("rollNo", roll).put("fields", fields))
        }
        return JSONObject().put("students", students).toString()
    }
}

object HprcWorkbookExporter {
    fun write(pages: List<HprcCapturedPage>, out: OutputStream) {
        val sheets = listOf("Student_List", "Student_Wise", "Formative", "Summative", "LPCD", "BCO", "Original_Format", "Review")
        ZipOutputStream(out).use { zip ->
            val overrides = StringBuilder("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            sheets.forEachIndexed { i, _ -> overrides.append("<Override PartName=\"/xl/worksheets/sheet${i+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>$overrides</Types>")
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            val rels = sheets.indices.joinToString("") { i -> "<Relationship Id=\"rId${i+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i+1}.xml\"/>" }
            entry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$rels</Relationships>")
            val sheetTags = sheets.mapIndexed { i, name -> "<sheet name=\"$name\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>" }.joinToString("")
            entry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>$sheetTags</sheets></workbook>")
            sheets.forEachIndexed { index, name ->
                val rows = mutableListOf<List<String>>()
                when (name) {
                    "Student_List" -> { rows.add(listOf("Student Name", "Roll No", "Pages", "Sections")); pages.groupBy { it.rollNo.takeIf { roll -> roll.isNotBlank() } ?: it.studentName }.forEach { (_, items) -> rows.add(listOf(items.firstOrNull { it.studentName.isNotBlank() }?.studentName.orEmpty(), items.firstOrNull { it.rollNo.isNotBlank() }?.rollNo.orEmpty(), items.size.toString(), items.map { it.section }.distinct().joinToString(", "))) } }
                    "Student_Wise" -> {
                        rows.add(listOf("Student Name", "Roll No", "Pages", "All Captured Text"))
                        pages.groupBy { it.rollNo.takeIf { roll -> roll.isNotBlank() } ?: it.studentName }.forEach { (_, items) -> rows.add(listOf(items.firstOrNull { it.studentName.isNotBlank() }?.studentName.orEmpty(), items.firstOrNull { it.rollNo.isNotBlank() }?.rollNo.orEmpty(), items.size.toString(), items.joinToString("\n") { it.rawText })) }
                    }
                    "Original_Format" -> { rows.add(listOf("Student Name", "Roll No", "Detected Section", "Original OCR Text")); pages.forEach { rows.add(listOf(it.studentName, it.rollNo, it.section, it.rawText)) } }
                    "Review" -> { rows.add(listOf("Student Name", "Roll No", "Section", "Unverified OCR Text")); pages.filter { it.needsReview }.forEach { rows.add(listOf(it.studentName, it.rollNo, it.section, it.rawText)) } }
                    else -> {
                        rows.add(listOf("Student Name", "Roll No", "Field / Code", "Detected Value", "Source Line"))
                        pages.filter { it.section.equals(name, true) }.forEach { page ->
                            val lines = page.rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
                            lines.forEach { line ->
                                val m = Regex("^(.{2,45}?)\\s*[:：-]\\s*(.{1,100})$").find(line)
                                val code = Regex("(?i)^(F[1-3][A-C]|1st\\s*S\\.?E\\.?|2nd\\s*S\\.?E\\.?|3rd\\s*S\\.?E\\.?|LPCD|BCO)\\s+(.+)$").find(line)
                                val label = m?.groupValues?.get(1) ?: code?.groupValues?.get(1) ?: "Needs Review"
                                val value = m?.groupValues?.get(2) ?: code?.groupValues?.get(2) ?: line
                                rows.add(listOf(page.studentName, page.rollNo, label, value, line))
                            }
                        }
                    }
                }
                val xmlRows = rows.mapIndexed { r, row -> "<row r=\"${r+1}\">" + row.mapIndexed { c, value -> "<c r=\"${col(c)}${r+1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(value)}</t></is></c>" }.joinToString("") + "</row>" }.joinToString("")
                entry(zip, "xl/worksheets/sheet${index+1}.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$xmlRows</sheetData></worksheet>")
            }
        }
    }
    private fun col(i: Int): String = (('A'.code + i).toChar()).toString()
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun entry(zip: ZipOutputStream, name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
}
