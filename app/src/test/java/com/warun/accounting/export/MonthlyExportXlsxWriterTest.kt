package com.warun.accounting.export

import com.warun.accounting.data.export.MonthlyExportTestFixtures
import com.warun.accounting.data.export.ExcelIntegerContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class MonthlyExportXlsxWriterTest {
    private val snapshot = MonthlyExportTestFixtures.snapshot()

    @Test
    fun dailyWorkbookHasValidOoxmlPartsNumericMoneyCellsAndJapaneseStrings() {
        val bytes = workbookBytes { MonthlyExportXlsxWriter.writeDailyReport(snapshot, it) }
        val entries = unzip(bytes)

        assertRequiredEntries(entries)
        entries.values.forEach(::parseXml)
        val sheet = parseXml(entries.getValue("xl/worksheets/sheet1.xml"))
        assertEquals("現金売上", inlineText(sheet, "B1"))
        assertEquals("1000", numericValue(sheet, "B2"))
        assertEquals("1900", numericValue(sheet, "G2"))
        assertEquals("3900", numericValue(sheet, "G4"))
        assertFalse(cell(sheet, "B2").hasAttribute("t"))
        assertEquals("2", cell(sheet, "B2").getAttribute("s"))
        assertEquals("1", sheet.getElementsByTagName("autoFilter").length.toString())
        assertEquals("1", sheet.getElementsByTagName("pane").length.toString())
    }

    @Test
    fun expenseWorkbookContainsOnlyRequiredColumnsAndExactActiveTotal() {
        val bytes = workbookBytes { MonthlyExportXlsxWriter.writeExpenseDetail(snapshot, it) }
        val entries = unzip(bytes)
        val sheet = parseXml(entries.getValue("xl/worksheets/sheet1.xml"))

        assertEquals(
            listOf("日付", "支出先", "金額", "内容・用途", "支払方法", "Evidence件数"),
            (1..6).map { inlineText(sheet, "${columnName(it)}1") }
        )
        assertEquals("2150", numericValue(sheet, "C8"))
        assertTrue(entries.getValue("xl/styles.xml").toString(Charsets.UTF_8).contains("#,##0"))
        assertFalse(entries.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).contains("勘定科目"))
        assertFalse(entries.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).contains("カテゴリ"))
        assertFalse(entries.getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8).contains("取消店"))
    }

    @Test
    fun repeatedWorkbookGenerationIsByteForByteDeterministic() {
        val first = workbookBytes { MonthlyExportXlsxWriter.writeDailyReport(snapshot, it) }
        val second = workbookBytes { MonthlyExportXlsxWriter.writeDailyReport(snapshot, it) }

        assertArrayEquals(first, second)
    }

    @Test
    fun writerDefensivelyRejectsInexactIntegerButAcceptsNegativeExactBoundary() {
        val row = snapshot.dailyReports.first()
        val invalid = snapshot.copy(
            dailyReports = listOf(
                row.copy(
                    cashSales = ExcelIntegerContract.MaxExactInteger + 1L,
                    salesTotal = ExcelIntegerContract.MaxExactInteger + 1L
                )
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            workbookBytes { MonthlyExportXlsxWriter.writeDailyReport(invalid, it) }
        }

        val invalidNegative = invalid.copy(
            dailyReports = listOf(
                row.copy(
                    cashSales = ExcelIntegerContract.MinExactInteger - 1L,
                    salesTotal = ExcelIntegerContract.MinExactInteger - 1L
                )
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            workbookBytes { MonthlyExportXlsxWriter.writeDailyReport(invalidNegative, it) }
        }

        val boundary = snapshot.copy(
            dailyReports = listOf(
                row.copy(
                    cashSales = ExcelIntegerContract.MinExactInteger,
                    cardSales = 0L,
                    qrSales = 0L,
                    accountsReceivableSales = 0L,
                    otherSales = 0L,
                    salesTotal = ExcelIntegerContract.MinExactInteger
                )
            ),
            dailyReportTotals = snapshot.dailyReportTotals.copy(
                cashSales = ExcelIntegerContract.MinExactInteger,
                cardSales = 0L,
                qrSales = 0L,
                accountsReceivableSales = 0L,
                otherSales = 0L,
                salesTotal = ExcelIntegerContract.MinExactInteger
            )
        )
        val sheet = parseXml(
            unzip(workbookBytes { MonthlyExportXlsxWriter.writeDailyReport(boundary, it) })
                .getValue("xl/worksheets/sheet1.xml")
        )
        assertEquals(ExcelIntegerContract.MinExactInteger.toString(), numericValue(sheet, "B2"))
    }

    @Test
    fun generatedSamplesArePlacedOnlyUnderIgnoredBuildDirectory() {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val appDirectory = if (workingDirectory.name == "app") {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
        val directory = File(appDirectory, "build/monthly-export-samples").apply { mkdirs() }
        val daily = File(directory, snapshot.dailyReportFileName)
        val expense = File(directory, snapshot.expenseDetailFileName)
        daily.outputStream().use { MonthlyExportXlsxWriter.writeDailyReport(snapshot, it) }
        expense.outputStream().use { MonthlyExportXlsxWriter.writeExpenseDetail(snapshot, it) }

        assertTrue(daily.isFile && daily.length() > 0L)
        assertTrue(expense.isFile && expense.length() > 0L)
        assertFalse(daily.absolutePath.contains("${File.separator}output${File.separator}"))
        assertFalse(expense.absolutePath.contains("${File.separator}output${File.separator}"))
    }

    private fun workbookBytes(write: (ByteArrayOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also(write).toByteArray()

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes())
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun assertRequiredEntries(entries: Map<String, ByteArray>) {
        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            "xl/worksheets/sheet1.xml"
        ).forEach { assertNotNull(entries[it]) }
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun cell(document: org.w3c.dom.Document, reference: String): Element {
        val cells = document.getElementsByTagName("c")
        for (index in 0 until cells.length) {
            val cell = cells.item(index) as Element
            if (cell.getAttribute("r") == reference) return cell
        }
        error("Cell $reference not found")
    }

    private fun inlineText(document: org.w3c.dom.Document, reference: String): String =
        cell(document, reference).getElementsByTagName("t").item(0).textContent

    private fun numericValue(document: org.w3c.dom.Document, reference: String): String =
        cell(document, reference).getElementsByTagName("v").item(0).textContent

    private fun columnName(column: Int): Char = ('A'.code + column - 1).toChar()
}
