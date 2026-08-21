package com.warun.accounting.export

import com.warun.accounting.data.export.MonthlyExportSnapshot
import com.warun.accounting.data.export.ExcelIntegerContract
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free OOXML writer for the two tax-accountant workbooks.
 *
 * All monetary values are written as numeric cells backed by the validated Long snapshot. No
 * formula recalculates accounting data inside Excel.
 */
object MonthlyExportXlsxWriter {
    private val dailyColumns = listOf(
        Column("日付", 13.0, CellStyle.Text),
        Column("現金売上", 15.0, CellStyle.Money),
        Column("クレジットカード売上", 23.0, CellStyle.Money),
        Column("QR決済売上", 16.0, CellStyle.Money),
        Column("売掛売上", 15.0, CellStyle.Money),
        Column("その他売上", 15.0, CellStyle.Money),
        Column("売上合計", 15.0, CellStyle.Money)
    )

    private val expenseColumns = listOf(
        Column("日付", 13.0, CellStyle.Text),
        Column("支出先", 24.0, CellStyle.Text),
        Column("金額", 15.0, CellStyle.Money),
        Column("内容・用途", 34.0, CellStyle.Text),
        Column("支払方法", 17.0, CellStyle.Text),
        Column("Evidence件数", 16.0, CellStyle.Number)
    )

    fun writeDailyReport(snapshot: MonthlyExportSnapshot, output: OutputStream) {
        val rows = snapshot.dailyReports.map { row ->
            listOf(
                Cell.Text(row.reportDate.toString()),
                Cell.Number(row.cashSales),
                Cell.Number(row.cardSales),
                Cell.Number(row.qrSales),
                Cell.Number(row.accountsReceivableSales),
                Cell.Number(row.otherSales),
                Cell.Number(row.salesTotal)
            )
        }
        val totals = snapshot.dailyReportTotals
        writeWorkbook(
            output = output,
            sheetName = "日報",
            columns = dailyColumns,
            rows = rows,
            totalRow = listOf(
                Cell.TotalText("月合計"),
                Cell.TotalNumber(totals.cashSales),
                Cell.TotalNumber(totals.cardSales),
                Cell.TotalNumber(totals.qrSales),
                Cell.TotalNumber(totals.accountsReceivableSales),
                Cell.TotalNumber(totals.otherSales),
                Cell.TotalNumber(totals.salesTotal)
            )
        )
    }

    fun writeExpenseDetail(snapshot: MonthlyExportSnapshot, output: OutputStream) {
        val rows = snapshot.expenses.map { row ->
            listOf(
                Cell.Text(row.expenseDate.toString()),
                Cell.Text(row.supplierName),
                Cell.Number(row.amount),
                Cell.Text(row.memo),
                Cell.Text(row.paymentMethod),
                Cell.Number(row.evidenceCount.toLong())
            )
        }
        writeWorkbook(
            output = output,
            sheetName = "支出明細",
            columns = expenseColumns,
            rows = rows,
            totalRow = listOf(
                Cell.TotalText("有効支出合計"),
                Cell.Blank,
                Cell.TotalNumber(snapshot.expenseTotal),
                Cell.Blank,
                Cell.Blank,
                Cell.Blank
            )
        )
    }

    private fun writeWorkbook(
        output: OutputStream,
        sheetName: String,
        columns: List<Column>,
        rows: List<List<Cell>>,
        totalRow: List<Cell>
    ) {
        require(columns.isNotEmpty() && columns.size <= 26)
        require(rows.all { it.size == columns.size })
        require(totalRow.size == columns.size)
        requireValidXmlText(sheetName)
        columns.forEach { requireValidXmlText(it.header) }

        val zip = ZipOutputStream(output, StandardCharsets.UTF_8).apply {
            setLevel(Deflater.DEFAULT_COMPRESSION)
        }
        zip.writeXml("[Content_Types].xml", contentTypesXml())
        zip.writeXml("_rels/.rels", packageRelationshipsXml())
        zip.writeXml("xl/workbook.xml", workbookXml(sheetName))
        zip.writeXml("xl/_rels/workbook.xml.rels", workbookRelationshipsXml())
        zip.writeXml("xl/styles.xml", stylesXml())
        zip.writeXml(
            "xl/worksheets/sheet1.xml",
            worksheetXml(columns, rows, totalRow)
        )
        zip.finish()
        zip.flush()
    }

