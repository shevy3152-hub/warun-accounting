package com.warun.accounting.data.export

import com.warun.accounting.data.local.ExpenseVisibilityRecord
import java.time.YearMonth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyExportSnapshotProviderTest {
    private val fixture = MonthlyExportTestFixtures

    @Test
    fun dailyReportTotalsMatchFormalBusinessMetricPath() {
        val snapshot = success(fixture.source())

        assertEquals(2, snapshot.dailyReports.size)
        assertEquals(3_900L, snapshot.dailyReportTotals.salesTotal)
        assertEquals(1_200L, snapshot.dailyReportTotals.cashSales)
        assertEquals(800L, snapshot.dailyReportTotals.cardSales)
        assertEquals(650L, snapshot.dailyReportTotals.qrSales)
        assertEquals(600L, snapshot.dailyReportTotals.accountsReceivableSales)
        assertEquals(650L, snapshot.dailyReportTotals.otherSales)
        assertEquals(1_900L, snapshot.dailyReports.first().salesTotal)
    }

    @Test
    fun expenseRowsContainOnlyCurrentActiveStateAndExactTotal() {
        val snapshot = success(fixture.source())

        assertEquals(6, snapshot.expenses.size)
        assertEquals(2_150L, snapshot.expenseTotal)
        assertTrue(snapshot.expenses.none { it.expenseId == "cancelled" })
        assertEquals(1, snapshot.expenses.count { it.expenseId == "edited" })
        val edited = snapshot.expenses.single { it.expenseId == "edited" }
        assertEquals("編集後の支出先", edited.supplierName)
        assertEquals("編集後の用途", edited.memo)
    }

    @Test
    fun supportedPaymentMethodsUseExistingDisplayNames() {
        val methods = success(fixture.source()).expenses.associate { it.expenseId to it.paymentMethod }

        assertEquals("現金", methods.getValue("cash"))
        assertEquals("クレジット", methods.getValue("credit"))
        assertEquals("電子マネー", methods.getValue("electronic"))
        assertEquals("掛け", methods.getValue("credit-purchase"))
        assertEquals("プリペイド", methods.getValue("prepaid"))
    }

    @Test
    fun storedEvidenceCountsExcludeCancelledExpense() {
        val snapshot = success(fixture.source())

        assertEquals(2, snapshot.expenses.single { it.expenseId == "cash" }.evidenceCount)
        assertTrue(snapshot.storedEvidence.all { it.expenseId == "cash" })
        assertEquals(2, snapshot.storedEvidence.size)
    }

    @Test
    fun duplicateDailyReportDateFailsWithoutGuessingMerge() {
        val source = fixture.source()
        val duplicate = source.dailyReports.first().copy(id = "duplicate")

        assertFailure(
            source.copy(dailyReports = source.dailyReports + duplicate),
            MonthlyExportValidationFailureReason.DUPLICATE_DAILY_REPORT_DATE
        )
    }

    @Test
    fun outsideMonthSourceFailsStrictValidation() {
        val source = fixture.source()
        val outside = source.expenseVisibility.first().expense.copy(
            id = "outside",
            expenseDate = "2026-07-01"
        )

        assertFailure(
            source.copy(
                expenseVisibility = source.expenseVisibility + ExpenseVisibilityRecord(outside, false)
            ),
            MonthlyExportValidationFailureReason.EXPENSE_OUTSIDE_TARGET_MONTH
        )
    }

    @Test
    fun invalidDatesAndOverflowFailInsteadOfBeingCorrected() {
        val source = fixture.source()
        assertFailure(
            source.copy(
                dailyReports = listOf(source.dailyReports.first().copy(reportDate = "2026-06-31"))
            ),
            MonthlyExportValidationFailureReason.INVALID_REPORT_DATE
        )
        assertFailure(
            source.copy(
                dailyReports = listOf(
                    source.dailyReports.first().copy(
                        cashSales = Long.MAX_VALUE,
                        cardSales = 1L
                    )
                )
            ),
            MonthlyExportValidationFailureReason.BUSINESS_METRIC_MAPPING_FAILURE
        )
    }

    @Test
    fun excelExactIntegerBoundaryIsAcceptedAndBoundaryPlusOneIsRejected() {
        assertEquals(999_999_999_999_999L, ExcelIntegerContract.MaxExactInteger)
        assertEquals(-999_999_999_999_999L, ExcelIntegerContract.MinExactInteger)
        val boundaryReport = fixture.report(
            "boundary",
            "2026-06-01",
            ExcelIntegerContract.MaxExactInteger,
            0L,
            0L,
            0L,
            0L
        )
        val accepted = provider().assemble(
            fixture.targetMonth,
            MonthlyExportSourceSnapshot(listOf(boundaryReport), emptyList(), emptyList())
        )
        assertTrue(accepted is MonthlyExportSnapshotResult.Success)

        assertFailure(
            MonthlyExportSourceSnapshot(
                listOf(boundaryReport.copy(cashSales = ExcelIntegerContract.MaxExactInteger + 1L)),
                emptyList(),
                emptyList()
            ),
            MonthlyExportValidationFailureReason.EXCEL_INTEGER_OUT_OF_RANGE
        )
        assertTrue(ExcelIntegerContract.isExact(ExcelIntegerContract.MinExactInteger))
        assertTrue(!ExcelIntegerContract.isExact(ExcelIntegerContract.MinExactInteger - 1L))
    }

    @Test
    fun expenseExcelBoundaryIsValidatedForRowsAndTotal() {
        val boundary = fixture.expense(
            "expense-boundary",
            "2026-06-01",
            ExcelIntegerContract.MaxExactInteger,
            "現金",
            "境界店"
        )
        val accepted = provider().assemble(
            fixture.targetMonth,
            MonthlyExportSourceSnapshot(
                emptyList(),
                listOf(ExpenseVisibilityRecord(boundary, false)),
                emptyList()
            )
        )
        assertTrue(accepted is MonthlyExportSnapshotResult.Success)

        assertFailure(
            MonthlyExportSourceSnapshot(
                emptyList(),
                listOf(
                    ExpenseVisibilityRecord(
                        boundary.copy(amount = ExcelIntegerContract.MaxExactInteger + 1L),
                        false
                    )
                ),
                emptyList()
            ),
            MonthlyExportValidationFailureReason.EXCEL_INTEGER_OUT_OF_RANGE
        )
    }

    @Test
    fun repeatedGenerationIsReadOnlyAndDeterministic() = runTest {
        val source = fixture.source()
        val original = source.copy(
            dailyReports = source.dailyReports.toList(),
            expenseVisibility = source.expenseVisibility.toList(),
            storedEvidence = source.storedEvidence.toList()
        )
        var loads = 0
        val provider = MonthlyExportSnapshotProvider(
            object : MonthlyExportRepository {
                override suspend fun loadSourceSnapshot(targetMonth: YearMonth): MonthlyExportSourceSnapshot {
                    loads += 1
                    return source
                }
            }
        )

        val first = provider.load(fixture.targetMonth)
        val second = provider.load(fixture.targetMonth)

        assertEquals(first, second)
        assertEquals(2, loads)
        assertEquals(original, source)
    }

    @Test
    fun filenamesFollowConfirmedMonthlyFormat() {
        val snapshot = success(fixture.source())

        assertEquals("2026年6月_日報.xlsx", snapshot.dailyReportFileName)
        assertEquals("2026年6月_支出明細.xlsx", snapshot.expenseDetailFileName)
    }

    private fun success(source: MonthlyExportSourceSnapshot): MonthlyExportSnapshot =
        (provider().assemble(fixture.targetMonth, source) as MonthlyExportSnapshotResult.Success).snapshot

    private fun assertFailure(
        source: MonthlyExportSourceSnapshot,
        expected: MonthlyExportValidationFailureReason
    ) {
        val result = provider().assemble(fixture.targetMonth, source)
        assertTrue(result is MonthlyExportSnapshotResult.ValidationFailure)
        assertEquals(
            expected,
            (result as MonthlyExportSnapshotResult.ValidationFailure).failure.reason
        )
    }

    private fun provider() = MonthlyExportSnapshotProvider(
        object : MonthlyExportRepository {
            override suspend fun loadSourceSnapshot(
                targetMonth: YearMonth
            ): MonthlyExportSourceSnapshot = error("not used")
        }
    )
}
