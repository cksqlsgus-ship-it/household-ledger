package com.myledger.app

import android.content.Context
import java.util.Locale

enum class FilterDecision {
    PASS,
    IGNORE,
    UNMATCHED
}

data class ParsedEntry(
    val shouldSave: Boolean,
    val type: String,
    val category: String,
    val merchant: String,
    val fromAccount: String,
    val toAccount: String,
    val balanceInfo: String,
    val itemSummary: String,
    val totalQuantity: String,
    val amount: Long,
    val cleanRawText: String,
    val itemsJson: String = "[]"
)

object NotificationParser {

    val CATEGORIES = listOf(
        "식비",
        "카페/간식",
        "쇼핑/생활",
        "교통/차량",
        "구독/통신",
        "의료/건강",
        "수입",
        "이체/계좌이동",
        "고정 지출",
        "기타"
    )

    /**
     * 통과 / 무시 / 미분류(보류) 3단계 판정:
     * 1) 무시 단어가 하나라도 있으면 통과 단어가 함께 있어도 무조건 IGNORE
     * 2) 양식 규칙(ParserTemplate) 일치 시 actionType 에 따라 PASS / IGNORE
     * 3) 통과/분류/이체 단어 규칙에 일치하면 PASS
     * 4) 통과 단어도 무시 단어도 없으면 UNMATCHED (설정 탭의 미분류·보류 목록으로 이동)
     */
    fun evaluateFilterDecision(context: Context, text: String): FilterDecision {
        val lower = text.lowercase(Locale.KOREA)
        if (lower.isBlank()) return FilterDecision.IGNORE

        val rules = LedgerStorage.getAutoRules(context)

        // 1. 무시 단어(ignoreKeywords) 및 무시/삭제 전용 규칙 최우선 검사
        for (rule in rules) {
            val ignoreTokens = splitTokens(rule.ignoreKeywords).map { it.lowercase(Locale.KOREA) }
            if (ignoreTokens.any { lower.contains(it) }) {
                return FilterDecision.IGNORE
            }

            if (rule.action == "ignore" || rule.action == "delete") {
                val delTokens = splitTokens(rule.keyword).map { it.lowercase(Locale.KOREA) }
                if (delTokens.any { lower.contains(it) }) {
                    return FilterDecision.IGNORE
                }
            }
        }

        // 2. 커스텀 양식 규칙(ParserTemplate) 검사
        val templates = LedgerStorage.getParserTemplates(context)
        val matchedTemplate = findMatchingTemplate(templates, text)
        if (matchedTemplate != null) {
            return if (matchedTemplate.actionType == "delete") {
                FilterDecision.IGNORE
            } else {
                FilterDecision.PASS
            }
        }

        // 3. 통과(pass) / 이체(transfer) / 카테고리(category) 단어 검사
        val hasPassOrClassifyMatch = rules.any { rule ->
            if (rule.action != "pass" && rule.action != "transfer" && rule.action != "category") {
                return@any false
            }
            val passTokens = splitTokens(rule.keyword).map { it.lowercase(Locale.KOREA) }
            passTokens.any { lower.contains(it) }
        }

        if (hasPassOrClassifyMatch) {
            return FilterDecision.PASS
        }

        // 4. 통과 단어도 없고 무시 단어도 없는 알림
        return FilterDecision.UNMATCHED
    }

