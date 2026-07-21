package com.warun.accounting.ocr.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptParserTest {
    private val parser = ReceiptParser()

    @Test
    fun valorOcrSelectsTotalAndKeepsBranchWithoutSelectingDepositOrChange() {
        val rawText = """
            岐南店 TEL 058-259-2077
            領収証
            登録番号 T8200001031520
            Valar
            2026年07月20日 (月) 15:48 レジ0003
            商品A
            商品B
            合計
            お預り
            小計
            お釣り
            1,540
            113
            2,000
            460
        """.trimIndent()

        val result = parser.parse(rawText, knownStoreNames = listOf("バロー"))

        assertEquals("バロー", result.bestStore?.normalizedName)
        assertEquals("岐南店", result.bestStore?.branchName)
        assertEquals("2026-07-20 15:48", result.bestDateTime?.normalizedValue)
        assertEquals(1_540L, result.bestTotalAmount?.amount)
        assertFalse(result.totalAmountCandidates.any { it.amount == 2_000L })
        assertFalse(result.totalAmountCandidates.any { it.amount == 460L })
        assertEquals(rawText, result.rawText)
    }

    @Test
    fun totalAmountCanFollowLabelOnNextLine() {
        val result = parser.parse("お買上計\n￥1,234")

        assertEquals(1_234L, result.bestTotalAmount?.amount)
        assertEquals(listOf(0, 1), result.bestTotalAmount?.evidence?.lines?.map { it.index })
    }

    @Test
    fun totalAmountCanPrecedeLabel() {
        val result = parser.parse("1,234円\nご請求額")

        assertEquals(1_234L, result.bestTotalAmount?.amount)
    }

    @Test
    fun normalizesFullWidthDigitsCommaCurrencyAndSpaces() {
        val result = parser.parse("税込合計\n￥１，２３４ 円")

        assertEquals(1_234L, result.bestTotalAmount?.amount)
        assertTrue(result.lines[1].normalized.contains("1,234"))
    }

    @Test
    fun strongerTotalLabelWinsWhenSeveralTotalsExist() {
        val result = parser.parse("小計 1,000円\n合計 1,080円\n税込合計 1,100円")

        assertEquals(1_100L, result.bestTotalAmount?.amount)
        assertFalse(result.totalAmountCandidates.any { it.amount == 1_000L })
    }

    @Test
    fun extractsSupportedDatesAndSameLineTime() {
        val samples = mapOf(
            "日時 2026/7/20 15:48" to "2026-07-20 15:48",
            "発行日 2026-07-20" to "2026-07-20",
            "購入日 2026年7月20日" to "2026-07-20",
            "日時 26/7/20 09:05" to "2026-07-20 09:05"
        )

        samples.forEach { (text, expected) ->
            assertEquals(expected, parser.parse(text).bestDateTime?.normalizedValue)
        }
    }

    @Test
    fun combinesDateWithTimeOnAdjacentLine() {
        val result = parser.parse("取引日時\n2026/07/20\n15:48")

        assertEquals("2026-07-20 15:48", result.bestDateTime?.normalizedValue)
        assertEquals(2, result.bestDateTime?.evidence?.lines?.size)
    }

    @Test
    fun emptyTextReturnsNoCandidates() {
        val result = parser.parse("")

        assertNull(result.bestStore)
        assertNull(result.bestDateTime)
        assertNull(result.bestTotalAmount)
    }

    @Test
    fun ocrNoiseOnlyReturnsNoCandidates() {
        val result = parser.parse("---\n***\n123")

        assertNull(result.bestStore)
        assertNull(result.bestDateTime)
        assertNull(result.bestTotalAmount)
    }

    @Test
    fun returnsAvailableFieldsWhenReceiptIsPartial() {
        val result = parser.parse("Valar\n岐南店\n合計 ￥980", listOf("バロー"))

        assertEquals("バロー", result.bestStore?.normalizedName)
        assertEquals("岐南店", result.bestStore?.branchName)
        assertEquals(980L, result.bestTotalAmount?.amount)
        assertNull(result.bestDateTime)
    }

    @Test
    fun excludedPaymentValuesAreNeverPromotedByNearbyTotalLabel() {
        val result = parser.parse("合計\nお預り 2,000円\nお釣り 460円")

        assertNull(result.bestTotalAmount)
    }

    @Test
    fun cashArithmeticIsLowConfidenceAndDoesNotOverrideExplicitTotal() {
        val result = parser.parse(
            """
                合計 1,600円
                お預り
                お釣り
                1,540
                2,000
                460
                クレジット
            """.trimIndent()
        )

        assertEquals(1_600L, result.bestTotalAmount?.amount)
        val arithmeticCandidate = result.totalAmountCandidates.single { it.amount == 1_540L }
        assertEquals(ReceiptCandidateConfidence.Low, arithmeticCandidate.confidence)
        assertTrue(arithmeticCandidate.priority < result.bestTotalAmount!!.priority)
    }

    @Test
    fun valorAliasAndCashAmountsCanBeRecoveredFromReorderedOcrLines() {
        val rawText = """
            登録番号18200001031520
            キャンペーン実施中です。
            商品1 ¥128
            商品2 ¥108
            商品3 ¥98
            商品4 ¥88
            商品5 ¥198
            商品6 ¥78
            商品7 ¥78
            商品8 ¥58
            貴No00999999鎌
            手No00999999遠藤
            小計
            1, 441
            外8%
            タイショウ
            Valsr
            外税計
            合計
            お預り
            お的り
            タイショウ
            1, 434
            M1, 555
            M445
            シートNo6263
            2, 000
        """.trimIndent()

        val result = parser.parse(rawText, knownStoreNames = listOf("バロー"))

        assertEquals("バロー", result.bestStore?.normalizedName)
        assertEquals(1_555L, result.bestTotalAmount?.amount)
        assertEquals(ReceiptCandidateConfidence.Low, result.bestTotalAmount?.confidence)
        assertFalse(result.totalAmountCandidates.any { it.amount == 2_000L })
        assertFalse(result.totalAmountCandidates.any { it.amount == 445L })
    }
}
