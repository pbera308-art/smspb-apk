package com.bspsms.android

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

data class SummativeTermMarks(
    val ben: String = "",
    val eng: String = "",
    val evs: String = "",
    val math: String = "",
    val health: String = "",
    val art: String = "",
    val total: String = ""
)

data class DynamicSubjectMarks(
    val label: String,
    val t1: String = "",
    val t2: String = "",
    val t3: String = "",
    val total: String = ""
)

data class StudentSummativeResultRow(
    val rollNo: String,
    val studentName: String,
    val term1: SummativeTermMarks,
    val term2: SummativeTermMarks,
    val term3: SummativeTermMarks,
    val benTotal: String,
    val engTotal: String,
    val evsTotal: String,
    val mathTotal: String,
    val healthTotal: String,
    val artTotal: String,
    val grandTotal: String,
    val percentage: String,
    var position: String = "",
    val dynamicSubjects: Map<String, DynamicSubjectMarks> = emptyMap(),
    val term1Max: Double = 20.0,
    val term2Max: Double = 30.0,
    val term3Max: Double = 50.0
) {
    fun grandTotalNumeric(): Double {
        return grandTotal.toDoubleOrNull() ?: 0.0
    }
}

object ResultXlsxExporter {

    // Workbook columns are generated from the subjects actually present in the marksheet.
    private val COLUMN_HEADERS = listOf("ROLL NO", "STUDENT NAME", "GRAND TOTAL", "PERCENTAGE", "POSITION")

    fun recalculatePositions(rows: List<StudentSummativeResultRow>): List<StudentSummativeResultRow> {
        val ranked = rows
            .mapIndexed { idx, row -> idx to row.grandTotalNumeric() }
            .sortedByDescending { it.second }

        val result = rows.map { it.copy() }
        var currentRank = 0
        var lastScore = Double.NaN
        ranked.forEachIndexed { sortedPos, (origIdx, score) ->
            if (score <= 0.0) {
                if (result[origIdx].position.isBlank()) {
                    result[origIdx].position = ""
                }
            } else {
                if (lastScore.isNaN() || score != lastScore) {
                    currentRank = sortedPos + 1
                    lastScore = score
                }
                result[origIdx].position = currentRank.toString()
            }
        }
        return result
    }

    fun writeWorkbook(
        rowsInput: List<StudentSummativeResultRow>,
        sheetTitle: String = "Summative Evaluation",
        outStream: OutputStream
    ) {
        val rows = recalculatePositions(rowsInput)
        val cleanTitle = sheetTitle
            .replace(Regex("[:\\\\/?*\\[\\]]"), " ")
            .trim()
            .take(31)
            .ifBlank { "Summative Result" }

        ZipOutputStream(outStream).use { zip ->
            writeZipEntry(zip, "[Content_Types].xml", CONTENT_TYPES_XML)
            writeZipEntry(zip, "_rels/.rels", ROOT_RELS_XML)
            writeZipEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS_XML)
            writeZipEntry(zip, "xl/workbook.xml", buildWorkbookXml(cleanTitle))
            writeZipEntry(zip, "xl/styles.xml", STYLES_XML)
            writeZipEntry(zip, "xl/worksheets/sheet1.xml", buildSheetXml(rows))
            zip.finish()
            zip.flush()
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildWorkbookXml(sheetName: String): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="${escapeXml(sheetName)}" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
    }