    fun parse(context: Context, title: String, text: String): ParsedEntry {
        val rawCombined = listOf(title.trim(), text.trim())
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

        val templates = LedgerStorage.getParserTemplates(context)
        val matchedTemplate = findMatchingTemplate(templates, rawCombined)

        val cleanedText = removeExcludedLines(rawCombined, matchedTemplate?.excludeWords.orEmpty())
        val fallbackMerchant = title.trim().ifEmpty {
            cleanedText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "기타 내역"
        }

        val rules = LedgerStorage.getAutoRules(context)
        val decision = evaluateFilterDecision(context, rawCombined)

        if (decision == FilterDecision.IGNORE) {
            return ParsedEntry(
                shouldSave = false,
                type = "exp",
                category = "기타",
                merchant = fallbackMerchant,
                fromAccount = "",
                toAccount = "",
                balanceInfo = "",
                itemSummary = "",
                totalQuantity = "",
                amount = 0L,
                cleanRawText = cleanedText
            )
        }

        val classifyRule = findMatchingClassifyRule(rules, rawCombined, cleanedText)

        if (matchedTemplate != null) {
            if (matchedTemplate.actionType == "delete") {
                return ParsedEntry(
                    shouldSave = false,
                    type = "exp",
                    category = matchedTemplate.category,
                    merchant = fallbackMerchant,
                    fromAccount = "",
                    toAccount = "",
                    balanceInfo = "",
                    itemSummary = "",
                    totalQuantity = "",
                    amount = 0L,
                    cleanRawText = cleanedText
                )
            }

            val baseType = when (matchedTemplate.actionType) {
                "inc" -> "inc"
                "trn" -> "trn"
                else -> "exp"
            }
            val baseCategory = if (baseType == "trn" && matchedTemplate.category == "기타") {
                "이체/계좌이동"
            } else {
                matchedTemplate.category.ifBlank { "기타" }
            }

            val (resolvedType, resolvedCategory) = applyRuleOverride(baseType, baseCategory, classifyRule)

            val merchant = extractTextField(
                source = cleanedText,
                mode = matchedTemplate.merchantMode,
                fixedOrSingle = matchedTemplate.merchantValue,
                startMarker = matchedTemplate.merchantStart,
                endMarker = matchedTemplate.merchantEnd,
                fallback = fallbackMerchant
            )

            val fromAccount = extractTextField(
                source = cleanedText,
                mode = matchedTemplate.fromAccountMode,
                fixedOrSingle = matchedTemplate.fromAccountValue,
                startMarker = matchedTemplate.fromAccountStart,
                endMarker = matchedTemplate.fromAccountEnd,
                fallback = ""
            )

            val toAccount = extractTextField(
                source = cleanedText,
                mode = matchedTemplate.toAccountMode,
                fixedOrSingle = matchedTemplate.toAccountValue,
                startMarker = matchedTemplate.toAccountStart,
                endMarker = matchedTemplate.toAccountEnd,
                fallback = ""
            )

            val item = extractTextField(
                source = cleanedText,
                mode = matchedTemplate.itemMode,
                fixedOrSingle = matchedTemplate.itemValue,
                startMarker = matchedTemplate.itemStart,
                endMarker = matchedTemplate.itemEnd,
                fallback = ""
            )

            val quantity = extractTextField(
                source = cleanedText,
                mode = matchedTemplate.quantityMode,
                fixedOrSingle = matchedTemplate.quantityValue,
                startMarker = matchedTemplate.quantityStart,
                endMarker = matchedTemplate.quantityEnd,
                fallback = ""
            )

            val amount = extractAmountField(
                source = cleanedText,
                mode = matchedTemplate.amountMode,
                fixedOrMarker = matchedTemplate.amountValue,
                startMarker = matchedTemplate.amountStart,
                endMarker = matchedTemplate.amountEnd
            )

            return ParsedEntry(
                shouldSave = amount > 0L,
                type = resolvedType,
                category = resolvedCategory,
                merchant = merchant.ifBlank { fallbackMerchant },
                fromAccount = fromAccount,
                toAccount = toAccount,
                balanceInfo = "",
                itemSummary = item,
                totalQuantity = quantity,
                amount = amount,
                cleanRawText = cleanedText
            )
        }

        val amount = extractFirstNumber(cleanedText)
        val (resolvedType, resolvedCategory) = applyRuleOverride("exp", "기타", classifyRule)

        return ParsedEntry(
            shouldSave = (decision == FilterDecision.PASS && amount > 0L),
            type = resolvedType,
            category = resolvedCategory,
            merchant = fallbackMerchant,
            fromAccount = "",
            toAccount = "",
            balanceInfo = "",
            itemSummary = "",
            totalQuantity = "",
            amount = amount,
            cleanRawText = cleanedText
        )
    }

