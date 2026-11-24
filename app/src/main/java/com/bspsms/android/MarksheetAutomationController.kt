package com.bspsms.android

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class BulkDownloaderMode { AUTO, MANUAL }

enum class AutomationJobStatus { IDLE, RUNNING, PAUSED, COMPLETED, STOPPED, ERROR }

data class HrpcStudentEntry(
    val rowIndex: Int,
    val rollNo: String,
    val name: String,
    val hasView: Boolean,
    val hasPrint: Boolean,
    val viewHref: String = "",
    val printHref: String = ""
) {
    val uniqueKey: String get() = "${rollNo.trim().lowercase()}|${name.trim().lowercase()}"
}

data class BulkDownloadHistoryItem(
    val rollNo: String,
    val studentName: String,
    val pageNumber: Int,
    val fileName: String,
    val pagesSaved: List<Int>,
    val timestamp: String
)

data class BulkFailedItem(
    val rollNo: String,
    val studentName: String,
    val pageNumber: Int,
    val rowIndex: Int,
    val reason: String
)

data class BulkMarksheetState(
    val mode: BulkDownloaderMode = BulkDownloaderMode.AUTO,
    val jobStatus: AutomationJobStatus = AutomationJobStatus.IDLE,
    val selectedPages: Set<Int> = emptySet(), // empty = all 5 pages
    val safFolderLabel: String = "",
    val hasSafFolder: Boolean = false,
    val scannedStudents: List<HrpcStudentEntry> = emptyList(),
    val totalStudents: Int = 0,
    val completedDownloads: Int = 0,
    val pendingDownloads: Int = 0,
    val failedDownloadsCount: Int = 0,
    val currentStudentName: String = "",
    val currentRollNo: String = "",
    val currentPageNumber: Int = 1,
    val totalPages: Int = 1,
    val statusMessage: String = "Open Student Information table in Portal after Search, then tap Scan Table.",
    val isError: Boolean = false,
    val downloadHistory: List<BulkDownloadHistoryItem> = emptyList(),
    val failedList: List<BulkFailedItem> = emptyList(),
    val completedKeys: Set<String> = emptySet(),
    val singleBusyKey: String? = null
)

data class ResultFailedItem(
    val rollNo: String,
    val studentName: String,
    val pageNumber: Int,
    val rowIndex: Int,
    val reason: String
)

data class ResultReadyState(
    val jobStatus: AutomationJobStatus = AutomationJobStatus.IDLE,
    val scannedStudents: List<HrpcStudentEntry> = emptyList(),
    val extractedRows: List<StudentSummativeResultRow> = emptyList(),
    val extractedKeys: Set<String> = emptySet(),
    val totalStudents: Int = 0,
    val completedCount: Int = 0,
    val pendingCount: Int = 0,
    val failedCount: Int = 0,
    val currentStudentName: String = "",
    val currentRollNo: String = "",
    val currentPageNumber: Int = 1,
    val totalPages: Int = 1,
    val safFolderLabel: String = "",
    val hasSafFolder: Boolean = false,
    val lastExportedFileName: String = "",
    val statusMessage: String = "Open Student Information table in Portal after Search, then tap Start Extraction.",
    val isError: Boolean = false,
    val failedList: List<ResultFailedItem> = emptyList()
)

