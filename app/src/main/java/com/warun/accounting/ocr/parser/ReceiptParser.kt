package com.warun.accounting.ocr.parser

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

class ReceiptParser @Inject constructor() {
    fun parse(
        rawText: String,
        knownStoreNames: Collection<String> = emptyList()
    ): ReceiptParseResult = runCatching {
        val lines = rawText.split('\n').mapIndexed { index, original ->
            ReceiptLine(index, original, normalizeLine(original))
        }
        ReceiptParseResult(
            rawText = rawText,
            lines = lines,
            storeCandidates = extractStores(lines, knownStoreNames),
            dateTimeCandidates = extractDateTimes(lines),
            totalAmountCandidates = extractTotalAmounts(lines)
        )
    }.getOrElse {
        ReceiptParseResult(rawText, emptyList(), emptyList(), emptyList(), emptyList())
    }

    private fun extractStores(
        lines: List<ReceiptLine>,
        knownStoreNames: Collection<String>
    ): List<ReceiptStoreCandidate> {
        val nonBlankLines = lines.filter { it.normalized.isNotBlank() }
        val topLines = nonBlankLines.take(StoreSearchLineCount)
        if (topLines.isEmpty()) return emptyList()
        val branchLine = topLines.firstNotNullOfOrNull { line ->
            BranchRegex.find(line.normalized)?.groupValues?.get(1)?.let { line to it }
        }
        val rules = buildList {
            addAll(ReceiptStoreRules.defaults)
            val existing = ReceiptStoreRules.defaults.map { normalizeComparable(it.canonicalName) }.toSet()
            knownStoreNames.asSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && it != "他" }
                .filter { normalizeComparable(it) !in existing }
                .distinct()
                .forEach { add(ReceiptStoreRule(it, setOf(it))) }
        }
        val candidates = mutableListOf<ReceiptStoreCandidate>()

        for (line in nonBlankLines) {
            val comparable = normalizeComparable(line.normalized)
            for (rule in rules) {
                val matchedAlias = rule.aliases.firstOrNull { alias ->
                    val normalizedAlias = normalizeComparable(alias)
                    comparable.contains(normalizedAlias) ||
                        comparable.tokens().any { token -> isSimilar(token, normalizedAlias) }
                } ?: continue
                val isKnownSupplier = knownStoreNames.any {
                    normalizeComparable(it) == normalizeComparable(rule.canonicalName)
                }
                val evidenceLines = listOfNotNull(
                    line,
                    branchLine?.first?.takeIf { it.index != line.index }
                )
                val priority = 1_000 + topLineBonus(line.index) + if (isKnownSupplier) 100 else 0
                candidates += ReceiptStoreCandidate(
                    normalizedName = rule.canonicalName,
                    originalText = line.original,
                    branchName = branchLine?.second?.takeUnless {
                        normalizeComparable(it) == normalizeComparable(rule.canonicalName)
                    },
                    priority = priority,
                    confidence = ReceiptCandidateConfidence.High,
                    evidence = ReceiptCandidateEvidence(
                        evidenceLines,
                        "店舗別名または既存支払先候補と一致: $matchedAlias"
                    )
                )
            }
        }

        for (line in topLines) {
            if (!isPossibleGenericStoreLine(line.normalized)) continue
            val branch = BranchRegex.find(line.normalized)?.groupValues?.get(1)
            candidates += ReceiptStoreCandidate(
                normalizedName = line.normalized,
                originalText = line.original,
                branchName = branch?.takeIf { it != line.normalized },
                priority = 300 + topLineBonus(line.index),
                confidence = ReceiptCandidateConfidence.Low,
                evidence = ReceiptCandidateEvidence(listOf(line), "レシート上部の文字列")
            )
        }