    private fun findMatchingClassifyRule(
        rules: List<AutoRule>,
        rawText: String,
        cleanedText: String
    ): AutoRule? {
        val hay = "$rawText\n$cleanedText".lowercase(Locale.KOREA)
        if (hay.isBlank()) return null

        for (rule in rules) {
            if (rule.action != "transfer" && rule.action != "category") continue
            val tokens = splitTokens(rule.keyword).map { it.lowercase(Locale.KOREA) }
            if (tokens.any { hay.contains(it) }) {
                return rule
            }
        }
        return null
    }

    private fun applyRuleOverride(
        baseType: String,
        baseCategory: String,
        rule: AutoRule?
    ): Pair<String, String> {
        rule ?: return baseType to baseCategory
        return when (rule.action) {
            "transfer" -> "trn" to "이체/계좌이동"
            "category" -> baseType to rule.category.ifBlank { baseCategory }
            else -> baseType to baseCategory
        }
    }

    private fun findMatchingTemplate(templates: List<ParserTemplate>, text: String): ParserTemplate? {
        if (text.isBlank()) return null
        val lowerText = text.lowercase(Locale.KOREA)

        for (tpl in templates) {
            val includes = splitTokens(tpl.includeWords)
            if (includes.isEmpty()) continue
            val allIncluded = includes.all { token -> lowerText.contains(token.lowercase(Locale.KOREA)) }
            if (!allIncluded) continue

            val excludes = splitTokens(tpl.excludeWords)
            val hasExcluded = excludes.any { token -> lowerText.contains(token.lowercase(Locale.KOREA)) }
            if (hasExcluded) continue

            return tpl
        }
        return null
    }

    private fun removeExcludedLines(text: String, excludeWordsRaw: String): String {
        val excludes = splitTokens(excludeWordsRaw).map { it.lowercase(Locale.KOREA) }
        if (excludes.isEmpty()) return text.trim()

        val filtered = text.lineSequence()
            .filter { line ->
                val lowerLine = line.lowercase(Locale.KOREA)
                excludes.none { token -> lowerLine.contains(token) }
            }
            .joinToString("\n")
            .trim()

        return if (filtered.isNotEmpty()) filtered else text.trim()
    }