    private fun buildSheetXml(rows: List<StudentSummativeResultRow>): String {
        val dynamicKeys = rows.flatMap { it.dynamicSubjects.keys }.distinct().sorted()
        val dynamicColumns = dynamicKeys.flatMap { key ->
            val label = rows.firstNotNullOfOrNull { it.dynamicSubjects[key]?.label } ?: key
            val first = rows.firstOrNull()
            listOf(
                "$label - 1ST SE (${first?.term1Max?.let { formatMax(it) } ?: ""})",
                "$label - 2ND SE (${first?.term2Max?.let { formatMax(it) } ?: ""})",
                "$label - 3RD SE (${first?.term3Max?.let { formatMax(it) } ?: ""})",
                "$label - TOTAL"
            )
        }
        val allHeaders = COLUMN_HEADERS + dynamicColumns
        val sb = StringBuilder(16384)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        sb.append("""<sheetViews><sheetView tabSelected="1" workbookViewId="0"><pane xSplit="2" ySplit="2" topLeftCell="C3" activePane="bottomRight" state="frozen"/></sheetView></sheetViews>""")
        sb.append("""<cols><col min="1" max="1" width="11" customWidth="1"/><col min="2" max="2" width="26" customWidth="1"/>""")
        if (allHeaders.size > 2) sb.append("""<col min="3" max="${allHeaders.size}" width="14" customWidth="1"/>""")
        sb.append("</cols><sheetData>")

        // Group header row: one group per real subject found in the source marksheet.
        sb.append("""<row r="1" ht="24" customHeight="1">""")
        sb.append(stringCell("A1", "STUDENT DETAILS", 1))
        sb.append(stringCell("B1", "", 1))
        sb.append(stringCell("C1", "FINAL RESULT", 1))
        sb.append(stringCell("D1", "", 1))
        sb.append(stringCell("E1", "", 1))
        dynamicKeys.forEachIndexed { index, key ->
            val label = rows.firstNotNullOfOrNull { it.dynamicSubjects[key]?.label } ?: key
            val col = 5 + index * 4
            sb.append(stringCell("${colLetter(col)}1", label, 5))
            for (offset in 1..3) sb.append(stringCell("${colLetter(col + offset)}1", "", 5))
        }
        sb.append("""</row><row r="2" ht="22" customHeight="1">""")
        allHeaders.forEachIndexed { index, header ->
            val style = if (index >= 5) 5 else 1
            sb.append(stringCell("${colLetter(index)}2", header, style))
        }
        sb.append("</row>")

        rows.forEachIndexed { rowIndex, row ->
            val rowNum = rowIndex + 3
            val values = mutableListOf(row.rollNo, row.studentName, row.grandTotal, row.percentage, row.position)
            dynamicKeys.forEach { key ->
                val subject = row.dynamicSubjects[key]
                values.addAll(listOf(subject?.t1.orEmpty(), subject?.t2.orEmpty(), subject?.t3.orEmpty(), subject?.total.orEmpty()))
            }
            sb.append("""<row r="$rowNum" ht="19" customHeight="1">""")
            values.forEachIndexed { colIndex, value ->
                val cellRef = "${colLetter(colIndex)}$rowNum"
                val style = when {
                    colIndex == 1 -> 7
                    colIndex == 2 -> 8
                    colIndex >= 5 && (colIndex - 5) % 4 == 3 -> 8
                    else -> 6
                }
                if (colIndex <= 1) sb.append(stringCell(cellRef, value, style))
                else sb.append(autoNumericOrStringCell(cellRef, value, style))
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        val mergeCount = 2 + dynamicKeys.size
        sb.append("""<mergeCells count="$mergeCount"><mergeCell ref="A1:B1"/><mergeCell ref="C1:E1"/>""")
        dynamicKeys.forEachIndexed { index, _ ->
            val startCol = 5 + index * 4
            sb.append("""<mergeCell ref="${colLetter(startCol)}1:${colLetter(startCol + 3)}1"/>""")
        }
        sb.append("</mergeCells></worksheet>")
        return sb.toString()
    }

    private fun formatMax(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun stringCell(ref: String, value: String, style: Int): String {
        return """<c r="$ref" s="$style" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c>"""
    }

    private fun autoNumericOrStringCell(ref: String, raw: String, style: Int): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return """<c r="$ref" s="$style" t="inlineStr"><is><t></t></is></c>"""
        val num = clean.removeSuffix("%").trim().toDoubleOrNull()
        return if (num != null && num.isFinite() && !clean.endsWith("%")) {
            val formatted = if (num == num.roundToInt().toDouble()) num.roundToInt().toString()
                else String.format(java.util.Locale.US, "%.2f", num)
            """<c r="$ref" s="$style"><v>$formatted</v></c>"""
        } else stringCell(ref, clean, style)
    }

    private fun colLetter(index0: Int): String {
        var idx = index0
        val sb = StringBuilder()
        while (idx >= 0) {
            sb.insert(0, ('A' + (idx % 26)))
            idx = (idx / 26) - 1
        }
        return sb.toString()
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    // Styles:
    // 0: default
    // 1: Dark Blue Header (#123B70, white bold, center)
    // 2: 1st Summative Header (#1D4ED8, white bold, center)
    // 3: 2nd Summative Header (#047857, white bold, center)
    // 4: 3rd Summative Header (#6D28D9, white bold, center)
    // 5: Subject Totals Header (#B45309, white bold, center)
    // 6: Data cell centered
    // 7: Data cell left-aligned (Student Name)
    // 8: Bold total/summary data cell centered
    private const val STYLES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="3">
    <font><sz val="11"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
  </fonts>
  <fills count="8">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF123B70"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1D4ED8"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF047857"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF6D28D9"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFB45309"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF8FAFC"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="9">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="6" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
    <xf numFmtId="0" fontId="2" fillId="7" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
  </cellXfs>
</styleSheet>"""
}