class MarksheetAutomationController(private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var portalWebViewProvider: () -> WebView? = { null }
    private var activePopupWebViewProvider: () -> WebView? = { null }
    private var closePopupWebViewAction: () -> Unit = {}

    private val _bulkState = MutableStateFlow(BulkMarksheetState())
    val bulkState: StateFlow<BulkMarksheetState> = _bulkState.asStateFlow()

    private val _resultState = MutableStateFlow(ResultReadyState())
    val resultState: StateFlow<ResultReadyState> = _resultState.asStateFlow()

    private var bulkJob: Job? = null
    private var resultJob: Job? = null

    @Volatile
    private var pauseBulkRequested = false
    @Volatile
    private var stopBulkRequested = false
    @Volatile
    private var pauseResultRequested = false
    @Volatile
    private var stopResultRequested = false

    @Volatile
    var cookieResolver: (String) -> String? = { url ->
        runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
    }

    // Latest saved HRPC filter snapshot so Back Navigation can restore Academic Year, Medium, Class, Section, Page
    private val lastHrpcSnapshotRef = AtomicReference<String?>(null)

    @Volatile
    var pendingHrpcRestoreFromBack: Boolean = false

    // Captures direct PDF download triggered when Portal Print endpoint serves application/pdf via DownloadListener
    private val capturedPdfDownloadUrl = AtomicReference<String?>(null)
    private val capturedPdfUserAgent = AtomicReference<String?>(null)

    private val failedDbGetUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var lastCleanHrpcUrl: String? = null

    @Volatile
    private var lastHrpcMenuHref: String? = null

    @Volatile
    var lastWebViewCacheMissAtMs: Long = 0L

    @Volatile
    var historyGoBackProducesCacheMiss: Boolean = false

    @Volatile
    var lastMainFrameErrorDesc: String? = null

    @Volatile
    var lastMainFrameErrorAtMs: Long = 0L

    init {
        refreshSafFolderState()
    }

    fun attachPortalWebViewProvider(provider: () -> WebView?) {
        portalWebViewProvider = provider
    }

    fun attachPopupWebViewHandlers(
        popupProvider: () -> WebView?,
        closePopupAction: () -> Unit
    ) {
        activePopupWebViewProvider = popupProvider
        closePopupWebViewAction = closePopupAction
    }

    private fun activeMarksheetWebView(fallbackMainWebView: WebView): WebView =
        activePopupWebViewProvider() ?: fallbackMainWebView

    private suspend fun closePopupIfOpen() {
        if (activePopupWebViewProvider() != null) {
            withContext(Dispatchers.Main) {
                runCatching { closePopupWebViewAction() }
            }
            delay(160L)
        }
    }

    fun refreshSafFolderState() {
        val label = SafStorageHelper.getSavedFolderLabel(appContext)
        val hasUri = SafStorageHelper.getSavedFolderUri(appContext) != null
        _bulkState.update { it.copy(safFolderLabel = label, hasSafFolder = hasUri) }
        _resultState.update { it.copy(safFolderLabel = label, hasSafFolder = hasUri) }
    }

    fun onSafFolderSelected(treeUri: Uri) {
        val label = SafStorageHelper.persistFolderUri(appContext, treeUri)
        _bulkState.update {
            it.copy(
                safFolderLabel = label,
                hasSafFolder = true,
                statusMessage = "Download folder selected: $label",
                isError = false
            )
        }
        _resultState.update {
            it.copy(
                safFolderLabel = label,
                hasSafFolder = true
            )
        }
    }

    fun updateLastHrpcSnapshot(snapshotJson: String) {
        if (snapshotJson.isNotBlank() && snapshotJson != "{}") {
            lastHrpcSnapshotRef.set(snapshotJson)
            runCatching {
                val obj = JSONObject(snapshotJson)
                val u = obj.optString("url", "").trim()
                val menuHref = obj.optString("hrpcMenuHref", "").trim()
                if (menuHref.startsWith("http", ignoreCase = true) && !failedDbGetUrls.contains(menuHref)) {
                    lastHrpcMenuHref = menuHref
                }
                if (u.startsWith("http", ignoreCase = true) &&
                    !failedDbGetUrls.contains(u) &&
                    !u.contains("print", ignoreCase = true)
                ) {
                    lastCleanHrpcUrl = u
                }
            }
        }
    }

    fun getLastHrpcSnapshot(): String? = lastHrpcSnapshotRef.get()

    fun isAnyAutomationActive(): Boolean =
        _bulkState.value.jobStatus == AutomationJobStatus.RUNNING ||
            _bulkState.value.singleBusyKey != null ||
            _resultState.value.jobStatus == AutomationJobStatus.RUNNING

    fun markUrlAsDbError(url: String?) {
        val cleaned = url?.trim().orEmpty()
        if (cleaned.startsWith("http", ignoreCase = true)) {
            failedDbGetUrls.add(cleaned)
        }
        lastWebViewCacheMissAtMs = System.currentTimeMillis()
        historyGoBackProducesCacheMiss = true
    }

    fun clearDbErrorUrls() {
        failedDbGetUrls.clear()
    }

    fun notifyWebViewCacheMiss() {
        val now = System.currentTimeMillis()
        lastWebViewCacheMissAtMs = now
        historyGoBackProducesCacheMiss = true
    }

    fun notifyWebViewMainFrameError(desc: String?) {
        lastMainFrameErrorDesc = desc?.takeIf { it.isNotBlank() } ?: "Portal page failed to load"
        lastMainFrameErrorAtMs = System.currentTimeMillis()
    }

    fun clearWebViewTransientError() {
        lastMainFrameErrorDesc = null
    }

    fun isAutomatedPrintSuppressionActive(): Boolean {
        val bStatus = _bulkState.value.jobStatus
        return bStatus == AutomationJobStatus.RUNNING || _bulkState.value.singleBusyKey != null
    }

    fun onPortalDownloadTriggered(url: String, mimeType: String?, userAgent: String?) {
        val cleanedUrl = url.trim()
        val looksPdf = mimeType?.contains("pdf", ignoreCase = true) == true ||
            cleanedUrl.contains(".pdf", ignoreCase = true) ||
            cleanedUrl.contains("print", ignoreCase = true) ||
            cleanedUrl.startsWith("blob:", ignoreCase = true) ||
            cleanedUrl.startsWith("data:application/pdf", ignoreCase = true)
        if (looksPdf && isAutomatedPrintSuppressionActive()) {
            capturedPdfDownloadUrl.set(cleanedUrl)
            capturedPdfUserAgent.set(userAgent)
        }
    }

    fun setBulkMode(mode: BulkDownloaderMode) {
        _bulkState.update { it.copy(mode = mode) }
    }

    fun toggleSelectedPage(page: Int) {
        if (page !in 1..6) return
        _bulkState.update { st ->
            val next = if (st.selectedPages.contains(page)) {
                st.selectedPages - page
            } else {
                st.selectedPages + page
            }
            st.copy(selectedPages = next)
        }
    }

    fun clearSelectedPages() {
        _bulkState.update { it.copy(selectedPages = emptySet()) }
    }

    fun selectAllPages() {
        _bulkState.update { it.copy(selectedPages = setOf(1, 2, 3, 4, 5, 6)) }
    }

    fun pauseBulk() {
        pauseBulkRequested = true
        _bulkState.update {
            it.copy(
                jobStatus = AutomationJobStatus.PAUSED,
                statusMessage = "Paused cleanly after current step. Tap Resume to continue."
            )
        }
    }

    fun resumeBulk() {
        pauseBulkRequested = false
        if (_bulkState.value.jobStatus == AutomationJobStatus.PAUSED) {
            _bulkState.update {
                it.copy(
                    jobStatus = AutomationJobStatus.RUNNING,
                    statusMessage = "Resuming bulk marksheet download..."
                )
            }
        }
    }

    fun stopBulk() {
        stopBulkRequested = true
        pauseBulkRequested = false
        _bulkState.update {
            it.copy(
                jobStatus = AutomationJobStatus.STOPPED,
                statusMessage = "Bulk marksheet download stopped by user."
            )
        }
    }

    fun pauseResult() {
        pauseResultRequested = true
        _resultState.update {
            it.copy(
                jobStatus = AutomationJobStatus.PAUSED,
                statusMessage = "Paused extraction. Tap Resume to continue."
            )
        }
    }

    fun resumeResult() {
        pauseResultRequested = false
        if (_resultState.value.jobStatus == AutomationJobStatus.PAUSED) {
            _resultState.update {
                it.copy(
                    jobStatus = AutomationJobStatus.RUNNING,
                    statusMessage = "Resuming summative marks extraction..."
                )
            }
        }
    }

    fun stopResult() {
        stopResultRequested = true
        pauseResultRequested = false
        _resultState.update {
            it.copy(
                jobStatus = AutomationJobStatus.STOPPED,
                statusMessage = "Extraction stopped by user."
            )
        }
    }

    fun scanCurrentTableForBulk() {
        scope.launch {
            val webView = portalWebViewProvider()
            if (webView == null) {
                _bulkState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
                return@launch
            }
            val scan = scanTableInternal(webView)
            if (!scan.ok) {
                _bulkState.update {
                    it.copy(
                        scannedStudents = emptyList(),
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return@launch
            }
            saveHrpcSnapshotNow(webView)
            val completed = _bulkState.value.completedKeys
            val failedCount = _bulkState.value.failedList.size
            val totalEst = maxOf(scan.totalEntries, scan.students.size)
            _bulkState.update {
                it.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingDownloads = (totalEst - completed.size - failedCount).coerceAtLeast(0),
                    statusMessage = "Found ${scan.students.size} students on Page ${scan.currentPage}/${scan.totalPages} (Total Entries: $totalEst).",
                    isError = false
                )
            }
        }
    }

    fun scanCurrentTableForResult() {
        scope.launch {
            val webView = portalWebViewProvider()
            if (webView == null) {
                _resultState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
                return@launch
            }
            val scan = scanTableInternal(webView)
            if (!scan.ok) {
                _resultState.update {
                    it.copy(
                        scannedStudents = emptyList(),
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return@launch
            }
            saveHrpcSnapshotNow(webView)
            val totalEst = maxOf(scan.totalEntries, scan.students.size)
            val done = _resultState.value.extractedKeys.size
            val failedCount = _resultState.value.failedList.size
            _resultState.update {
                it.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingCount = (totalEst - done - failedCount).coerceAtLeast(0),
                    statusMessage = "Found ${scan.students.size} students on Page ${scan.currentPage}/${scan.totalPages} (Total Entries: $totalEst).",
                    isError = false
                )
            }
        }
    }

    fun startBulkAutoDownload(clearPreviousHistory: Boolean = true) {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            refreshSafFolderState()
            _bulkState.update {
                it.copy(
                    statusMessage = "Please select a writable destination folder first (Tap 'Choose Download Folder').",
                    isError = true
                )
            }
            return
        }
        val webView = portalWebViewProvider()
        if (webView == null) {
            _bulkState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
            return
        }

        bulkJob?.cancel()
        pauseBulkRequested = false
        stopBulkRequested = false

        _bulkState.update { st ->
            if (clearPreviousHistory) {
                st.copy(
                    singleBusyKey = null,
                    completedDownloads = 0,
                    failedDownloadsCount = 0,
                    pendingDownloads = st.totalStudents,
                    downloadHistory = emptyList(),
                    failedList = emptyList(),
                    completedKeys = emptySet(),
                    currentStudentName = "",
                    currentRollNo = ""
                )
            } else {
                st.copy(singleBusyKey = null)
            }
        }

        bulkJob = scope.launch {
            runBulkAutoLoop(webView, treeUri)
        }
    }

    fun retryFailedBulkDownloads() {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _bulkState.update { it.copy(statusMessage = "Select a destination folder first.", isError = true) }
            return
        }
        val webView = portalWebViewProvider() ?: return
        val failed = _bulkState.value.failedList.toList()
        if (failed.isEmpty()) return

        bulkJob?.cancel()
        pauseBulkRequested = false
        stopBulkRequested = false

        bulkJob = scope.launch {
            _bulkState.update {
                it.copy(
                    singleBusyKey = null,
                    jobStatus = AutomationJobStatus.RUNNING,
                    isError = false,
                    statusMessage = "Retrying ${failed.size} failed download(s)..."
                )
            }
            val selectedPagesList = effectiveSelectedPages()
            for (item in failed) {
                if (!waitWhilePausedBulk() || stopBulkRequested) break

                // Navigate to the failed student's page if needed
                val moved = ensureOnTablePage(webView, item.pageNumber) { stopBulkRequested }
                if (!moved) {
                    if (!stopBulkRequested) {
                        updateFailedReason(item, "Could not return to Page ${item.pageNumber}")
                    }
                    continue
                }

                val student = HrpcStudentEntry(
                    rowIndex = item.rowIndex,
                    rollNo = item.rollNo,
                    name = item.studentName,
                    hasView = true,
                    hasPrint = true
                )
                val res = processSingleStudentPdf(webView, treeUri, student, item.pageNumber, selectedPagesList) { stopBulkRequested }
                if (res.isSuccess) {
                    val hist = res.getOrThrow()
                    _bulkState.update { st ->
                        val nextFailed = st.failedList.filterNot {
                            it.rollNo == item.rollNo && it.studentName == item.studentName && it.pageNumber == item.pageNumber
                        }
                        val nextCompletedKeys = st.completedKeys + student.uniqueKey
                        st.copy(
                            completedDownloads = nextCompletedKeys.size,
                            failedDownloadsCount = nextFailed.size,
                            pendingDownloads = (st.totalStudents - nextCompletedKeys.size - nextFailed.size).coerceAtLeast(0),
                            failedList = nextFailed,
                            completedKeys = nextCompletedKeys,
                            downloadHistory = listOf(hist) + st.downloadHistory,
                            statusMessage = "Retry succeeded: ${hist.fileName}"
                        )
                    }
                } else {
                    updateFailedReason(item, res.exceptionOrNull()?.message ?: "Retry failed")
                }
            }

            _bulkState.update { st ->
                val finalStatus = when {
                    stopBulkRequested -> AutomationJobStatus.STOPPED
                    else -> AutomationJobStatus.COMPLETED
                }
                st.copy(
                    jobStatus = finalStatus,
                    pendingDownloads = (st.totalStudents - st.completedKeys.size - st.failedList.size).coerceAtLeast(0),
                    statusMessage = if (stopBulkRequested) {
                        "Retry stopped by user. Remaining failed: ${st.failedList.size}."
                    } else if (st.failedList.isEmpty()) {
                        "All failed downloads retried successfully!"
                    } else {
                        "Retry finished with ${st.failedList.size} remaining failure(s)."
                    }
                )
            }
        }
    }

    fun downloadSingleStudentManual(student: HrpcStudentEntry) {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            refreshSafFolderState()
            _bulkState.update { it.copy(statusMessage = "Please choose a writable download folder first.", isError = true) }
            return
        }
        val webView = portalWebViewProvider() ?: return
        if (_bulkState.value.jobStatus == AutomationJobStatus.RUNNING || _bulkState.value.singleBusyKey != null) return

        scope.launch {
            val currentPage = _bulkState.value.currentPageNumber
            val selectedPagesList = effectiveSelectedPages()
            _bulkState.update {
                it.copy(
                    singleBusyKey = student.uniqueKey,
                    currentStudentName = student.name,
                    currentRollNo = student.rollNo,
                    statusMessage = "Downloading marksheet PDF for Roll ${student.rollNo} (${student.name})...",
                    isError = false
                )
            }
            val res = processSingleStudentPdf(webView, treeUri, student, currentPage, selectedPagesList) { false }
            if (res.isSuccess) {
                val hist = res.getOrThrow()
                _bulkState.update { st ->
                    val nextCompletedKeys = st.completedKeys + student.uniqueKey
                    val nextFailed = st.failedList.filterNot {
                        it.rollNo.equals(student.rollNo, ignoreCase = true) && it.studentName.equals(student.name, ignoreCase = true)
                    }
                    st.copy(
                        singleBusyKey = null,
                        completedDownloads = nextCompletedKeys.size,
                        failedList = nextFailed,
                        failedDownloadsCount = nextFailed.size,
                        pendingDownloads = (st.totalStudents - nextCompletedKeys.size - nextFailed.size).coerceAtLeast(0),
                        completedKeys = nextCompletedKeys,
                        downloadHistory = listOf(hist) + st.downloadHistory,
                        statusMessage = "Saved ${hist.fileName} cleanly to SAF folder.",
                        isError = false
                    )
                }
            } else {
                val reason = res.exceptionOrNull()?.message ?: "Failed to save PDF"
                _bulkState.update { st ->
                    val fail = BulkFailedItem(student.rollNo, student.name, currentPage, student.rowIndex, reason)
                    val nextFailed = (st.failedList + fail).distinctBy { "${it.rollNo}|${it.studentName}|${it.pageNumber}" }
                    st.copy(
                        singleBusyKey = null,
                        failedDownloadsCount = nextFailed.size,
                        pendingDownloads = (st.totalStudents - st.completedKeys.size - nextFailed.size).coerceAtLeast(0),
                        failedList = nextFailed,
                        statusMessage = "Failed for ${student.name}: $reason",
                        isError = true
                    )
                }
            }
        }
    }

    fun captureOpenMarksheetManually(rollNoInput: String, nameInput: String) {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _bulkState.update { it.copy(statusMessage = "Please choose a download folder first.", isError = true) }
            return
        }
        val webView = portalWebViewProvider() ?: return
        scope.launch {
            val roll = rollNoInput.trim().ifBlank { "000" }
            val name = nameInput.trim().ifBlank { "MARKSHEET" }
            val selectedPages = effectiveSelectedPages()
            val fileName = SafStorageHelper.sanitizeMarksheetFileName(roll, name, "pdf")
            _bulkState.update {
                it.copy(
                    singleBusyKey = "manual_open",
                    statusMessage = "Capturing currently open marksheet as $fileName...",
                    isError = false
                )
            }
            val saveRes = captureActiveMarksheetWebViewToSaf(
                activeMarksheetWebView(webView),
                treeUri,
                fileName,
                selectedPages
            )
            if (saveRes.isSuccess) {
                val hist = BulkDownloadHistoryItem(
                    rollNo = roll,
                    studentName = name,
                    pageNumber = _bulkState.value.currentPageNumber,
                    fileName = fileName,
                    pagesSaved = selectedPages,
                    timestamp = nowTimeStr()
                )
                _bulkState.update { st ->
                    st.copy(
                        singleBusyKey = null,
                        completedDownloads = st.completedDownloads + 1,
                        downloadHistory = listOf(hist) + st.downloadHistory,
                        statusMessage = "Saved $fileName successfully!",
                        isError = false
                    )
                }
            } else {
                _bulkState.update {
                    it.copy(
                        singleBusyKey = null,
                        statusMessage = saveRes.exceptionOrNull()?.message ?: "Failed capturing open marksheet",
                        isError = true
                    )
                }
            }
        }
    }

    fun startResultExtraction(clearPrevious: Boolean = true) {
        val webView = portalWebViewProvider()
        if (webView == null) {
            _resultState.update { it.copy(statusMessage = "Portal WebView is not ready.", isError = true) }
            return
        }
        resultJob?.cancel()
        pauseResultRequested = false
        stopResultRequested = false

        if (clearPrevious) {
            _resultState.update {
                it.copy(
                    extractedRows = emptyList(),
                    extractedKeys = emptySet(),
                    completedCount = 0,
                    failedCount = 0,
                    failedList = emptyList(),
                    lastExportedFileName = ""
                )
            }
        }

        resultJob = scope.launch {
            runResultExtractionLoop(webView)
        }
    }

    fun retryFailedResultExtractions() {
        val webView = portalWebViewProvider() ?: return
        val failed = _resultState.value.failedList.toList()
        if (failed.isEmpty()) return

        resultJob?.cancel()
        pauseResultRequested = false
        stopResultRequested = false

        resultJob = scope.launch {
            _resultState.update {
                it.copy(
                    jobStatus = AutomationJobStatus.RUNNING,
                    isError = false,
                    statusMessage = "Retrying ${failed.size} failed marksheet extraction(s)..."
                )
            }
            for (item in failed) {
                if (!waitWhilePausedResult() || stopResultRequested) break

                val moved = ensureOnTablePage(webView, item.pageNumber) { stopResultRequested }
                if (!moved) {
                    if (!stopResultRequested) {
                        updateResultFailedReason(item, "Could not return to Page ${item.pageNumber}")
                    }
                    continue
                }

                val entry = HrpcStudentEntry(item.rowIndex, item.rollNo, item.studentName, hasView = true, hasPrint = false)
                val res = extractSingleStudentSummative(webView, entry, item.pageNumber) { stopResultRequested }
                if (res.isSuccess) {
                    val row = res.getOrThrow()
                    _resultState.update { st ->
                        val mergedRows = mergeOrReplaceResultRow(st.extractedRows, row)
                        val updatedRows = ResultXlsxExporter.recalculatePositions(mergedRows)
                        val nextKeys = st.extractedKeys + entry.uniqueKey
                        val nextFailed = st.failedList.filterNot {
                            it.rollNo == item.rollNo && it.studentName == item.studentName && it.pageNumber == item.pageNumber
                        }
                        st.copy(
                            extractedRows = updatedRows,
                            extractedKeys = nextKeys,
                            completedCount = updatedRows.size,
                            pendingCount = (st.totalStudents - nextKeys.size - nextFailed.size).coerceAtLeast(0),
                            failedList = nextFailed,
                            failedCount = nextFailed.size,
                            statusMessage = "Extracted ${row.studentName} cleanly."
                        )
                    }
                } else {
                    updateResultFailedReason(item, res.exceptionOrNull()?.message ?: "Retry failed")
                }
            }
            _resultState.update { st ->
                val finalRows = ResultXlsxExporter.recalculatePositions(st.extractedRows)
                st.copy(
                    jobStatus = if (stopResultRequested) AutomationJobStatus.STOPPED else AutomationJobStatus.COMPLETED,
                    extractedRows = finalRows,
                    pendingCount = (st.totalStudents - st.extractedKeys.size - st.failedList.size).coerceAtLeast(0),
                    statusMessage = if (stopResultRequested) {
                        "Retry stopped by user. ${finalRows.size} student row(s) ready."
                    } else if (st.failedList.isEmpty()) {
                        "Retry complete! ${finalRows.size} student row(s) ready for Excel export."
                    } else {
                        "Completed with ${st.failedList.size} remaining failure(s)."
                    }
                )
            }
        }
    }

    fun exportResultWorkbookToSafFolder(fileNameInput: String = "") {
        val treeUri = SafStorageHelper.getSavedFolderUri(appContext)
        if (treeUri == null) {
            _resultState.update {
                it.copy(
                    statusMessage = "Select a SAF folder or use 'Save As .xlsx' to download.",
                    isError = true
                )
            }
            return
        }
        val rows = _resultState.value.extractedRows
        if (rows.isEmpty()) {
            _resultState.update { it.copy(statusMessage = "No student marks extracted yet.", isError = true) }
            return
        }
        scope.launch {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val finalName = fileNameInput.trim()
                .ifBlank { "Result_Ready_$timestamp.xlsx" }
                .let { if (it.endsWith(".xlsx", ignoreCase = true)) it else "$it.xlsx" }

            val writeRes = withContext(Dispatchers.IO) {
                SafStorageHelper.writeFileToFolder(
                    context = appContext,
                    treeUri = treeUri,
                    mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    displayName = finalName,
                    overwriteExisting = true
                ) { out ->
                    ResultXlsxExporter.writeWorkbook(rows, "Result Ready", out)
                }
            }
            if (writeRes.isSuccess) {
                _resultState.update {
                    it.copy(
                        lastExportedFileName = finalName,
                        statusMessage = "Excel saved: $finalName (${rows.size} students)",
                        isError = false
                    )
                }
            } else {
                _resultState.update {
                    it.copy(
                        statusMessage = writeRes.exceptionOrNull()?.message ?: "Failed saving Excel file",
                        isError = true
                    )
                }
            }
        }
    }

    fun exportResultWorkbookToExplicitUri(targetUri: Uri, fileNameLabel: String) {
        val rows = _resultState.value.extractedRows
        if (rows.isEmpty()) return
        scope.launch {
            val writeRes = withContext(Dispatchers.IO) {
                SafStorageHelper.writeToExplicitUri(appContext, targetUri) { out ->
                    ResultXlsxExporter.writeWorkbook(rows, "Result Ready", out)
                }
            }
            if (writeRes.isSuccess) {
                _resultState.update {
                    it.copy(
                        lastExportedFileName = fileNameLabel,
                        statusMessage = "Excel exported cleanly (${rows.size} students).",
                        isError = false
                    )
                }
            } else {
                _resultState.update {
                    it.copy(
                        statusMessage = writeRes.exceptionOrNull()?.message ?: "Export failed",
                        isError = true
                    )
                }
            }
        }
    }

    private suspend fun runBulkAutoLoop(webView: WebView, treeUri: Uri) {
        _bulkState.update {
            it.copy(
                jobStatus = AutomationJobStatus.RUNNING,
                isError = false,
                statusMessage = "Scanning Student Information table..."
            )
        }

        val selectedPagesList = effectiveSelectedPages()
        var currentPageSafetyCount = 0
        historyGoBackProducesCacheMiss = false
        clearDbErrorUrls()
        clearWebViewTransientError()

        while (currentPageSafetyCount < 200) {
            currentPageSafetyCount++
            if (!waitWhilePausedBulk() || stopBulkRequested) break

            var scan = scanTableInternal(webView)
            if (!scan.ok && !scan.sessionExpired) {
                val targetPage = _bulkState.value.currentPageNumber.coerceAtLeast(1)
                if (recoverStudentTableFromSnapshot(webView, targetPage) { stopBulkRequested }) {
                    scan = scanTableInternal(webView)
                }
            }
            if (stopBulkRequested) break
            if (!scan.ok) {
                _bulkState.update {
                    it.copy(
                        jobStatus = if (scan.sessionExpired) AutomationJobStatus.PAUSED else AutomationJobStatus.ERROR,
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return
            }

            saveHrpcSnapshotNow(webView)
            val totalEst = maxOf(_bulkState.value.totalStudents, scan.totalEntries, scan.students.size)

            _bulkState.update { st ->
                st.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingDownloads = (totalEst - st.completedKeys.size - st.failedList.size).coerceAtLeast(0)
                )
            }

            for (student in scan.students) {
                if (!waitWhilePausedBulk() || stopBulkRequested) break

                // Prevent duplicate downloads cleanly
                if (_bulkState.value.completedKeys.contains(student.uniqueKey)) {
                    continue
                }

                _bulkState.update { st ->
                    st.copy(
                        currentStudentName = student.name,
                        currentRollNo = student.rollNo,
                        currentPageNumber = scan.currentPage,
                        statusMessage = "Page ${scan.currentPage}/${scan.totalPages}: Downloading ${student.rollNo} - ${student.name}..."
                    )
                }

                val res = processSingleStudentPdf(webView, treeUri, student, scan.currentPage, selectedPagesList) { stopBulkRequested }
                if (stopBulkRequested) break

                if (res.isSuccess) {
                    val hist = res.getOrThrow()
                    _bulkState.update { st ->
                        val nextKeys = st.completedKeys + student.uniqueKey
                        val nextFailed = st.failedList.filterNot {
                            it.rollNo == student.rollNo && it.studentName == student.name && it.pageNumber == scan.currentPage
                        }
                        st.copy(
                            completedDownloads = nextKeys.size,
                            failedList = nextFailed,
                            failedDownloadsCount = nextFailed.size,
                            pendingDownloads = (st.totalStudents - nextKeys.size - nextFailed.size).coerceAtLeast(0),
                            completedKeys = nextKeys,
                            downloadHistory = listOf(hist) + st.downloadHistory,
                            statusMessage = "Saved ${hist.fileName}"
                        )
                    }
                } else {
                    val reason = res.exceptionOrNull()?.message ?: "Download failed"
                    if (reason.contains("403 Forbidden", ignoreCase = true)) {
                        pauseBulkRequested = true
                        _bulkState.update {
                            it.copy(
                                jobStatus = AutomationJobStatus.PAUSED,
                                statusMessage = "Portal blocked the request (403). Wait a few minutes, check the Portal, then tap Resume.",
                                isError = true
                            )
                        }
                        if (!waitWhilePausedBulk() || stopBulkRequested) break
                    } else if (reason.contains("session expired", ignoreCase = true)) {
                        pauseBulkRequested = true
                        _bulkState.update {
                            it.copy(
                                jobStatus = AutomationJobStatus.PAUSED,
                                statusMessage = "Portal session expired. Sign in on Portal and tap Resume.",
                                isError = true
                            )
                        }
                        if (!waitWhilePausedBulk() || stopBulkRequested) break
                    } else {
                        val fail = BulkFailedItem(student.rollNo, student.name, scan.currentPage, student.rowIndex, reason)
                        _bulkState.update { st ->
                            val nextFailed = (st.failedList + fail).distinctBy { "${it.rollNo}|${it.studentName}|${it.pageNumber}" }
                            st.copy(
                                failedList = nextFailed,
                                failedDownloadsCount = nextFailed.size,
                                pendingDownloads = (st.totalStudents - st.completedKeys.size - nextFailed.size).coerceAtLeast(0),
                                statusMessage = "Failed for ${student.rollNo} (${student.name}): $reason"
                            )
                        }
                        ensureOnTablePage(webView, scan.currentPage) { stopBulkRequested }
                    }
                }

                // Let the previous background WebView fully close and give the portal a breather before the next student
                delay(1200L)
            }

            if (stopBulkRequested) break
            if (!waitWhilePausedBulk() || stopBulkRequested) break

            if (!scan.hasNextPage && scan.currentPage >= scan.totalPages) {
                break
            }

            _bulkState.update {
                it.copy(statusMessage = "Moving to Page ${scan.currentPage + 1}...")
            }
            ensureOnTablePage(webView, scan.currentPage) { stopBulkRequested }
            if (stopBulkRequested) break
            val advanced = advanceToNextTablePage(webView, scan.currentPage) { stopBulkRequested }
            if (!advanced) {
                if (stopBulkRequested) break
                _bulkState.update { st ->
                    st.copy(
                        jobStatus = AutomationJobStatus.ERROR,
                        isError = true,
                        currentStudentName = "",
                        currentRollNo = "",
                        statusMessage = "Could not move to table Page ${scan.currentPage + 1}. Saved ${st.completedDownloads} of ${st.totalStudents}. Check the Portal table and start again."
                    )
                }
                return
            }
        }

        _bulkState.update { st ->
            val finishedStatus = if (stopBulkRequested) AutomationJobStatus.STOPPED else AutomationJobStatus.COMPLETED
            val msg = if (stopBulkRequested) {
                "Stopped. Saved ${st.completedDownloads} PDF(s), Failed ${st.failedDownloadsCount}."
            } else {
                "Completed! Downloaded ${st.completedDownloads} marksheet PDF(s). Failed: ${st.failedDownloadsCount}."
            }
            st.copy(
                jobStatus = finishedStatus,
                currentStudentName = "",
                currentRollNo = "",
                pendingDownloads = (st.totalStudents - st.completedKeys.size - st.failedDownloadsCount).coerceAtLeast(0),
                statusMessage = msg,
                isError = false
            )
        }
    }

    private suspend fun runResultExtractionLoop(webView: WebView) {
        _resultState.update {
            it.copy(
                jobStatus = AutomationJobStatus.RUNNING,
                isError = false,
                statusMessage = "Scanning Student Information table..."
            )
        }

        var pageSafetyCount = 0
        historyGoBackProducesCacheMiss = false
        clearDbErrorUrls()
        clearWebViewTransientError()

        while (pageSafetyCount < 200) {
            pageSafetyCount++
            if (!waitWhilePausedResult() || stopResultRequested) break

            var scan = scanTableInternal(webView)
            if (!scan.ok && !scan.sessionExpired) {
                val targetPage = _resultState.value.currentPageNumber.coerceAtLeast(1)
                if (recoverStudentTableFromSnapshot(webView, targetPage) { stopResultRequested }) {
                    scan = scanTableInternal(webView)
                }
            }
            if (stopResultRequested) break
            if (!scan.ok) {
                _resultState.update {
                    it.copy(
                        jobStatus = if (scan.sessionExpired) AutomationJobStatus.PAUSED else AutomationJobStatus.ERROR,
                        statusMessage = scan.error,
                        isError = true
                    )
                }
                return
            }

            saveHrpcSnapshotNow(webView)
            val totalEst = maxOf(_resultState.value.totalStudents, scan.totalEntries, scan.students.size)

            _resultState.update { st ->
                st.copy(
                    scannedStudents = scan.students,
                    totalStudents = totalEst,
                    currentPageNumber = scan.currentPage,
                    totalPages = scan.totalPages,
                    pendingCount = (totalEst - st.extractedKeys.size - st.failedList.size).coerceAtLeast(0)
                )
            }

            for (student in scan.students) {
                if (!waitWhilePausedResult() || stopResultRequested) break
                if (_resultState.value.extractedKeys.contains(student.uniqueKey)) continue

                _resultState.update {
                    it.copy(
                        currentStudentName = student.name,
                        currentRollNo = student.rollNo,
                        currentPageNumber = scan.currentPage,
                        statusMessage = "Page ${scan.currentPage}/${scan.totalPages}: Extracting marks for ${student.rollNo} - ${student.name}..."
                    )
                }

                val res = extractSingleStudentSummative(webView, student, scan.currentPage) { stopResultRequested }
                if (stopResultRequested) break

                if (res.isSuccess) {
                    val row = res.getOrThrow()
                    _resultState.update { st ->
                        val mergedRows = mergeOrReplaceResultRow(st.extractedRows, row)
                        val updatedRows = ResultXlsxExporter.recalculatePositions(mergedRows)
                        val nextKeys = st.extractedKeys + student.uniqueKey
                        val nextFailed = st.failedList.filterNot {
                            it.rollNo == student.rollNo && it.studentName == student.name && it.pageNumber == scan.currentPage
                        }
                        st.copy(
                            extractedRows = updatedRows,
                            extractedKeys = nextKeys,
                            completedCount = updatedRows.size,
                            failedList = nextFailed,
                            failedCount = nextFailed.size,
                            pendingCount = (st.totalStudents - nextKeys.size - nextFailed.size).coerceAtLeast(0),
                            statusMessage = "Extracted ${row.rollNo} - ${row.studentName} (Grand Total: ${row.grandTotal})"
                        )
                    }
                } else {
                    val reason = res.exceptionOrNull()?.message ?: "Extraction failed"
                    if (reason.contains("403 Forbidden", ignoreCase = true)) {
                        pauseResultRequested = true
                        _resultState.update {
                            it.copy(
                                jobStatus = AutomationJobStatus.PAUSED,
                                statusMessage = "Portal blocked the request (403). Wait a few minutes, check the Portal, then tap Resume.",
                                isError = true
                            )
                        }
                        if (!waitWhilePausedResult() || stopResultRequested) break
                    } else if (reason.contains("session expired", ignoreCase = true)) {
                        pauseResultRequested = true
                        _resultState.update {
                            it.copy(
                                jobStatus = AutomationJobStatus.PAUSED,
                                statusMessage = "Portal session expired. Re-login in Portal and tap Resume.",
                                isError = true
                            )
                        }
                        if (!waitWhilePausedResult() || stopResultRequested) break
                    } else {
                        val fail = ResultFailedItem(student.rollNo, student.name, scan.currentPage, student.rowIndex, reason)
                        _resultState.update { st ->
                            val nextFailed = (st.failedList + fail).distinctBy { "${it.rollNo}|${it.studentName}|${it.pageNumber}" }
                            st.copy(
                                failedList = nextFailed,
                                failedCount = nextFailed.size,
                                pendingCount = (st.totalStudents - st.extractedKeys.size - nextFailed.size).coerceAtLeast(0)
                            )
                        }
                        ensureOnTablePage(webView, scan.currentPage) { stopResultRequested }
                    }
                }
                delay(1000L)
            }

            if (stopResultRequested) break
            if (!waitWhilePausedResult() || stopResultRequested) break

            if (!scan.hasNextPage && scan.currentPage >= scan.totalPages) break
            ensureOnTablePage(webView, scan.currentPage) { stopResultRequested }
            if (stopResultRequested) break
            val advanced = advanceToNextTablePage(webView, scan.currentPage) { stopResultRequested }
            if (!advanced) {
                if (stopResultRequested) break
                _resultState.update { st ->
                    st.copy(
                        jobStatus = AutomationJobStatus.ERROR,
                        isError = true,
                        extractedRows = ResultXlsxExporter.recalculatePositions(st.extractedRows),
                        currentStudentName = "",
                        currentRollNo = "",
                        statusMessage = "Could not move to table Page ${scan.currentPage + 1}. Extracted ${st.extractedKeys.size} of ${st.totalStudents}. Check the Portal table and start again."
                    )
                }
                return
            }
        }

        _resultState.update { st ->
            val finalRows = ResultXlsxExporter.recalculatePositions(st.extractedRows)
            st.copy(
                jobStatus = if (stopResultRequested) AutomationJobStatus.STOPPED else AutomationJobStatus.COMPLETED,
                extractedRows = finalRows,
                currentStudentName = "",
                currentRollNo = "",
                pendingCount = (st.totalStudents - st.extractedKeys.size - st.failedList.size).coerceAtLeast(0),
                statusMessage = if (stopResultRequested) {
                    "Stopped. Extracted ${finalRows.size} student row(s)."
                } else {
                    "Extraction complete! ${finalRows.size} student(s) ready. Tap 'Download Excel (.xlsx)' to save."
                }
            )
        }
    }

    private suspend fun processSingleStudentPdf(
        webView: WebView,
        treeUri: Uri,
        student: HrpcStudentEntry,
        tablePageNum: Int,
        selectedPages: List<Int>,
        isAborted: () -> Boolean
    ): Result<BulkDownloadHistoryItem> {
        var lastError: Throwable? = null
        for (attempt in 1..2) {
            if (isAborted()) return Result.failure(IllegalStateException("Stopped by user"))
            capturedPdfDownloadUrl.set(null)
            capturedPdfUserAgent.set(null)
            closePopupIfOpen()

            // Ensure table is on expected page & snapshot is up to date
            val tableReady = ensureOnTablePage(webView, tablePageNum, isAborted)
            if (!tableReady) {
                lastError = IllegalStateException("Could not reach table Page $tablePageNum")
                continue
            }
            if (isAborted()) return Result.failure(IllegalStateException("Stopped by user"))
            saveHrpcSnapshotNow(webView)

            val triggerJson = evalJs(
                webView,
                PortalHrpcDomScripts.triggerStudentActionJs(student.rowIndex, student.rollNo, student.name, "view")
            )
            val triggerObj = runCatching { JSONObject(triggerJson) }.getOrNull()
            if (triggerObj?.optBoolean("ok", false) != true) {
                lastError = IllegalStateException(triggerObj?.optString("error") ?: "Print button failed")
                break
            }

            // Wait up to 20s for either direct PDF download listener OR rendered HTML marksheet (in main or fallback popup WebView)
            val waitOutcome = waitForMarksheetOrPdfDownload(webView, timeoutMs = 20_000L, isAborted = isAborted)
            if (isAborted()) {
                returnToStudentTableFromMarksheet(webView, tablePageNum) { false }
                return Result.failure(IllegalStateException("Stopped by user"))
            }
            val directPdfUrl = capturedPdfDownloadUrl.getAndSet(null)

            if (!waitOutcome.ok && directPdfUrl.isNullOrBlank()) {
                lastError = IllegalStateException(waitOutcome.error)
                closePopupIfOpen()
                if (waitOutcome.error.contains("session expired", ignoreCase = true)) {
                    return Result.failure(lastError)
                }
                recoverStudentTableFromSnapshot(webView, tablePageNum, isAborted)
                if (attempt == 1 && !isAborted()) {
                    continue
                }
                return Result.failure(lastError)
            }

            val fileName = SafStorageHelper.sanitizeMarksheetFileName(student.rollNo, student.name, "pdf")

            return try {
                var savedViaDirectPdf = false
                if (!directPdfUrl.isNullOrBlank()) {
                    val rawPdfBytes: ByteArray? = when {
                        directPdfUrl.startsWith("data:", ignoreCase = true) -> {
                            decodeDataUriPdfBytes(directPdfUrl)
                        }
                        directPdfUrl.startsWith("blob:", ignoreCase = true) -> {
                            readBlobPdfBytesFromWebView(activeMarksheetWebView(webView), directPdfUrl)
                        }
                        else -> {
                            val cookieHeader = runCatching { cookieResolver(directPdfUrl) }.getOrNull()
                            withContext(Dispatchers.IO) {
                                downloadBytesWithCookies(directPdfUrl, cookieHeader, capturedPdfUserAgent.get())
                            }
                        }
                    }

                    if (rawPdfBytes != null && rawPdfBytes.isNotEmpty()) {
                        val writeRes = withContext(Dispatchers.IO) {
                            SafStorageHelper.writeFileToFolder(
                                context = appContext,
                                treeUri = treeUri,
                                mimeType = "application/pdf",
                                displayName = fileName,
                                overwriteExisting = true
                            ) { out ->
                                val filterRes = MarksheetPdfGenerator.filterExistingPdfBytes(
                                    rawPdfBytes = rawPdfBytes,
                                    selectedPagesInput = selectedPages,
                                    cacheDir = appContext.cacheDir,
                                    outStream = out
                                )
                                if (filterRes.isFailure) {
                                    throw filterRes.exceptionOrNull() ?: IllegalStateException("PDF write failed")
                                }
                            }
                        }
                        if (writeRes.isFailure) {
                            throw writeRes.exceptionOrNull() ?: IllegalStateException("SAF write failed")
                        }
                        savedViaDirectPdf = true
                    }
                }

                if (!savedViaDirectPdf) {
                    delay(350L)
                    val targetView = activeMarksheetWebView(webView)
                    val saveRes = captureActiveMarksheetWebViewToSaf(targetView, treeUri, fileName, selectedPages)
                    if (saveRes.isFailure) {
                        returnToStudentTableFromMarksheet(webView, tablePageNum) { false }
                        return Result.failure(saveRes.exceptionOrNull() ?: IllegalStateException("PDF generation failed"))
                    }
                }

                // Return cleanly to the Student Information table and restore pagination page
                returnToStudentTableFromMarksheet(webView, tablePageNum) { false }

                return Result.success(
                    BulkDownloadHistoryItem(
                        rollNo = student.rollNo,
                        studentName = student.name,
                        pageNumber = tablePageNum,
                        fileName = fileName,
                        pagesSaved = selectedPages,
                        timestamp = nowTimeStr()
                    )
                )
            } catch (t: Throwable) {
                returnToStudentTableFromMarksheet(webView, tablePageNum) { false }
                return Result.failure(t)
            }
        }
        if (!isAborted()) {
            ensureOnTablePage(webView, tablePageNum, isAborted)
        }
        return Result.failure(lastError ?: IllegalStateException("Failed downloading marksheet PDF"))
    }

    private fun sanitizeExtractedMarkCell(raw: String?, maxAllowed: Double): String {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isBlank()) return ""
        val lower = cleaned.lowercase(Locale.US)
        if (lower in setOf("-", "--", "---", "na", "n/a", "nil", "*")) return ""
        if (lower == "ab" || lower == "absent" || lower == "abs") return "AB"
        // Reject any subject labels, headers, or grade strings defensively
        if (Regex("[a-zA-Z]{2,}|[\\u0980-\\u09FF]").containsMatchIn(cleaned)) {
            return ""
        }
        val num = cleaned.toDoubleOrNull() ?: return ""
        if (!num.isFinite() || num < 0.0 || num > maxAllowed) return ""
        return formatCleanNumber(num)
    }

    private fun sanitizeNumericText(raw: String?, maxAllowed: Double): String {
        val cleaned = raw?.replace("%", "")?.trim().orEmpty()
        val num = cleaned.toDoubleOrNull() ?: return ""
        if (!num.isFinite() || num < 0.0 || num > maxAllowed) return ""
        return if (cleaned.contains(".")) String.format(Locale.US, "%.2f", num) else formatCleanNumber(num)
    }

    private suspend fun extractSingleStudentSummative(
        webView: WebView,
        student: HrpcStudentEntry,
        tablePageNum: Int,
        isAborted: () -> Boolean
    ): Result<StudentSummativeResultRow> {
        var waitOutcome = SimpleOutcome(false, "View failed", false)
        for (attempt in 1..2) {
            if (isAborted()) return Result.failure(IllegalStateException("Stopped by user"))
            closePopupIfOpen()
            val tableReady = ensureOnTablePage(webView, tablePageNum, isAborted)
            if (!tableReady) {
                waitOutcome = SimpleOutcome(false, "Could not reach table Page $tablePageNum", false)
                continue
            }
            if (isAborted()) return Result.failure(IllegalStateException("Stopped by user"))
            saveHrpcSnapshotNow(webView)

            val triggerJson = evalJs(
                webView,
                PortalHrpcDomScripts.triggerStudentActionJs(student.rowIndex, student.rollNo, student.name, "view")
            )
            val triggerObj = runCatching { JSONObject(triggerJson) }.getOrNull()
            if (triggerObj?.optBoolean("ok", false) != true) {
                return Result.failure(IllegalStateException(triggerObj?.optString("error") ?: "View button failed"))
            }

            waitOutcome = waitForMarksheetOrPdfDownload(webView, timeoutMs = 20_000L, isAborted = isAborted)
            if (isAborted()) {
                returnToStudentTableFromMarksheet(webView, tablePageNum) { false }
                return Result.failure(IllegalStateException("Stopped by user"))
            }
            if (!waitOutcome.ok) {
                if (waitOutcome.error.contains("session expired", ignoreCase = true)) {
                    return Result.failure(IllegalStateException(waitOutcome.error))
                }
                closePopupIfOpen()
                recoverStudentTableFromSnapshot(webView, tablePageNum, isAborted)
                if (attempt == 1 && !isAborted()) {
                    continue
                }
            }
            break
        }
        if (!waitOutcome.ok) {
            returnToStudentTableFromMarksheet(webView, tablePageNum) { false }
            return Result.failure(IllegalStateException(waitOutcome.error))
        }

        delay(280L)
        if (isAborted()) {
            returnToStudentTableFromMarksheet(webView, tablePageNum) { false }
            return Result.failure(IllegalStateException("Stopped by user"))
        }
        val targetView = activeMarksheetWebView(webView)
        val extractedJson = evalJs(targetView, PortalHrpcDomScripts.extractSummativeMarksJs(student.rollNo, student.name))
        returnToStudentTableFromMarksheet(webView, tablePageNum) { false }

        val obj = runCatching { JSONObject(extractedJson) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Failed parsing marksheet DOM"))

        if (!obj.optBoolean("ok", false)) {
            val err = obj.optString("error", "Summative Evaluation table not found")
            if (obj.optBoolean("sessionExpired", false)) {
                return Result.failure(IllegalStateException("Portal session expired"))
            }
            return Result.failure(IllegalStateException(err))
        }

        val subjectsObj = obj.optJSONObject("subjects") ?: JSONObject()
        val labelsObj = obj.optJSONObject("subjectLabels") ?: JSONObject()
        val printedObj = obj.optJSONObject("printed") ?: JSONObject()
        val maximaObj = obj.optJSONObject("termMaxima") ?: JSONObject()
        val maxT1 = maximaObj.optDouble("t1", 20.0).takeIf { it > 0.0 } ?: 20.0
        val maxT2 = maximaObj.optDouble("t2", 30.0).takeIf { it > 0.0 } ?: 30.0
        val maxT3 = maximaObj.optDouble("t3", 50.0).takeIf { it > 0.0 } ?: 50.0

        fun termMark(subKey: String, termField: String, maxAllowed: Double): String {
            val raw = subjectsObj.optJSONObject(subKey)?.optString(termField, "")
            return sanitizeExtractedMarkCell(raw, maxAllowed)
        }

        // Treat blank, "-", "AB", and any non-numeric token strictly as 0.0 for all calculations
        fun sumStringsAsZero(values: List<String>): Double {
            var sum = 0.0
            for (v in values) {
                val n = v.toDoubleOrNull()
                if (n != null && n.isFinite() && n >= 0.0) {
                    sum += n
                }
            }
            return sum
        }

        val ben1 = termMark("ben", "t1", maxT1); val eng1 = termMark("eng", "t1", maxT1); val evs1 = termMark("evs", "t1", maxT1)
        val math1 = termMark("math", "t1", maxT1); val health1 = termMark("health", "t1", maxT1); val art1 = termMark("art", "t1", maxT1)
        val t1Vals = listOf(ben1, eng1, evs1, math1, health1, art1)
        val t1Sum = sumStringsAsZero(t1Vals)
        var tot1 = if (t1Sum == 0.0 && t1Vals.all { it.isBlank() }) {
            sanitizeExtractedMarkCell(printedObj.optString("t1", ""), 2000.0)
        } else {
            formatCleanNumber(t1Sum)
        }

        val ben2 = termMark("ben", "t2", maxT2); val eng2 = termMark("eng", "t2", maxT2); val evs2 = termMark("evs", "t2", maxT2)
        val math2 = termMark("math", "t2", maxT2); val health2 = termMark("health", "t2", maxT2); val art2 = termMark("art", "t2", maxT2)
        val t2Vals = listOf(ben2, eng2, evs2, math2, health2, art2)
        val t2Sum = sumStringsAsZero(t2Vals)
        var tot2 = if (t2Sum == 0.0 && t2Vals.all { it.isBlank() }) {
            sanitizeExtractedMarkCell(printedObj.optString("t2", ""), 2500.0)
        } else {
            formatCleanNumber(t2Sum)
        }

        val ben3 = termMark("ben", "t3", maxT3); val eng3 = termMark("eng", "t3", maxT3); val evs3 = termMark("evs", "t3", maxT3)
        val math3 = termMark("math", "t3", maxT3); val health3 = termMark("health", "t3", maxT3); val art3 = termMark("art", "t3", maxT3)
        val t3Vals = listOf(ben3, eng3, evs3, math3, health3, art3)
        val t3Sum = sumStringsAsZero(t3Vals)
        var tot3 = if (t3Sum == 0.0 && t3Vals.all { it.isBlank() }) {
            sanitizeExtractedMarkCell(printedObj.optString("t3", ""), 3500.0)
        } else {
            formatCleanNumber(t3Sum)
        }

        fun subjectTotal(key: String, m1: String, m2: String, m3: String): String {
            val s = sumStringsAsZero(listOf(m1, m2, m3))
            if (s == 0.0 && m1.isBlank() && m2.isBlank() && m3.isBlank()) {
                return termMark(key, "total", maxT1 + maxT2 + maxT3)
            }
            return formatCleanNumber(s)
        }

        val benTotal = subjectTotal("ben", ben1, ben2, ben3)
        val engTotal = subjectTotal("eng", eng1, eng2, eng3)
        val evsTotal = subjectTotal("evs", evs1, evs2, evs3)
        val mathTotal = subjectTotal("math", math1, math2, math3)
        val healthTotal = subjectTotal("health", health1, health2, health3)
        val artTotal = subjectTotal("art", art1, art2, art3)

        // Keep every subject actually detected in this student's Summative Evaluation.
        // No fixed subject list is used for the exported result workbook.
        val dynamicSubjects = linkedMapOf<String, DynamicSubjectMarks>()
        val subjectKeys = subjectsObj.keys()
        while (subjectKeys.hasNext()) {
            val key = subjectKeys.next()
            val termObj = subjectsObj.optJSONObject(key) ?: continue
            val d1 = sanitizeExtractedMarkCell(termObj.optString("t1", ""), maxT1)
            val d2 = sanitizeExtractedMarkCell(termObj.optString("t2", ""), maxT2)
            val d3 = sanitizeExtractedMarkCell(termObj.optString("t3", ""), maxT3)
            val total = sanitizeExtractedMarkCell(termObj.optString("total", ""), maxT1 + maxT2 + maxT3)
            val computed = sumStringsAsZero(listOf(d1, d2, d3))
            dynamicSubjects[key] = DynamicSubjectMarks(
                label = labelsObj.optString(key, key.replace('_', ' ').replaceFirstChar { it.uppercase() }),
                t1 = d1, t2 = d2, t3 = d3,
                total = total.ifBlank { if (computed > 0.0) formatCleanNumber(computed) else "" }
            )
        }

        val allT1Values = if (dynamicSubjects.isNotEmpty()) dynamicSubjects.values.map { it.t1 } else t1Vals
        val allT2Values = if (dynamicSubjects.isNotEmpty()) dynamicSubjects.values.map { it.t2 } else t2Vals
        val allT3Values = if (dynamicSubjects.isNotEmpty()) dynamicSubjects.values.map { it.t3 } else t3Vals
        tot1 = if (allT1Values.all { it.isBlank() }) sanitizeExtractedMarkCell(printedObj.optString("t1", ""), 2000.0) else formatCleanNumber(sumStringsAsZero(allT1Values))
        tot2 = if (allT2Values.all { it.isBlank() }) sanitizeExtractedMarkCell(printedObj.optString("t2", ""), 2500.0) else formatCleanNumber(sumStringsAsZero(allT2Values))
        tot3 = if (allT3Values.all { it.isBlank() }) sanitizeExtractedMarkCell(printedObj.optString("t3", ""), 3500.0) else formatCleanNumber(sumStringsAsZero(allT3Values))

        val subjectTotals = if (dynamicSubjects.isNotEmpty()) dynamicSubjects.values.map { it.total }
            else listOf(benTotal, engTotal, evsTotal, mathTotal, healthTotal, artTotal)
        val computedGrand = sumStringsAsZero(subjectTotals)
        val printedGrand = sanitizeExtractedMarkCell(printedObj.optString("grandTotal", ""), 700.0).toDoubleOrNull()
        val finalGrandNum = if (computedGrand == 0.0 && printedGrand != null && printedGrand > 0.0) {
            printedGrand
        } else {
            computedGrand
        }
        val grandTotalStr = if (subjectTotals.all { it.isBlank() } && printedGrand == null) "" else formatCleanNumber(finalGrandNum)

        // Calculate percentage from the subjects and full marks actually detected in this class.
        val matchedSubjectCount = subjectsObj.length().takeIf { it > 0 } ?: obj.optInt("matchedSubjectCount", 6).coerceAtLeast(1)
        val fullMarksTotal = matchedSubjectCount * (maxT1 + maxT2 + maxT3)
        val pctNum = if (fullMarksTotal > 0.0) (finalGrandNum / fullMarksTotal) * 100.0 else 0.0
        val pctStr = if (grandTotalStr.isBlank()) "" else String.format(Locale.US, "%.2f", pctNum.coerceIn(0.0, 100.0))

        val posStr = sanitizeExtractedMarkCell(printedObj.optString("position", ""), 999.0)

        val displayRoll = obj.optString("rollNo", student.rollNo).trim().ifBlank { student.rollNo }
        val displayName = obj.optString("studentName", student.name).trim().ifBlank { student.name }

        return Result.success(
            StudentSummativeResultRow(
                rollNo = displayRoll,
                studentName = displayName,
                term1 = SummativeTermMarks(ben1, eng1, evs1, math1, health1, art1, tot1),
                term2 = SummativeTermMarks(ben2, eng2, evs2, math2, health2, art2, tot2),
                term3 = SummativeTermMarks(ben3, eng3, evs3, math3, health3, art3, tot3),
                benTotal = benTotal,
                engTotal = engTotal,
                evsTotal = evsTotal,
                mathTotal = mathTotal,
                healthTotal = healthTotal,
                artTotal = artTotal,
                grandTotal = grandTotalStr,
                percentage = pctStr,
                position = posStr,
                dynamicSubjects = dynamicSubjects,
                term1Max = maxT1,
                term2Max = maxT2,
                term3Max = maxT3
            )
        )
    }

    private suspend fun captureActiveMarksheetWebViewToSaf(
        webView: WebView,
        treeUri: Uri,
        fileName: String,
        selectedPages: List<Int>
    ): Result<Uri> {
        val pdfBytesRes = MarksheetPdfGenerator.captureSelectedPagesToPdfBytes(webView, selectedPages, 6)
        if (pdfBytesRes.isFailure) {
            return Result.failure(pdfBytesRes.exceptionOrNull() ?: IllegalStateException("Render failed"))
        }
        val bytes = pdfBytesRes.getOrThrow()
        return withContext(Dispatchers.IO) {
            SafStorageHelper.writeFileToFolder(
                context = appContext,
                treeUri = treeUri,
                mimeType = "application/pdf",
                displayName = fileName,
                overwriteExisting = true
            ) { out ->
                out.write(bytes)
            }
        }
    }

    private suspend fun waitForMarksheetOrPdfDownload(
        webView: WebView,
        timeoutMs: Long,
        isAborted: () -> Boolean
    ): SimpleOutcome {
        val startMs = System.currentTimeMillis()
        clearWebViewTransientError()
        val deadline = startMs + timeoutMs
        var firstMarksheetReadySeenAt = 0L

        while (System.currentTimeMillis() < deadline) {
            if (isAborted()) {
                return SimpleOutcome(false, "Stopped by user")
            }
            if (capturedPdfDownloadUrl.get() != null) {
                return SimpleOutcome(true, "")
            }
            // Error callbacks are shared by the main portal WebView and its popup WebView.
            // The table WebView can report a navigation/cache error while the student's marksheet
            // has already opened successfully in the popup. Do not abort from a global timestamp;
            // inspect the actual active marksheet WebView below and only fail if THAT page is an error.
            val targetView = activeMarksheetWebView(webView)
            val raw = evalJs(targetView, PortalHrpcDomScripts.checkMarksheetRenderJs())
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            if (obj != null) {
                if (obj.optBoolean("hasAuthForm", false)) {
                    return SimpleOutcome(false, "Portal session expired. Please log in again.")
                }
                if (obj.optBoolean("hasForbidden", false)) {
                    return SimpleOutcome(false, "Portal blocked this request (403 Forbidden)")
                }
                val pageUrl = obj.optString("url", "")
                if (obj.optBoolean("hasPortalError", false)) {
                    markUrlAsDbError(pageUrl)
                    return SimpleOutcome(
                        false,
                        "Portal database error displayed. Recovering cleanly...",
                        isCacheMiss = true
                    )
                }
                if (pageUrl.contains("chromewebdata", ignoreCase = true)) {
                    val bodyCheck = evalJs(
                        targetView,
                        "(function(){var t=(document.body&&document.body.innerText)||'';return /ERR_CACHE_MISS|Confirm Form Resubmission|Webpage not available/i.test(t)?'CACHE_MISS':'OK';})();"
                    )
                    if (bodyCheck.contains("CACHE_MISS")) {
                        notifyWebViewCacheMiss()
                        return SimpleOutcome(false, "Portal form cache miss (ERR_CACHE_MISS)", isCacheMiss = true)
                    }
                }
                val readyState = obj.optString("readyState", "")
                val looksLikeMarksheet = obj.optBoolean("looksLikeMarksheet", false)
                val spinner = obj.optBoolean("spinnerVisible", false)
                val pendingImgs = obj.optInt("pendingImgs", 0)
                if (readyState == "complete" && looksLikeMarksheet && !spinner) {
                    if (firstMarksheetReadySeenAt == 0L) {
                        firstMarksheetReadySeenAt = System.currentTimeMillis()
                    }
                    // Allow slow decorative icons at most 2.5s before completing marksheet capture
                    if (pendingImgs == 0 || (System.currentTimeMillis() - firstMarksheetReadySeenAt) >= 2_500L) {
                        return SimpleOutcome(true, "")
                    }
                }
            }
            delay(220L)
        }
        return SimpleOutcome(false, "Timed out waiting for marksheet to render")
    }

    private enum class TableWaitResult { READY, CACHE_MISS, TIMEOUT }

    private suspend fun waitForStudentTableVisibleWithAbort(
        webView: WebView,
        timeoutMs: Long,
        stepStartMs: Long,
        isAborted: () -> Boolean
    ): TableWaitResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAborted()) return TableWaitResult.TIMEOUT
            if (lastWebViewCacheMissAtMs >= stepStartMs) {
                return TableWaitResult.CACHE_MISS
            }
            val currentUrl = withContext(Dispatchers.Main) { webView.url.orEmpty() }
            if (currentUrl.contains("chromewebdata", ignoreCase = true) || currentUrl == "about:blank") {
                val errCheck = evalJs(
                    webView,
                    "(function(){var t=(document.body&&document.body.innerText)||'';return /ERR_CACHE_MISS|Webpage not available|Confirm Form Resubmission/i.test(t)?'CACHE_MISS':'OTHER';})();"
                )
                if (errCheck.contains("CACHE_MISS")) {
                    historyGoBackProducesCacheMiss = true
                    return TableWaitResult.CACHE_MISS
                }
            }
            val scan = scanTableInternal(webView)
            if (scan.dbError) {
                return TableWaitResult.CACHE_MISS
            }
            if (scan.ok && scan.students.isNotEmpty()) {
                return TableWaitResult.READY
            }
            delay(240L)
        }
        return TableWaitResult.TIMEOUT
    }

    private suspend fun buildCleanRecoveryUrls(webView: WebView, snapObj: JSONObject): List<String> {
        val candidates = linkedSetOf<String>()
        val historyUrls = withContext(Dispatchers.Main) {
            val urls = mutableListOf<String>()
            runCatching {
                val bf = webView.copyBackForwardList()
                for (i in (bf.currentIndex - 1) downTo 0) {
                    val u = bf.getItemAtIndex(i)?.url.orEmpty().trim()
                    if (u.startsWith("http", ignoreCase = true)) {
                        urls.add(u)
                    }
                }
            }
            urls
        }

        fun addCandidate(u: String?) {
            val cleaned = u?.trim().orEmpty()
            if (!cleaned.startsWith("http", ignoreCase = true)) return
            if (cleaned.contains("chromewebdata", ignoreCase = true)) return
            if (failedDbGetUrls.contains(cleaned)) return
            if (Regex("(?:log[-_]?out|sign[-_]?out|login|signin)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) return
            if (Regex("/(?:print|pdf|preview|preview_print|view_hrpc|print_hrpc)(?:/|\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)) return
            candidates.add(cleaned)
        }

        addCandidate(snapObj.optString("hrpcMenuHref", ""))
        addCandidate(lastHrpcMenuHref)
        historyUrls.forEach { addCandidate(it) }
        addCandidate(lastCleanHrpcUrl)
        addCandidate(snapObj.optString("referrer", ""))
        addCandidate(snapObj.optString("url", ""))
        addCandidate(MainActivity.PORTAL_URL)
        return candidates.toList()
    }

    private suspend fun recoverStudentTableFromSnapshot(
        webView: WebView,
        targetPageNumber: Int,
        isAborted: () -> Boolean
    ): Boolean {
        if (isAborted()) return false
        val snapStr = lastHrpcSnapshotRef.get()
        val snapObj = runCatching { JSONObject(snapStr ?: "{}") }.getOrNull() ?: JSONObject()

        pendingHrpcRestoreFromBack = false
        clearWebViewTransientError()

        // Inspect current DOM first
        val pageProbe = evalJs(
            webView,
            """
            (function() {
                var bodyTxt = (document.body && document.body.innerText) ? document.body.innerText : '';
                var hasErr = /ERR_CACHE_MISS|Webpage not available|Confirm Form Resubmission|An\s+Error\s+Was\s+Encountered|selected\s+a\s+database\s+type|A\s+PHP\s+Error\s+was\s+encountered/i.test(bodyTxt) ||
                    location.href.indexOf('chromewebdata') !== -1 || location.href === 'about:blank';
                var hasDbErr = /An\s+Error\s+Was\s+Encountered|selected\s+a\s+database\s+type|A\s+PHP\s+Error\s+was\s+encountered/i.test(bodyTxt);
                var hasStudentTable = !!document.querySelector('table[data-bsp-student-table="true"]') ||
                    (/Showing\s+\d+\s+to\s+\d+\s+of\s+\d+\s+entries/i.test(bodyTxt) && !/Summative\s+Evaluation/i.test(bodyTxt));
                var onMarksheet = !hasStudentTable && /Summative\s+Evaluation|Progress\s+Report|Holistic\s+Report/i.test(bodyTxt);
                var hasSelects = document.querySelectorAll('select').length >= 2;
                return JSON.stringify({ url: location.href, hasErr: hasErr, hasDbErr: hasDbErr, hasStudentTable: hasStudentTable, onMarksheet: onMarksheet, hasSelects: hasSelects });
            })();
            """.trimIndent()
        )
        val probeObj = runCatching { JSONObject(pageProbe) }.getOrNull()
        val curUrl = probeObj?.optString("url", "").orEmpty()
        val hasErr = probeObj?.optBoolean("hasErr", true) ?: true
        val hasDbErr = probeObj?.optBoolean("hasDbErr", false) ?: false
        val hasTable = probeObj?.optBoolean("hasStudentTable", false) ?: false
        val onMarksheet = probeObj?.optBoolean("onMarksheet", false) ?: false
        val hasSelects = probeObj?.optBoolean("hasSelects", false) ?: false

        if (hasDbErr) {
            markUrlAsDbError(curUrl)
        }

        if (hasTable && !hasErr) {
            return navigateToExactTablePageIfNeeded(webView, targetPageNumber, isAborted)
        }

        if (hasErr || onMarksheet || !hasSelects) {
            val recoveryUrls = buildCleanRecoveryUrls(webView, snapObj)
            var reachedFilterOrTable = false

            for (candidateUrl in recoveryUrls) {
                if (isAborted()) return false
                val loadStartMs = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    webView.stopLoading()
                    webView.loadUrl(candidateUrl)
                }
                val loadDeadline = loadStartMs + 10_000L
                var sawSelectsOnCandidate = false
                var sawTableOnCandidate = false
                var sawDbErrOnCandidate = false
                var triedMenuClick = false

                while (System.currentTimeMillis() < loadDeadline) {
                    if (isAborted()) return false
                    delay(260L)
                    val readyJson = evalJs(
                        webView,
                        """
                        (function(){
                            var t=(document.body&&document.body.innerText)||'';
                            var hasDbErr=/An\s+Error\s+Was\s+Encountered|selected\s+a\s+database\s+type|A\s+PHP\s+Error\s+was\s+encountered/i.test(t);
                            var hasErrPage=/ERR_CACHE_MISS|Webpage not available|Confirm Form Resubmission/i.test(t);
                            return JSON.stringify({
                                ready: document.readyState==='complete',
                                auth: !!document.querySelector('input[type="password"],img[src*="captcha" i]'),
                                dbErr: hasDbErr || hasErrPage,
                                selects: document.querySelectorAll('select').length,
                                table: /Showing\s+\d+\s+to\s+\d+/i.test(t) && !/Summative\s+Evaluation/i.test(t)
                            });
                        })();
                        """.trimIndent()
                    )
                    val rObj = runCatching { JSONObject(readyJson) }.getOrNull()
                    if (rObj?.optBoolean("auth", false) == true) return false
                    if (rObj?.optBoolean("dbErr", false) == true) {
                        markUrlAsDbError(candidateUrl)
                        sawDbErrOnCandidate = true
                        break
                    }
                    if (rObj?.optBoolean("table", false) == true) {
                        sawTableOnCandidate = true
                        break
                    }
                    if (rObj?.optBoolean("ready", false) == true && rObj.optInt("selects", 0) >= 2) {
                        sawSelectsOnCandidate = true
                        break
                    }
                    if (rObj?.optBoolean("ready", false) == true && !triedMenuClick) {
                        triedMenuClick = true
                        // If we landed on portal dashboard, click the HRPC / Marksheet menu link automatically
                        val menuRes = evalJs(webView, PortalHrpcDomScripts.openHrpcMenuFromDashboardJs())
                        val clicked = runCatching { JSONObject(menuRes).optBoolean("clicked", false) }.getOrDefault(false)
                        if (clicked) {
                            delay(500L)
                        }
                    }
                }

                if (sawTableOnCandidate) {
                    return navigateToExactTablePageIfNeeded(webView, targetPageNumber, isAborted)
                }
                if (sawSelectsOnCandidate) {
                    reachedFilterOrTable = true
                    break
                }
                if (!sawDbErrOnCandidate) {
                    // Check one final time before moving to next candidate URL
                    val finalCheck = scanTableInternal(webView)
                    if (finalCheck.ok && finalCheck.students.isNotEmpty()) {
                        return navigateToExactTablePageIfNeeded(webView, targetPageNumber, isAborted)
                    }
                }
            }

            if (!reachedFilterOrTable) {
                val selCheck = evalJs(webView, "(function(){return String(document.querySelectorAll('select').length);})();")
                if ((selCheck.trim().toIntOrNull() ?: 0) < 2) {
                    return false
                }
            }
        }

        if (isAborted()) return false
        if (!snapStr.isNullOrBlank()) {
            evalJs(webView, PortalHrpcDomScripts.restoreHrpcFiltersJs(snapStr))
        }
        val appeared = waitForStudentTableVisible(webView, timeoutMs = 15_000L, isAborted = isAborted)
        if (!appeared) return false
        return navigateToExactTablePageIfNeeded(webView, targetPageNumber, isAborted)
    }

    private suspend fun navigateToExactTablePageIfNeeded(
        webView: WebView,
        targetPage: Int,
        isAborted: () -> Boolean
    ): Boolean {
        val currentScan = scanTableInternal(webView)
        if (!currentScan.ok) return false
        if (currentScan.currentPage == targetPage || targetPage <= 1) return true

        var steps = 0
        var latestPage = currentScan.currentPage
        while (latestPage != targetPage && steps < 30) {
            if (isAborted()) return false
            steps++
            val clickJson = evalJs(webView, PortalHrpcDomScripts.clickPaginationPageJs(targetPage, "exact"))
            val clickObj = runCatching { JSONObject(clickJson) }.getOrNull()
            if (clickObj?.optBoolean("alreadyOnPage", false) == true) return true
            if (clickObj?.optBoolean("clicked", false) != true) return false

            delay(320L)
            var changed = false
            val deadline = System.currentTimeMillis() + 7_000L
            while (System.currentTimeMillis() < deadline) {
                if (isAborted()) return false
                val afterScan = scanTableInternal(webView)
                if (afterScan.ok && afterScan.currentPage == targetPage) {
                    return true
                }
                if (afterScan.ok && afterScan.currentPage != latestPage) {
                    latestPage = afterScan.currentPage
                    changed = true
                    break
                }
                delay(220L)
            }
            if (!changed) break
        }
        return scanTableInternal(webView).currentPage == targetPage
    }

    private suspend fun returnToStudentTableFromMarksheet(
        webView: WebView,
        targetPageNumber: Int,
        isAborted: () -> Boolean
    ) {
        if (activePopupWebViewProvider() != null) {
            closePopupIfOpen()
            val scanAfterClose = scanTableInternal(webView)
            if (scanAfterClose.ok && scanAfterClose.students.isNotEmpty()) {
                navigateToExactTablePageIfNeeded(webView, targetPageNumber, isAborted)
                return
            }
            recoverStudentTableFromSnapshot(webView, targetPageNumber, isAborted)
            return
        }

        val inPageRaw = evalJs(webView, PortalHrpcDomScripts.tryCloseInPageMarksheetOrModalJs())
        val inPageObj = runCatching { JSONObject(inPageRaw) }.getOrNull()
        if (inPageObj?.optBoolean("handled", false) == true) {
            delay(320L)
            ensureOnTablePage(webView, targetPageNumber, isAborted)
            return
        }

        if (!historyGoBackProducesCacheMiss && webView.canGoBack()) {
            val goBackStartMs = System.currentTimeMillis()
            pendingHrpcRestoreFromBack = true
            clearWebViewTransientError()
            withContext(Dispatchers.Main) { webView.goBack() }
            when (waitForStudentTableVisibleWithAbort(webView, timeoutMs = 10_000L, stepStartMs = goBackStartMs, isAborted = isAborted)) {
                TableWaitResult.READY -> {
                    pendingHrpcRestoreFromBack = false
                    navigateToExactTablePageIfNeeded(webView, targetPageNumber, isAborted)
                    return
                }
                TableWaitResult.CACHE_MISS -> {
                    historyGoBackProducesCacheMiss = true
                    recoverStudentTableFromSnapshot(webView, targetPageNumber, isAborted)
                    return
                }
                TableWaitResult.TIMEOUT -> {
                    // Fall through to clean GET + filter restore recovery
                }
            }
        }

        recoverStudentTableFromSnapshot(webView, targetPageNumber, isAborted)
    }

    private suspend fun ensureOnTablePage(
        webView: WebView,
        targetPage: Int,
        isAborted: () -> Boolean
    ): Boolean {
        if (isAborted()) return false
        val scanNow = scanTableInternal(webView)
        if (scanNow.ok && scanNow.students.isNotEmpty()) {
            return navigateToExactTablePageIfNeeded(webView, targetPage, isAborted)
        }
        return recoverStudentTableFromSnapshot(webView, targetPage, isAborted)
    }

    private suspend fun advanceToNextTablePage(
        webView: WebView,
        prevPage: Int,
        isAborted: () -> Boolean
    ): Boolean {
        if (isAborted()) return false
        val clickJson = evalJs(webView, PortalHrpcDomScripts.clickPaginationPageJs(prevPage + 1, "next"))
        val obj = runCatching { JSONObject(clickJson) }.getOrNull() ?: return false
        if (!obj.optBoolean("ok", false)) return false
        val prevFirstRow = obj.optString("prevFirstRow", "").trim()

        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            if (isAborted()) return false
            delay(300L)
            val after = scanTableInternal(webView)
            if (after.ok) {
                if (after.currentPage > prevPage) return true
                val firstAfter = after.students.firstOrNull()?.let { "${it.rollNo} ${it.name}".trim() }.orEmpty()
                if (prevFirstRow.isNotBlank() && firstAfter.isNotBlank() &&
                    !prevFirstRow.contains(firstAfter, ignoreCase = true)
                ) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun waitForStudentTableVisible(
        webView: WebView,
        timeoutMs: Long,
        isAborted: () -> Boolean
    ): Boolean {
        val startMs = System.currentTimeMillis()
        val deadline = startMs + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAborted()) return false
            if (lastWebViewCacheMissAtMs >= startMs) return false
            val scan = scanTableInternal(webView)
            if (scan.dbError) {
                markUrlAsDbError(withContext(Dispatchers.Main) { webView.url })
                return false
            }
            if (scan.ok && scan.students.isNotEmpty()) return true
            delay(280L)
        }
        return false
    }

    private suspend fun scanTableInternal(webView: WebView): TableScanOutcome {
        val raw = evalJs(webView, PortalHrpcDomScripts.scanHrpcTableJs())
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return TableScanOutcome(ok = false, error = "Unable to inspect portal page.")
        if (!obj.optBoolean("ok", false)) {
            val isDbErr = obj.optBoolean("dbError", false)
            if (isDbErr) {
                markUrlAsDbError(obj.optString("url", ""))
            }
            return TableScanOutcome(
                ok = false,
                sessionExpired = obj.optBoolean("sessionExpired", false),
                dbError = isDbErr,
                error = obj.optString("reason", "Student table not detected.")
            )
        }
        val arr = obj.optJSONArray("students") ?: JSONArray()
        val list = mutableListOf<HrpcStudentEntry>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            list.add(
                HrpcStudentEntry(
                    rowIndex = item.optInt("rowIndex", i),
                    rollNo = item.optString("rollNo", ""),
                    name = item.optString("name", ""),
                    hasView = item.optBoolean("hasView", false),
                    hasPrint = item.optBoolean("hasPrint", false),
                    viewHref = item.optString("viewHref", ""),
                    printHref = item.optString("printHref", "")
                )
            )
        }
        return TableScanOutcome(
            ok = true,
            currentPage = obj.optInt("currentPage", 1).coerceAtLeast(1),
            totalPages = obj.optInt("totalPages", 1).coerceAtLeast(1),
            totalEntries = obj.optInt("totalEntries", list.size).coerceAtLeast(list.size),
            hasNextPage = obj.optBoolean("hasNextPage", false),
            students = list
        )
    }

    private suspend fun saveHrpcSnapshotNow(webView: WebView) {
        val snap = evalJs(webView, PortalHrpcDomScripts.snapshotHrpcStateJs())
        updateLastHrpcSnapshot(snap)
    }

    private fun effectiveSelectedPages(): List<Int> {
        val sel = _bulkState.value.selectedPages
        return if (sel.isEmpty()) listOf(1, 2, 3, 4, 5, 6) else sel.sorted()
    }

    private fun updateFailedReason(item: BulkFailedItem, newReason: String) {
        _bulkState.update { st ->
            val updated = st.failedList.map {
                if (it.rollNo == item.rollNo && it.studentName == item.studentName && it.pageNumber == item.pageNumber) {
                    it.copy(reason = newReason)
                } else {
                    it
                }
            }
            st.copy(failedList = updated, failedDownloadsCount = updated.size, statusMessage = newReason)
        }
    }

    private fun updateResultFailedReason(item: ResultFailedItem, newReason: String) {
        _resultState.update { st ->
            val updated = st.failedList.map {
                if (it.rollNo == item.rollNo && it.studentName == item.studentName && it.pageNumber == item.pageNumber) {
                    it.copy(reason = newReason)
                } else {
                    it
                }
            }
            st.copy(failedList = updated, failedCount = updated.size, statusMessage = newReason)
        }
    }

    private fun mergeOrReplaceResultRow(
        existing: List<StudentSummativeResultRow>,
        incoming: StudentSummativeResultRow
    ): List<StudentSummativeResultRow> {
        val key = "${incoming.rollNo.trim().lowercase(Locale.US)}|${incoming.studentName.trim().lowercase(Locale.US)}"
        val idx = existing.indexOfFirst {
            "${it.rollNo.trim().lowercase(Locale.US)}|${it.studentName.trim().lowercase(Locale.US)}" == key
        }
        return if (idx >= 0) {
            existing.toMutableList().apply { this[idx] = incoming }
        } else {
            existing + incoming
        }
    }

    private suspend fun waitWhilePausedBulk(): Boolean {
        while (pauseBulkRequested && !stopBulkRequested) {
            delay(300L)
        }
        return !stopBulkRequested
    }

    private suspend fun waitWhilePausedResult(): Boolean {
        while (pauseResultRequested && !stopResultRequested) {
            delay(300L)
        }
        return !stopResultRequested
    }

    private fun formatCleanNumber(v: Double): String {
        return if (v == v.roundToInt().toDouble()) {
            v.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        }
    }

    private fun nowTimeStr(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun decodeDataUriPdfBytes(dataUri: String): ByteArray? {
        val commaIdx = dataUri.indexOf(',')
        if (commaIdx <= 0 || commaIdx + 1 >= dataUri.length) return null
        val b64 = dataUri.substring(commaIdx + 1).trim()
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun readBlobPdfBytesFromWebView(webView: WebView, blobUrl: String): ByteArray? {
        val quoted = JSONObject.quote(blobUrl)
        val startJson = evalJs(
            webView,
            """
            (function() {
                try {
                    window.__bspBlobDone = false;
                    window.__bspBlobB64 = '';
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', $quoted, true);
                    xhr.responseType = 'blob';
                    xhr.onload = function() {
                        if (xhr.status === 200 || xhr.status === 0) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var res = String(reader.result || '');
                                var idx = res.indexOf(',');
                                window.__bspBlobB64 = idx >= 0 ? res.slice(idx + 1) : '';
                                window.__bspBlobDone = true;
                            };
                            reader.readAsDataURL(xhr.response);
                        } else {
                            window.__bspBlobDone = true;
                        }
                    };
                    xhr.onerror = function() { window.__bspBlobDone = true; };
                    xhr.send();
                    return 'STARTED';
                } catch (e) {
                    return 'ERR';
                }
            })();
            """.trimIndent()
        )
        if (!startJson.contains("STARTED")) return null
        val deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline) {
            delay(180L)
            val statusJson = evalJs(
                webView,
                "(function(){return JSON.stringify({done:!!window.__bspBlobDone,data:window.__bspBlobB64||''});})();"
            )
            val stObj = runCatching { JSONObject(statusJson) }.getOrNull() ?: continue
            if (stObj.optBoolean("done", false)) {
                val b64 = stObj.optString("data", "")
                if (b64.isNotBlank()) {
                    return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
                return null
            }
        }
        return null
    }

    private fun downloadBytesWithCookies(urlStr: String, cookies: String?, userAgent: String?): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            if (!cookies.isNullOrBlank()) setRequestProperty("Cookie", cookies)
            if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("Download HTTP $code")
            }
            val baos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                input.copyTo(baos)
            }
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) error("Empty PDF response")
            bytes
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun evalJs(webView: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { raw ->
                val decoded = if (raw == null || raw == "null") {
                    "{}"
                } else {
                    try {
                        if (raw.startsWith("\"") && raw.endsWith("\"")) {
                            JSONArray("[$raw]").getString(0)
                        } else {
                            raw
                        }
                    } catch (_: Exception) {
                        raw
                    }
                }
                if (cont.isActive) cont.resume(decoded)
            }
        }

    private data class SimpleOutcome(
        val ok: Boolean,
        val error: String,
        val isCacheMiss: Boolean = false
    )
    private data class TableScanOutcome(
        val ok: Boolean,
        val sessionExpired: Boolean = false,
        val dbError: Boolean = false,
        val currentPage: Int = 1,
        val totalPages: Int = 1,
        val totalEntries: Int = 0,
        val hasNextPage: Boolean = false,
        val students: List<HrpcStudentEntry> = emptyList(),
        val error: String = ""
    )
}