    private fun splitTokens(raw: String): List<String> {
        return raw.split(",", "\n", "|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun extractTextField(
        source: String,
        mode: String,
        fixedOrSingle: String,
        startMarker: String,
        endMarker: String,
        fallback: String
    ): String {
        val cleanMode = mode.trim().uppercase(Locale.KOREA)
        return when (cleanMode) {
            "FIXED" -> fixedOrSingle.trim()
            "AFTER" -> {
                val anchor = startMarker.trim().ifEmpty { fixedOrSingle.trim() }
                if (anchor.isEmpty()) return fallback
                val extracted = sliceAfter(source, anchor)
                extracted.ifEmpty { fallback }
            }
            "BEFORE" -> {
                val anchor = endMarker.trim().ifEmpty { fixedOrSingle.trim() }
                if (anchor.isEmpty()) return fallback
                val extracted = sliceBefore(source, anchor)
                extracted.ifEmpty { fallback }
            }
            "BETWEEN" -> {
                val s = startMarker.trim()
                val e = endMarker.trim()
                val extracted = sliceBetween(source, s, e)
                extracted.ifEmpty { fallback }
            }
            else -> fallback
        }
    }

    private fun extractAmountField(
        source: String,
        mode: String,
        fixedOrMarker: String,
        startMarker: String,
        endMarker: String
    ): Long {
        val cleanMode = mode.trim().uppercase(Locale.KOREA)
        return when (cleanMode) {
            "FIXED" -> parseDigitsToLong(fixedOrMarker)
            "LAST" -> extractLastNumber(source)
            "AFTER" -> {
                val anchor = startMarker.trim().ifEmpty { fixedOrMarker.trim() }
                val sliced = if (anchor.isNotEmpty()) sliceAfter(source, anchor) else source
                extractFirstNumber(sliced.ifEmpty { source })
            }
            "BEFORE" -> {
                val anchor = endMarker.trim().ifEmpty { fixedOrMarker.trim() }
                val sliced = if (anchor.isNotEmpty()) sliceBefore(source, anchor) else source
                extractLastNumber(sliced.ifEmpty { source })
            }
            "BETWEEN" -> {
                val sliced = sliceBetween(source, startMarker.trim(), endMarker.trim())
                extractFirstNumber(sliced.ifEmpty { source })
            }
            else -> extractFirstNumber(source)
        }
    }

    private fun sliceAfter(source: String, start: String): String {
        val idx = source.indexOf(start)
        if (idx < 0) return ""
        return source.substring(idx + start.length)
            .substringBefore("\n")
            .trim()
    }

    private fun sliceBefore(source: String, end: String): String {
        val idx = source.indexOf(end)
        if (idx < 0) return ""
        return source.substring(0, idx)
            .substringAfterLast("\n")
            .trim()
    }

    private fun sliceBetween(source: String, start: String, end: String): String {
        val afterStart = if (start.isNotEmpty()) {
            val sIdx = source.indexOf(start)
            if (sIdx < 0) return ""
            source.substring(sIdx + start.length)
        } else {
            source
        }

        val beforeEnd = if (end.isNotEmpty()) {
            val eIdx = afterStart.indexOf(end)
            if (eIdx < 0) afterStart else afterStart.substring(0, eIdx)
        } else {
            afterStart
        }

        return beforeEnd.substringBefore("\n").trim()
    }

    private fun parseDigitsToLong(raw: String): Long {
        val intPart = raw.substringBefore(".")
        val digits = intPart.replace(Regex("[^0-9]"), "")
        return digits.toLongOrNull() ?: 0L
    }

    private fun extractFirstNumber(text: String): Long {
        val currencyMatches = Regex(
            "(?i)(?:₩|\\$|USD|JPY|EUR|GBP)?\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?\\s*(?:원|KRW|USD|JPY|EUR|GBP)|(?:₩|\\$|USD|JPY|EUR|GBP)\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"
        ).findAll(text)

        for (m in currencyMatches) {
            val rawNum = (m.groups[1]?.value ?: m.groups[2]?.value ?: "").replace(",", "")
            val valLong = rawNum.toLongOrNull() ?: 0L
            if (valLong > 0L) return valLong
        }

        val commaMatches = Regex("([0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?").findAll(text)
        for (m in commaMatches) {
            val valLong = (m.groups[1]?.value ?: "").replace(",", "").toLongOrNull() ?: 0L
            if (valLong > 0L) return valLong
        }

        val plainMatches = Regex("([0-9]+)(?:\\.[0-9]+)?").findAll(text)
        for (m in plainMatches) {
            val valLong = (m.groups[1]?.value ?: "").toLongOrNull() ?: 0L
            if (valLong > 0L) return valLong
        }
        return 0L
    }

    private fun extractLastNumber(text: String): Long {
        val currencyMatches = Regex(
            "(?i)(?:₩|\\$|USD|JPY|EUR|GBP)?\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?\\s*(?:원|KRW|USD|JPY|EUR|GBP)|(?:₩|\\$|USD|JPY|EUR|GBP)\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"
        ).findAll(text).toList()

        for (m in currencyMatches.asReversed()) {
            val rawNum = (m.groups[1]?.value ?: m.groups[2]?.value ?: "").replace(",", "")
            val valLong = rawNum.toLongOrNull() ?: 0L
            if (valLong > 0L) return valLong
        }

        val commaMatches = Regex("([0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?").findAll(text).toList()
        for (m in commaMatches.asReversed()) {
            val valLong = (m.groups[1]?.value ?: "").replace(",", "").toLongOrNull() ?: 0L
            if (valLong > 0L) return valLong
        }

        val plainMatches = Regex("([0-9]+)(?:\\.[0-9]+)?").findAll(text).toList()
        for (m in plainMatches.asReversed()) {
            val valLong = (m.groups[1]?.value ?: "").toLongOrNull() ?: 0L
            if (valLong > 0L) return valLong
        }
        return 0L
    }
}