        return candidates
            .groupBy { normalizeComparable(it.normalizedName) }
            .mapNotNull { (_, matches) -> matches.maxByOrNull { it.priority } }
            .sortedByDescending { it.priority }
    }

    private fun extractDateTimes(lines: List<ReceiptLine>): List<ReceiptDateTimeCandidate> {
        val candidates = mutableListOf<ReceiptDateTimeCandidate>()
        for (line in lines) {
            val dateMatches = DatePatterns.flatMap { pattern -> pattern.findAll(line.normalized).toList() }
            for (match in dateMatches) {
                val parsedDate = parseDate(match) ?: continue
                val sameLineTime = TimeRegex.find(line.normalized)?.toTime()
                val adjacentTimeLine = if (sameLineTime == null) {
                    lines.asSequence()
                        .filter { kotlin.math.abs(it.index - line.index) == 1 }
                        .firstOrNull { TimeRegex.containsMatchIn(it.normalized) }
                } else null
                val time = sameLineTime ?: adjacentTimeLine?.let { TimeRegex.find(it.normalized)?.toTime() }
                val labelNearby = lines.any {
                    kotlin.math.abs(it.index - line.index) <= 1 && DateLabelRegex.containsMatchIn(it.normalized)
                }
                val evidenceLines = listOfNotNull(line, adjacentTimeLine)
                val priority = 500 + topLineBonus(line.index) +
                    (if (labelNearby) 250 else 0) +
                    (if (sameLineTime != null) 60 else if (time != null) 30 else 0)
                candidates += ReceiptDateTimeCandidate(
                    normalizedDate = parsedDate.toString(),
                    normalizedTime = time,
                    originalText = evidenceLines.joinToString(" / ") { it.original },
                    priority = priority,
                    confidence = confidenceFor(priority, high = 750, medium = 550),
                    evidence = ReceiptCandidateEvidence(
                        evidenceLines,
                        when {
                            labelNearby -> "日時ラベル付近の日付"
                            sameLineTime != null -> "日付と時刻が同じ行"
                            time != null -> "日付と時刻が隣接行"
                            else -> "日付形式と一致"
                        }
                    )
                )
            }
        }
        return candidates
            .groupBy { it.normalizedValue }
            .mapNotNull { (_, matches) -> matches.maxByOrNull { it.priority } }
            .sortedByDescending { it.priority }
    }

    private fun extractTotalAmounts(lines: List<ReceiptLine>): List<ReceiptAmountCandidate> {
        val occurrences = lines.flatMap(::extractAmountOccurrences)
        if (occurrences.isEmpty()) return emptyList()
        val totalLabels = lines.mapNotNull { line -> totalLabelWeight(line.normalized)?.let { line to it } }
        if (totalLabels.isEmpty()) return emptyList()
        val excludedLabels = lines.filter {
            ExcludedAmountLabelRegex.containsMatchIn(normalizeAmountLabel(it.normalized))
        }
        val candidates = mutableListOf<ReceiptAmountCandidate>()

        for ((labelLine, labelWeight) in totalLabels) {
            for (occurrence in occurrences) {
                val distance = kotlin.math.abs(occurrence.line.index - labelLine.index)
                if (distance > AmountContextRadius) continue
                val closerExcludedLabel = excludedLabels.any { excluded ->
                    kotlin.math.abs(excluded.index - occurrence.line.index) < distance ||
                        (excluded.index == occurrence.line.index && excluded.index != labelLine.index)
                }
                if (closerExcludedLabel || occurrence.isExcluded || occurrence.isLikelyItemLine) continue
                val priority = labelWeight + (AmountContextRadius - distance) * 80 +
                    if (occurrence.hasCurrencyMarker) 40 else 0
                candidates += ReceiptAmountCandidate(
                    amount = occurrence.amount,
                    originalText = occurrence.originalText,
                    priority = priority,
                    confidence = confidenceFor(priority, high = 950, medium = 750),
                    evidence = ReceiptCandidateEvidence(
                        listOf(labelLine, occurrence.line).distinctBy { it.index },
                        if (distance == 0) "合計ラベルと同じ行" else "合計ラベルの前後${distance}行"
                    )
                )
            }
        }

        inferTotalFromPaymentArithmetic(lines, occurrences, totalLabels.first().first)?.let(candidates::add)

        return candidates
            .filter { it.amount in MinimumAmount..MaximumAmount }
            .groupBy { it.amount }
            .mapNotNull { (_, matches) -> matches.maxByOrNull { it.priority } }
            .sortedByDescending { it.priority }
    }

    private fun inferTotalFromPaymentArithmetic(
        lines: List<ReceiptLine>,
        occurrences: List<AmountOccurrence>,
        totalLabel: ReceiptLine
    ): ReceiptAmountCandidate? {
        if (lines.none { DepositLabelRegex.containsMatchIn(normalizeAmountLabel(it.normalized)) } ||
            lines.none { ChangeLabelRegex.containsMatchIn(normalizeAmountLabel(it.normalized)) }
        ) return null
        val values = occurrences
            .filterNot { it.isExcluded || it.isLikelyItemLine }
            .filter { it.amount in MinimumAmount..MaximumAmount }
        val amountFrequencies = values.groupingBy { it.amount }.eachCount()
        val inferred = values.asSequence().flatMap { deposit ->
            values.asSequence()
                .filter { change ->
                    change !== deposit && change.amount < deposit.amount
                }
                .flatMap { change ->
                    val totalValue = deposit.amount - change.amount
                    values.asSequence()
                        .filter { total ->
                            total !== deposit &&
                                total !== change &&
                                total.amount == totalValue &&
                                total.amount > change.amount
                        }
                        .map { total ->
                            CashArithmeticMatch(
                                total = total,
                                deposit = deposit,
                                change = change,
                                changeFrequency = amountFrequencies.getValue(change.amount)
                            )
                        }
                }
        }.minWithOrNull(
            compareBy<CashArithmeticMatch>(
                { it.depositRoundnessPenalty },
                { it.changeFrequency },
                { it.lineSpan },
                { it.depositChangeDistance },
                { it.sequencePenalty },
                { -it.total.amount }
            )
        )
            ?: return null
        return ReceiptAmountCandidate(
            amount = inferred.total.amount,
            originalText = inferred.total.originalText,
            priority = 500,
            confidence = ReceiptCandidateConfidence.Low,
            evidence = ReceiptCandidateEvidence(
                listOf(
                    totalLabel,
                    inferred.total.line,
                    inferred.deposit.line,
                    inferred.change.line
                ).distinctBy { it.index },
                "現金払いの補助推定: 預り額 - 釣銭 = 合計額の関係と一致"
            )
        )
    }

    private fun extractAmountOccurrences(line: ReceiptLine): List<AmountOccurrence> {
        if (
            line.normalized.isBlank() ||
            IgnoredNumericLineRegex.containsMatchIn(line.normalized) ||
            DatePatterns.any { it.containsMatchIn(line.normalized) }
        ) return emptyList()
        return AmountRegex.findAll(line.normalized).mapNotNull { match ->
            val suffix = line.normalized.substring(match.range.last + 1).trimStart()
            if (suffix.startsWith('%')) {
                return@mapNotNull null
            }
            val amount = match.groupValues[1].filter(Char::isDigit).toLongOrNull() ?: return@mapNotNull null
            if (amount !in MinimumAmount..MaximumAmount) return@mapNotNull null
            val matchedText = match.value.trim()
            AmountOccurrence(
                line = line,
                amount = amount,
                originalText = matchedText,
                hasCurrencyMarker = matchedText.contains('¥') || matchedText.contains('￥') || matchedText.contains('円'),
                isLikelyItemLine = isLikelyItemAmountLine(line.normalized, match.range),
                isExcluded = matchedText.startsWith('-') ||
                    ExcludedAmountLabelRegex.containsMatchIn(normalizeAmountLabel(line.normalized))
            )
        }.toList()
    }

    private fun isLikelyItemAmountLine(line: String, amountRange: IntRange): Boolean {
        if (totalLabelWeight(line) != null) return false
        val surroundingText = line.removeRange(amountRange)
            .replace(ItemLinePunctuationRegex, "")
            .trim()
        return surroundingText.count(Char::isLetter) >= 3 ||
            GenericItemLabelRegex.containsMatchIn(surroundingText)
    }

    private fun parseDate(match: MatchResult): LocalDate? = try {
        val rawYear = match.groupValues[1].toInt()
        val year = if (rawYear < 100) 2_000 + rawYear else rawYear
        LocalDate.of(year, match.groupValues[2].toInt(), match.groupValues[3].toInt())
    } catch (_: DateTimeException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    private fun MatchResult.toTime(): String? {
        val hour = groupValues[1].toIntOrNull() ?: return null
        val minute = groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(Locale.ROOT, hour, minute)
    }

    private fun totalLabelWeight(line: String): Int? = when (val label = normalizeAmountLabel(line)) {
        in listOf("税込合計", "総合計", "ご請求額") -> 1_000
        in listOf("お買上計", "お買上額", "現計") -> 950
        "合計" -> 900
        else -> when {
            Regex("税込合計|総合計|ご請求額").containsMatchIn(label) -> 1_000
            Regex("お買上(?:計|額)|現計").containsMatchIn(label) -> 950
            label.contains("合計") && !label.contains("小計") -> 850
            else -> null
        }
    }

    private fun normalizeAmountLabel(value: String): String = value.replace(WhitespaceRegex, "")

    private fun isPossibleGenericStoreLine(line: String): Boolean {
        if (line.length !in 2..40 || line.count(Char::isLetter) < 2) return false
        if (GenericStoreExcludedRegex.containsMatchIn(line)) return false
        if (AmountRegex.containsMatchIn(line) || DatePatterns.any { it.containsMatchIn(line) }) return false
        return true
    }

    private fun normalizeLine(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\t \\u3000]+"), " ")
        .trim()

    private fun normalizeComparable(value: String): String = normalizeLine(value)
        .lowercase(Locale.JAPAN)
        .replace(Regex("[^\\p{L}\\p{N}ー]"), "")

    private fun String.tokens(): List<String> = split(Regex("[^\\p{L}\\p{N}ー]+"))
        .filter { it.length >= 3 }

    private fun isSimilar(left: String, right: String): Boolean {
        if (left == right || left.contains(right) || right.contains(left)) return true
        if (left.length < 4 || right.length < 4) return false
        return levenshteinDistance(left, right) <= 1
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (left[i] == right[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun topLineBonus(index: Int): Int = (120 - index * 8).coerceAtLeast(0)

    private fun confidenceFor(priority: Int, high: Int, medium: Int): ReceiptCandidateConfidence = when {
        priority >= high -> ReceiptCandidateConfidence.High
        priority >= medium -> ReceiptCandidateConfidence.Medium
        else -> ReceiptCandidateConfidence.Low
    }

    private data class AmountOccurrence(
        val line: ReceiptLine,
        val amount: Long,
        val originalText: String,
        val hasCurrencyMarker: Boolean,
        val isLikelyItemLine: Boolean,
        val isExcluded: Boolean
    )

    private data class CashArithmeticMatch(
        val total: AmountOccurrence,
        val deposit: AmountOccurrence,
        val change: AmountOccurrence,
        val changeFrequency: Int
    ) {
        private val lineIndexes = listOf(total.line.index, deposit.line.index, change.line.index)
        val depositRoundnessPenalty: Int = if (deposit.amount % 100L == 0L) 0 else 1
        val lineSpan: Int = lineIndexes.max() - lineIndexes.min()
        val depositChangeDistance: Int = kotlin.math.abs(deposit.line.index - change.line.index)
        val sequencePenalty: Int = if (
            total.line.index < deposit.line.index && deposit.line.index < change.line.index
        ) 0 else 1
    }

    private companion object {
        const val StoreSearchLineCount = 14
        const val AmountContextRadius = 2
        const val MinimumAmount = 1L
        const val MaximumAmount = 99_999_999L
        val BranchRegex = Regex("([\\p{L}\\p{N}ー々ヶ]{1,12}店)(?:\\s|$)")
        val DatePatterns = listOf(
            Regex("(?<!\\d)(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})(?!\\d)"),
            Regex("(?<!\\d)(\\d{4})年\\s*(\\d{1,2})月\\s*(\\d{1,2})日"),
            Regex("(?<!\\d)(\\d{2})[/-](\\d{1,2})[/-](\\d{1,2})(?!\\d)")
        )
        val TimeRegex = Regex("(?<!\\d)([01]?\\d|2[0-3])[:時]([0-5]\\d)(?:分)?")
        val DateLabelRegex = Regex("取引日時|購入日時|日時|発行日|購入日|お買上日")
        val AmountRegex = Regex(
            "[-*]?\\s*[¥￥]?\\s*(\\d{1,3}(?:(?:(?:,|\\.,?)\\s*|\\s+)\\d{3})+|\\d{1,8})\\s*円?"
        )
        val ExcludedAmountLabelRegex = Regex(
            "お?預り|預かり|お?釣り|お?的り|釣銭|小計|消費税|内税|外税|税計|値引|ポイント|支払前残高"
        )
        val DepositLabelRegex = Regex("お?預り|預かり")
        val ChangeLabelRegex = Regex("お?釣り|お?的り|釣銭")
        val IgnoredNumericLineRegex = Regex(
            "登録番号|電話|TEL|レジ|担当|[責貴手]No|チNo|店No|(?:レ)?シートNo|Code|コード|ポイント|\\d+点",
            RegexOption.IGNORE_CASE
        )
        val WhitespaceRegex = Regex("\\s+")
        val ItemLinePunctuationRegex = Regex("[\\s*※()（）:：/\\-]+")
        val GenericItemLabelRegex = Regex("商品|品名")
        val GenericStoreExcludedRegex = Regex(
            "領収証|領収書|レシート|登録番号|電話|TEL|住所|〒|\\d{2,4}[-ー]\\d{2,4}|日時|発行日|購入日|" +
                "担当|レジ|責No|チNo|店No|合計|小計|預り|釣り|税|ポイント|キャンペーン|ご入会|募集|受付|店長",
            RegexOption.IGNORE_CASE
        )
    }
}