    private fun worksheetXml(
        columns: List<Column>,
        rows: List<List<Cell>>,
        totalRow: List<Cell>
    ): String {
        val lastColumn = columnName(columns.size)
        val totalRowNumber = rows.size + 2
        val filterLastRow = maxOf(1, rows.size + 1)
        return buildString {
            append(XmlDeclaration)
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            append("<dimension ref=\"A1:").append(lastColumn).append(totalRowNumber).append("\"/>")
            append("<sheetViews><sheetView workbookViewId=\"0\">")
            append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>")
            append("</sheetView></sheetViews>")
            append("<sheetFormatPr defaultRowHeight=\"15\"/>")
            append("<cols>")
            columns.forEachIndexed { index, column ->
                val number = index + 1
                append("<col min=\"").append(number).append("\" max=\"").append(number)
                    .append("\" width=\"").append(column.width).append("\" customWidth=\"1\"/>")
            }
            append("</cols><sheetData>")
            append("<row r=\"1\" ht=\"22\" customHeight=\"1\">")
            columns.forEachIndexed { index, column ->
                appendInlineStringCell(index + 1, 1, column.header, HeaderStyle)
            }
            append("</row>")
            rows.forEachIndexed { rowIndex, cells ->
                val number = rowIndex + 2
                append("<row r=\"").append(number).append("\">")
                cells.forEachIndexed { columnIndex, cell ->
                    appendCell(columnIndex + 1, number, cell, columns[columnIndex].style)
                }
                append("</row>")
            }
            append("<row r=\"").append(totalRowNumber).append("\">")
            totalRow.forEachIndexed { columnIndex, cell ->
                appendCell(columnIndex + 1, totalRowNumber, cell, columns[columnIndex].style)
            }
            append("</row></sheetData>")
            append("<autoFilter ref=\"A1:").append(lastColumn).append(filterLastRow).append("\"/>")
            append("<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/>")
            append("</worksheet>")
        }
    }

    private fun StringBuilder.appendCell(
        column: Int,
        row: Int,
        cell: Cell,
        columnStyle: CellStyle
    ) {
        when (cell) {
            Cell.Blank -> Unit
            is Cell.Text -> appendInlineStringCell(column, row, cell.value, GeneralStyle)
            is Cell.Number -> appendNumberCell(
                column,
                row,
                cell.value,
                if (columnStyle == CellStyle.Money) MoneyStyle else GeneralStyle
            )
            is Cell.TotalText -> appendInlineStringCell(column, row, cell.value, TotalTextStyle)
            is Cell.TotalNumber -> appendNumberCell(
                column,
                row,
                cell.value,
                if (columnStyle == CellStyle.Money) TotalMoneyStyle else TotalNumberStyle
            )
        }
    }

    private fun StringBuilder.appendInlineStringCell(
        column: Int,
        row: Int,
        value: String,
        style: Int
    ) {
        requireValidXmlText(value)
        append("<c r=\"").append(columnName(column)).append(row)
            .append("\" s=\"").append(style).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
            .append(escapeXml(value))
            .append("</t></is></c>")
    }

    private fun StringBuilder.appendNumberCell(
        column: Int,
        row: Int,
        value: Long,
        style: Int
    ) {
        require(ExcelIntegerContract.isExact(value)) {
            "Excel numeric cell is outside the safe 15-digit integer range"
        }
        append("<c r=\"").append(columnName(column)).append(row)
            .append("\" s=\"").append(style).append("\"><v>").append(value).append("</v></c>")
    }

    private fun contentTypesXml() = XmlDeclaration +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private fun packageRelationshipsXml() = XmlDeclaration +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private fun workbookXml(sheetName: String) = XmlDeclaration +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<bookViews><workbookView/></bookViews><sheets>" +
        "<sheet name=\"${escapeXml(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/>" +
        "</sheets></workbook>"

    private fun workbookRelationshipsXml() = XmlDeclaration +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private fun stylesXml() = XmlDeclaration +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0\"/></numFmts>" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Meiryo\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Meiryo\"/></font></fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9EAF7\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right>" +
        "<top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"6\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\"/>" +
        "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"標準\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"

    private fun ZipOutputStream.writeXml(path: String, xml: String) {
        val entry = ZipEntry(path).apply { time = 0L }
        putNextEntry(entry)
        write(xml.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun columnName(column: Int): String {
        require(column in 1..26)
        return ('A'.code + column - 1).toChar().toString()
    }

    private fun requireValidXmlText(value: String) {
        require(value.all { character ->
            character == '\t' || character == '\n' || character == '\r' || character.code >= 0x20
        }) { "Export text contains a character that is invalid in XML" }
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                }
            )
        }
    }

    private data class Column(
        val header: String,
        val width: Double,
        val style: CellStyle
    )

    private enum class CellStyle { Text, Number, Money }

    private sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: Long) : Cell
        data class TotalText(val value: String) : Cell
        data class TotalNumber(val value: Long) : Cell
        data object Blank : Cell
    }

    private const val GeneralStyle = 0
    private const val HeaderStyle = 1
    private const val MoneyStyle = 2
    private const val TotalTextStyle = 3
    private const val TotalMoneyStyle = 4
    private const val TotalNumberStyle = 5
    private const val XmlDeclaration = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
}
