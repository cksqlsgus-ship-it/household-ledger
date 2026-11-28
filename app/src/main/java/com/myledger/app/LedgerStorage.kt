package com.myledger.app

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FixedExpenseItem(
    val id: String,
    val title: String,
    val startDay: Int,
    val endDay: Int,
    val amount: Long,
    val keyword: String
)

data class AutoRule(
    val id: String,
    val keyword: String,
    val ignoreKeywords: String = "",
    val action: String, // "pass", "ignore", "delete", "transfer", "category"
    val category: String
)

data class UnmatchedAlertItem(
    val id: String,
    val packageName: String,
    val title: String,
    val body: String,
    val timestamp: Long
)

data class AccountItem(
    val id: String,
    val name: String,
    val institution: String,
    val initialBalance: Long,
    val keywords: String,
    val autoTransferNote: String = ""
)

data class AccountFlowSummary(
    val balance: Long,
    val inflow: Long,
    val outflow: Long,
    val matchedRecords: List<JSONObject>
)

data class ParserTemplate(
    val id: String,
    val name: String,
    val includeWords: String = "",
    val excludeWords: String = "",
    val actionType: String = "exp", // "exp", "inc", "trn", "delete"
    val category: String = "기타",
    val merchantMode: String = "AUTO", // "AUTO", "FIXED", "AFTER", "BEFORE", "BETWEEN"
    val merchantValue: String = "",
    val merchantStart: String = "",
    val merchantEnd: String = "",
    val fromAccountMode: String = "AUTO",
    val fromAccountValue: String = "",
    val fromAccountStart: String = "",
    val fromAccountEnd: String = "",
    val toAccountMode: String = "AUTO",
    val toAccountValue: String = "",
    val toAccountStart: String = "",
    val toAccountEnd: String = "",
    val itemMode: String = "AUTO",
    val itemValue: String = "",
    val itemStart: String = "",
    val itemEnd: String = "",
    val amountMode: String = "FIRST", // "FIRST", "LAST", "FIXED", "AFTER", "BEFORE", "BETWEEN"
    val amountValue: String = "",
    val amountStart: String = "",
    val amountEnd: String = "",
    val quantityMode: String = "AUTO",
    val quantityValue: String = "",
    val quantityStart: String = "",
    val quantityEnd: String = ""
)

object LedgerStorage {

    private const val PREFS_NAME = "my_ledger_prefs"
    private const val KEY_RECORDS = "records_json"
    private const val KEY_DARK_MODE = "dark_mode"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_SEARCH_HISTORY = "search_history_json"
    private const val KEY_AUTO_RULES = "auto_rules_json"
    private const val KEY_UNMATCHED_ALERTS = "unmatched_alerts_json"
    private const val KEY_FIXED_EXPENSES = "fixed_expenses_json"
    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_PARSER_TEMPLATES = "parser_templates_json"
    private const val KEY_EMAIL_PROVIDER = "email_provider"
    private const val KEY_EMAIL_ADDR = "email_addr"
    private const val KEY_EMAIL_PASS = "email_pass"
    private const val KEY_EMAIL_PERIOD_START = "email_period_start"
    private const val KEY_EMAIL_PERIOD_END = "email_period_end"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDarkMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DARK_MODE, true)

    fun setDarkMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_MODE, enabled).apply()
    }

    fun getFontScale(context: Context): Float =
        prefs(context).getFloat(KEY_FONT_SCALE, 1.0f)

    fun setFontScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    // --- 미분류 / 보류 알림 저장소 ---

    fun getUnmatchedAlerts(context: Context): List<UnmatchedAlertItem> {
        val raw = prefs(context).getString(KEY_UNMATCHED_ALERTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<UnmatchedAlertItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    UnmatchedAlertItem(
                        id = obj.optString("id", ""),
                        packageName = obj.optString("packageName", ""),
                        title = obj.optString("title", ""),
                        body = obj.optString("body", ""),
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                )
            }
            list.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveUnmatchedAlerts(context: Context, items: List<UnmatchedAlertItem>) {
        val arr = JSONArray()
        for (item in items.take(150)) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("packageName", item.packageName)
                    put("title", item.title)
                    put("body", item.body)
                    put("timestamp", item.timestamp)
                }
            )
        }
        prefs(context).edit().putString(KEY_UNMATCHED_ALERTS, arr.toString()).apply()
    }

    fun addUnmatchedAlert(
        context: Context,
        packageName: String,
        title: String,
        body: String,
        timestamp: Long
    ) {
        val current = getUnmatchedAlerts(context).toMutableList()
        val isDup = current.any {
            it.title == title && it.body == body && kotlin.math.abs(it.timestamp - timestamp) < 5000L
        }
        if (isDup) return

        current.add(
            0,
            UnmatchedAlertItem(
                id = "${timestamp}_${(100..999).random()}",
                packageName = packageName,
                title = title,
                body = body,
                timestamp = timestamp
            )
        )
        saveUnmatchedAlerts(context, current)
    }

    fun removeUnmatchedAlert(context: Context, id: String) {
        val updated = getUnmatchedAlerts(context).filterNot { it.id == id }
        saveUnmatchedAlerts(context, updated)
    }

    fun clearUnmatchedAlerts(context: Context) {
        saveUnmatchedAlerts(context, emptyList())
    }

    fun reprocessUnmatchedAlerts(context: Context): Pair<Int, Int> {
        val current = getUnmatchedAlerts(context)
        if (current.isEmpty()) return 0 to 0

        val remaining = mutableListOf<UnmatchedAlertItem>()
        var promotedCount = 0
        var ignoredCount = 0

        for (item in current) {
            val combined = listOf(item.title, item.body).filter { it.isNotBlank() }.joinToString("\n")
            val decision = NotificationParser.evaluateFilterDecision(context, combined)
            when (decision) {
                FilterDecision.IGNORE -> {
                    ignoredCount++
                }
                FilterDecision.PASS -> {
                    val parsed = NotificationParser.parse(context, item.title, item.body)
                    if (parsed.shouldSave && parsed.amount > 0L) {
                        addOrMergeRecord(
                            context = context,
                            type = parsed.type,
                            category = parsed.category,
                            merchant = parsed.merchant,
                            fromAccount = parsed.fromAccount,
                            toAccount = parsed.toAccount,
                            balanceInfo = parsed.balanceInfo,
                            item = parsed.itemSummary,
                            quantity = parsed.totalQuantity,
                            amount = parsed.amount,
                            raw = parsed.cleanRawText,
                            itemsJson = parsed.itemsJson,
                            imagePath = "",
                            auto = true,
                            customTimestamp = if (item.timestamp > 0L) item.timestamp else System.currentTimeMillis()
                        )
                        promotedCount++
                    } else {
                        remaining.add(item)
                    }
                }
                FilterDecision.UNMATCHED -> {
                    remaining.add(item)
                }
            }
        }

        saveUnmatchedAlerts(context, remaining)
        return promotedCount to ignoredCount
    }

    // --- 양식 규칙 (ParserTemplate) ---

    fun getParserTemplates(context: Context): List<ParserTemplate> {
        val raw = prefs(context).getString(KEY_PARSER_TEMPLATES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ParserTemplate>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    ParserTemplate(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        includeWords = obj.optString("includeWords", ""),
                        excludeWords = obj.optString("excludeWords", ""),
                        actionType = obj.optString("actionType", "exp"),
                        category = obj.optString("category", "기타"),
                        merchantMode = obj.optString("merchantMode", "AUTO"),
                        merchantValue = obj.optString("merchantValue", ""),
                        merchantStart = obj.optString("merchantStart", ""),
                        merchantEnd = obj.optString("merchantEnd", ""),
                        fromAccountMode = obj.optString("fromAccountMode", "AUTO"),
                        fromAccountValue = obj.optString("fromAccountValue", ""),
                        fromAccountStart = obj.optString("fromAccountStart", ""),
                        fromAccountEnd = obj.optString("fromAccountEnd", ""),
                        toAccountMode = obj.optString("toAccountMode", "AUTO"),
                        toAccountValue = obj.optString("toAccountValue", ""),
                        toAccountStart = obj.optString("toAccountStart", ""),
                        toAccountEnd = obj.optString("toAccountEnd", ""),
                        itemMode = obj.optString("itemMode", "AUTO"),
                        itemValue = obj.optString("itemValue", ""),
                        itemStart = obj.optString("itemStart", ""),
                        itemEnd = obj.optString("itemEnd", ""),
                        amountMode = obj.optString("amountMode", "FIRST"),
                        amountValue = obj.optString("amountValue", ""),
                        amountStart = obj.optString("amountStart", ""),
                        amountEnd = obj.optString("amountEnd", ""),
                        quantityMode = obj.optString("quantityMode", "AUTO"),
                        quantityValue = obj.optString("quantityValue", ""),
                        quantityStart = obj.optString("quantityStart", ""),
                        quantityEnd = obj.optString("quantityEnd", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveParserTemplates(context: Context, templates: List<ParserTemplate>) {
        val arr = JSONArray()
        for (t in templates) {
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("includeWords", t.includeWords)
                    put("excludeWords", t.excludeWords)
                    put("actionType", t.actionType)
                    put("category", t.category)
                    put("merchantMode", t.merchantMode)
                    put("merchantValue", t.merchantValue)
                    put("merchantStart", t.merchantStart)
                    put("merchantEnd", t.merchantEnd)
                    put("fromAccountMode", t.fromAccountMode)
                    put("fromAccountValue", t.fromAccountValue)
                    put("fromAccountStart", t.fromAccountStart)
                    put("fromAccountEnd", t.fromAccountEnd)
                    put("toAccountMode", t.toAccountMode)
                    put("toAccountValue", t.toAccountValue)
                    put("toAccountStart", t.toAccountStart)
                    put("toAccountEnd", t.toAccountEnd)
                    put("itemMode", t.itemMode)
                    put("itemValue", t.itemValue)
                    put("itemStart", t.itemStart)
                    put("itemEnd", t.itemEnd)
                    put("amountMode", t.amountMode)
                    put("amountValue", t.amountValue)
                    put("amountStart", t.amountStart)
                    put("amountEnd", t.amountEnd)
                    put("quantityMode", t.quantityMode)
                    put("quantityValue", t.quantityValue)
                    put("quantityStart", t.quantityStart)
                    put("quantityEnd", t.quantityEnd)
                }
            )
        }
        prefs(context).edit().putString(KEY_PARSER_TEMPLATES, arr.toString()).apply()
    }

    fun addParserTemplate(context: Context, template: ParserTemplate) {
        val cleanName = template.name.trim()
        if (cleanName.isEmpty()) return
        val current = getParserTemplates(context).toMutableList()
        val id = if (template.id.isNotBlank()) template.id else "${System.currentTimeMillis()}_${(100..999).random()}"
        current.add(template.copy(id = id, name = cleanName))
        saveParserTemplates(context, current)
    }

    fun updateParserTemplate(context: Context, template: ParserTemplate) {
        val cleanName = template.name.trim()
        if (cleanName.isEmpty()) return
        val updated = getParserTemplates(context).map { item ->
            if (item.id == template.id) template.copy(name = cleanName) else item
        }
        saveParserTemplates(context, updated)
    }

    fun removeParserTemplate(context: Context, id: String) {
        val updated = getParserTemplates(context).filterNot { it.id == id }
        saveParserTemplates(context, updated)
    }

    // --- 계좌 관리 ---

    fun getAccounts(context: Context): List<AccountItem> {
        val raw = prefs(context).getString(KEY_ACCOUNTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<AccountItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    AccountItem(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        institution = obj.optString("institution", ""),
                        initialBalance = obj.optLong("initialBalance", 0L),
                        keywords = obj.optString("keywords", ""),
                        autoTransferNote = obj.optString("autoTransferNote", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAccounts(context: Context, items: List<AccountItem>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("institution", item.institution)
                    put("initialBalance", item.initialBalance)
                    put("keywords", item.keywords)
                    put("autoTransferNote", item.autoTransferNote)
                }
            )
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun addAccount(
        context: Context,
        name: String,
        institution: String,
        initialBalance: Long,
        keywords: String,
        autoTransferNote: String = ""
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val current = getAccounts(context).toMutableList()
        val id = "${System.currentTimeMillis()}_${(100..999).random()}"
        val kw = keywords.trim().ifEmpty { cleanName }
        current.add(
            AccountItem(
                id = id,
                name = cleanName,
                institution = institution.trim(),
                initialBalance = initialBalance,
                keywords = kw,
                autoTransferNote = autoTransferNote.trim()
            )
        )
        saveAccounts(context, current)
    }

    fun updateAccount(
        context: Context,
        id: String,
        name: String,
        institution: String,
        initialBalance: Long,
        keywords: String,
        autoTransferNote: String = ""
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val current = getAccounts(context).map { acc ->
            if (acc.id == id) {
                acc.copy(
                    name = cleanName,
                    institution = institution.trim(),
                    initialBalance = initialBalance,
                    keywords = keywords.trim().ifEmpty { cleanName },
                    autoTransferNote = autoTransferNote.trim()
                )
            } else {
                acc
            }
        }
        saveAccounts(context, current)
    }

    fun removeAccount(context: Context, id: String) {
        val updated = getAccounts(context).filterNot { it.id == id }
        saveAccounts(context, updated)
    }

    private fun matchesAccount(account: AccountItem, text: String): Boolean {
        if (text.isBlank()) return false
        val lowerText = text.lowercase(Locale.KOREA)
        val tokens = mutableListOf<String>()
        if (account.name.isNotBlank()) tokens.add(account.name.trim().lowercase(Locale.KOREA))
        if (account.institution.isNotBlank()) tokens.add(account.institution.trim().lowercase(Locale.KOREA))
        account.keywords.split(",", "/", "|", " ")
            .map { it.trim().lowercase(Locale.KOREA) }
            .filter { it.length >= 2 }
            .forEach { tokens.add(it) }
        return tokens.distinct().any { lowerText.contains(it) }
    }

    fun calculateAccountSummary(context: Context, account: AccountItem): AccountFlowSummary {
        val records = getRecords(context)
        var balance = account.initialBalance
        var inflow = 0L
        var outflow = 0L
        val matched = mutableListOf<JSONObject>()

        for (i in 0 until records.length()) {
            val obj = records.optJSONObject(i) ?: continue
            val type = obj.optString("type", "exp")
            val amount = obj.optLong("amount", 0L)
            if (amount <= 0L) continue

            val fromAcc = obj.optString("fromAccount", "")
            val toAcc = obj.optString("toAccount", "")
            val merchant = obj.optString("merchant", obj.optString("desc", ""))
            val raw = obj.optString("raw", "")

            val isFromMatch = matchesAccount(account, "$fromAcc $raw") ||
                (type == "trn" && merchant.substringBefore("→", "").let { matchesAccount(account, it) })
            val isToMatch = matchesAccount(account, "$toAcc $raw") ||
                (type == "trn" && merchant.substringAfter("→", "").let { matchesAccount(account, it) })

            when (type) {
                "inc" -> {
                    if (isToMatch || matchesAccount(account, "$merchant $fromAcc $toAcc $raw")) {
                        balance += amount
                        inflow += amount
                        matched.add(obj)
                    }
                }
                "trn" -> {
                    if (isToMatch && !isFromMatch) {
                        balance += amount
                        inflow += amount
                        matched.add(obj)
                    } else if (isFromMatch && !isToMatch) {
                        balance -= amount
                        outflow += amount
                        matched.add(obj)
                    } else if (isFromMatch && isToMatch) {
                        matched.add(obj)
                    }
                }
                else -> {
                    if (isFromMatch || matchesAccount(account, "$merchant $fromAcc $toAcc $raw")) {
                        balance -= amount
                        outflow += amount
                        matched.add(obj)
                    }
                }
            }
        }

        return AccountFlowSummary(
            balance = balance,
            inflow = inflow,
            outflow = outflow,
            matchedRecords = matched
        )
    }

    // --- 내역 저장/조회 ---

    fun getRecords(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveRecords(context: Context, arr: JSONArray) {
        prefs(context).edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    fun addManualRecordWithDate(
        context: Context,
        type: String,
        category: String,
        merchant: String,
        fromAccount: String,
        toAccount: String,
        item: String,
        quantity: String,
        amount: Long,
        dateStr: String,
        timestamp: Long
    ) {
        val current = getRecords(context)
        val obj = JSONObject().apply {
            put("id", "${timestamp}_${(100..999).random()}")
            put("type", type)
            put("category", category)
            put("merchant", merchant)
            put("desc", merchant)
            put("fromAccount", fromAccount)
            put("toAccount", toAccount)
            put("balanceInfo", "")
            put("item", item)
            put("quantity", quantity)
            put("amount", amount)
            put("date", dateStr)
            put("timestamp", timestamp)
            put("raw", "")
            put("itemsJson", "[]")
            put("imagePath", "")
            put("auto", false)
        }
        val updated = JSONArray().apply {
            put(obj)
            for (i in 0 until current.length()) {
                put(current.getJSONObject(i))
            }
        }
        saveRecords(context, updated)
    }

    fun addOrMergeRecord(
        context: Context,
        type: String,
        category: String,
        merchant: String,
        fromAccount: String,
        toAccount: String,
        balanceInfo: String,
        item: String,
        quantity: String,
        amount: Long,
        raw: String,
        itemsJson: String = "[]",
        imagePath: String = "",
        auto: Boolean = true,
        customTimestamp: Long? = null
    ) {
        val ts = customTimestamp ?: System.currentTimeMillis()
        val dateStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(ts))
        val current = getRecords(context)

        for (i in 0 until current.length()) {
            val existing = current.getJSONObject(i)
            val exAmt = existing.optLong("amount", 0L)
            val exTs = existing.optLong("timestamp", 0L)
            val exMerchant = existing.optString("merchant", existing.optString("desc", ""))
            if (exAmt == amount && kotlin.math.abs(ts - exTs) <= 12 * 3600 * 1000L) {
                val m1 = exMerchant.replace(Regex("[^가-힣a-zA-Z0-9]"), "").lowercase(Locale.KOREA)
                val m2 = merchant.replace(Regex("[^가-힣a-zA-Z0-9]"), "").lowercase(Locale.KOREA)
                if (m1.isNotEmpty() && m2.isNotEmpty() && (m1.contains(m2) || m2.contains(m1))) {
                    existing.put("type", type)
                    existing.put("category", category)
                    existing.put("merchant", merchant.ifEmpty { exMerchant })
                    existing.put("desc", merchant.ifEmpty { exMerchant })
                    if (fromAccount.isNotEmpty()) existing.put("fromAccount", fromAccount)
                    if (toAccount.isNotEmpty()) existing.put("toAccount", toAccount)
                    if (balanceInfo.isNotEmpty()) existing.put("balanceInfo", balanceInfo)
                    if (item.isNotEmpty()) existing.put("item", item)
                    if (quantity.isNotEmpty()) existing.put("quantity", quantity)
                    if (raw.length > existing.optString("raw", "").length) existing.put("raw", raw)
                    if (itemsJson.length > 2) existing.put("itemsJson", itemsJson)
                    if (imagePath.isNotEmpty()) existing.put("imagePath", imagePath)
                    saveRecords(context, current)
                    return
                }
            }
        }

        val obj = JSONObject().apply {
            put("id", "${ts}_${(100..999).random()}")
            put("type", type)
            put("category", category)
            put("merchant", merchant)
            put("desc", merchant)
            put("fromAccount", fromAccount)
            put("toAccount", toAccount)
            put("balanceInfo", balanceInfo)
            put("item", item)
            put("quantity", quantity)
            put("amount", amount)
            put("date", dateStr)
            put("timestamp", ts)
            put("raw", raw)
            put("itemsJson", itemsJson)
            put("imagePath", imagePath)
            put("auto", auto)
        }
        val updated = JSONArray().apply {
            put(obj)
            for (i in 0 until current.length()) {
                put(current.getJSONObject(i))
            }
        }
        saveRecords(context, updated)
    }

    fun updateRecord(
        context: Context,
        id: String,
        type: String,
        category: String,
        merchant: String,
        fromAccount: String,
        toAccount: String,
        item: String,
        quantity: String,
        amount: Long,
        raw: String,
        imagePath: String,
        dateStr: String,
        timestamp: Long
    ) {
        val current = getRecords(context)
        for (i in 0 until current.length()) {
            val obj = current.getJSONObject(i)
            if (obj.optString("id", "") == id) {
                obj.put("type", type)
                obj.put("category", category)
                obj.put("merchant", merchant)
                obj.put("desc", merchant)
                obj.put("fromAccount", fromAccount)
                obj.put("toAccount", toAccount)
                obj.put("item", item)
                obj.put("quantity", quantity)
                obj.put("amount", amount)
                obj.put("raw", raw)
                obj.put("imagePath", imagePath)
                obj.put("date", dateStr)
                obj.put("timestamp", timestamp)
                break
            }
        }
        saveRecords(context, current)
    }

    fun deleteRecordById(context: Context, id: String) {
        val current = getRecords(context)
        val updated = JSONArray()
        for (i in 0 until current.length()) {
            val obj = current.getJSONObject(i)
            if (obj.optString("id", "") != id) {
                updated.put(obj)
            }
        }
        saveRecords(context, updated)
    }

    fun splitRecordIntoItems(context: Context, recordObj: JSONObject, itemsArr: JSONArray) {
        val origId = recordObj.optString("id", "")
        val type = recordObj.optString("type", "exp")
        val category = recordObj.optString("category", "기타")
        val merchant = recordObj.optString("merchant", recordObj.optString("desc", ""))
        val fromAccount = recordObj.optString("fromAccount", "")
        val toAccount = recordObj.optString("toAccount", "")
        val dateStr = recordObj.optString("date", "")
        val ts = recordObj.optLong("timestamp", System.currentTimeMillis())
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)

        val current = getRecords(context)
        val updated = JSONArray()

        for (i in 0 until itemsArr.length()) {
            val itObj = itemsArr.optJSONObject(i) ?: continue
            val name = itObj.optString("name", "품목 ${i + 1}")
            val qty = itObj.optInt("qty", 1).coerceAtLeast(1)
            val amt = itObj.optLong("amount", 0L)
            if (amt <= 0L) continue
            updated.put(
                JSONObject().apply {
                    put("id", "${ts}_${i}_${(100..999).random()}")
                    put("type", type)
                    put("category", category)
                    put("merchant", "$merchant ($name)")
                    put("desc", "$merchant ($name)")
                    put("fromAccount", fromAccount)
                    put("toAccount", toAccount)
                    put("balanceInfo", "")
                    put("item", "• $name (${qty}개) : ${nf.format(amt)}원")
                    put("quantity", "${qty}개")
                    put("amount", amt)
                    put("date", dateStr)
                    put("timestamp", ts)
                    put("raw", "")
                    put("itemsJson", "[]")
                    put("imagePath", "")
                    put("auto", false)
                }
            )
        }

        for (i in 0 until current.length()) {
            val obj = current.getJSONObject(i)
            if (obj.optString("id", "") != origId) {
                updated.put(obj)
            }
        }
        saveRecords(context, updated)
    }

    // --- 고정 지출 ---

    fun getFixedExpenses(context: Context): List<FixedExpenseItem> {
        val raw = prefs(context).getString(KEY_FIXED_EXPENSES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<FixedExpenseItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val startD = if (obj.has("startDay")) obj.optInt("startDay", 1) else obj.optInt("dayOfMonth", 1)
                val endD = if (obj.has("endDay")) obj.optInt("endDay", startD) else startD
                list.add(
                    FixedExpenseItem(
                        id = obj.optString("id", ""),
                        title = obj.optString("title", ""),
                        startDay = startD,
                        endDay = endD,
                        amount = obj.optLong("amount", 0L),
                        keyword = obj.optString("keyword", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveFixedExpenses(context: Context, list: List<FixedExpenseItem>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("startDay", item.startDay)
                    put("endDay", item.endDay)
                    put("dayOfMonth", item.startDay)
                    put("amount", item.amount)
                    put("keyword", item.keyword)
                }
            )
        }
        prefs(context).edit().putString(KEY_FIXED_EXPENSES, arr.toString()).apply()
    }

    fun addFixedExpense(
        context: Context,
        title: String,
        startDay: Int,
        endDay: Int,
        amount: Long,
        keyword: String
    ) {
        val current = getFixedExpenses(context).toMutableList()
        current.add(
            FixedExpenseItem(
                id = "${System.currentTimeMillis()}_${(100..999).random()}",
                title = title,
                startDay = startDay,
                endDay = endDay,
                amount = amount,
                keyword = keyword
            )
        )
        saveFixedExpenses(context, current)
    }

    fun updateFixedExpense(
        context: Context,
        id: String,
        title: String,
        startDay: Int,
        endDay: Int,
        amount: Long,
        keyword: String
    ) {
        val current = getFixedExpenses(context).map { fe ->
            if (fe.id == id) {
                fe.copy(
                    title = title,
                    startDay = startDay,
                    endDay = endDay,
                    amount = amount,
                    keyword = keyword
                )
            } else {
                fe
            }
        }
        saveFixedExpenses(context, current)
    }

    fun removeFixedExpense(context: Context, id: String) {
        val updated = getFixedExpenses(context).filterNot { it.id == id }
        saveFixedExpenses(context, updated)
    }

    // --- 단어 자동 분류 · 통과 · 무시 규칙 (AutoRule) ---

    fun getAutoRules(context: Context): List<AutoRule> {
        val raw = prefs(context).getString(KEY_AUTO_RULES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<AutoRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    AutoRule(
                        id = obj.optString("id", ""),
                        keyword = obj.optString("keyword", ""),
                        ignoreKeywords = obj.optString("ignoreKeywords", ""),
                        action = obj.optString("action", "category"),
                        category = obj.optString("category", "기타")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAutoRules(context: Context, list: List<AutoRule>) {
        val arr = JSONArray()
        for (rule in list) {
            arr.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("keyword", rule.keyword)
                    put("ignoreKeywords", rule.ignoreKeywords)
                    put("action", rule.action)
                    put("category", rule.category)
                }
            )
        }
        prefs(context).edit().putString(KEY_AUTO_RULES, arr.toString()).apply()
    }

    fun ensureDefaultRules(context: Context) {
        val current = getAutoRules(context)
        if (current.isNotEmpty()) return
        saveAutoRules(
            context,
            listOf(
                AutoRule("default_ad", "", "광고, 쿠폰, 이벤트, 포인트 적립", "ignore", "기타"),
                AutoRule("default_pass_pay", "승인, 결제, 입금, 출금, 이체, USD, ₩", "광고, 쿠폰, 무료체험", "pass", "기타"),
                AutoRule("default_me", "이정현", "", "transfer", "이체/계좌이동")
            )
        )
    }

    fun addAutoRule(
        context: Context,
        keyword: String,
        ignoreKeywords: String,
        action: String,
        category: String
    ) {
        val cleanKw = keyword.trim()
        val cleanIgnore = ignoreKeywords.trim()
        if (cleanKw.isEmpty() && cleanIgnore.isEmpty()) return

        val current = getAutoRules(context).toMutableList()
        current.add(
            AutoRule(
                id = "${System.currentTimeMillis()}_${(100..999).random()}",
                keyword = cleanKw,
                ignoreKeywords = cleanIgnore,
                action = action,
                category = category
            )
        )
        saveAutoRules(context, current)
    }

    fun updateAutoRule(
        context: Context,
        id: String,
        keyword: String,
        ignoreKeywords: String,
        action: String,
        category: String
    ) {
        val cleanKw = keyword.trim()
        val cleanIgnore = ignoreKeywords.trim()
        if (cleanKw.isEmpty() && cleanIgnore.isEmpty()) return

        val updated = getAutoRules(context).map { r ->
            if (r.id == id) {
                r.copy(
                    keyword = cleanKw,
                    ignoreKeywords = cleanIgnore,
                    action = action,
                    category = category
                )
            } else {
                r
            }
        }
        saveAutoRules(context, updated)
    }

    fun removeAutoRule(context: Context, id: String) {
        val updated = getAutoRules(context).filterNot { it.id == id }
        saveAutoRules(context, updated)
    }

    fun applyAutoRulesToExistingRecords(context: Context): Int {
        val rules = getAutoRules(context)
        if (rules.isEmpty()) return 0
        val current = getRecords(context)
        val updated = JSONArray()
        var changedCount = 0

        for (i in 0 until current.length()) {
            val obj = current.getJSONObject(i)
            val hay = "${obj.optString("merchant", "")} ${obj.optString("fromAccount", "")} ${obj.optString("toAccount", "")} ${obj.optString("item", "")} ${obj.optString("raw", "")}"
                .lowercase(Locale.KOREA)

            // 1. 무시/삭제 단어 먼저 확인
            val shouldDeleteOrIgnore = rules.any { r ->
                val ignTokens = r.ignoreKeywords.split(",", "\n", "|")
                    .map { it.trim().lowercase(Locale.KOREA) }
                    .filter { it.isNotEmpty() }
                val hasIgnoreToken = ignTokens.any { hay.contains(it) }

                val delTokens = if (r.action == "delete" || r.action == "ignore") {
                    r.keyword.split(",", "\n", "|")
                        .map { it.trim().lowercase(Locale.KOREA) }
                        .filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                val hasDelToken = delTokens.any { hay.contains(it) }

                hasIgnoreToken || hasDelToken
            }

            if (shouldDeleteOrIgnore) {
                changedCount++
                continue
            }

            // 2. 이체 / 카테고리 규칙 확인
            var matchedRule: AutoRule? = null
            for (r in rules) {
                if (r.action != "transfer" && r.action != "category") continue
                val kwTokens = r.keyword.split(",", "\n", "|")
                    .map { it.trim().lowercase(Locale.KOREA) }
                    .filter { it.isNotEmpty() }
                if (kwTokens.any { hay.contains(it) }) {
                    matchedRule = r
                    break
                }
            }

            if (matchedRule == null) {
                updated.put(obj)
                continue
            }

            when (matchedRule.action) {
                "transfer" -> {
                    if (obj.optString("type", "") != "trn" || obj.optString("category", "") != "이체/계좌이동") {
                        obj.put("type", "trn")
                        obj.put("category", "이체/계좌이동")
                        changedCount++
                    }
                    updated.put(obj)
                }
                "category" -> {
                    if (obj.optString("category", "") != matchedRule.category) {
                        obj.put("category", matchedRule.category)
                        changedCount++
                    }
                    updated.put(obj)
                }
                else -> updated.put(obj)
            }
        }

        if (changedCount > 0) {
            saveRecords(context, updated)
        }
        return changedCount
    }

    // --- 검색 기록 / 이미지 / 이메일 설정 ---

    fun getSearchHistory(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SEARCH_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSearchHistory(context: Context, query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        val next = mutableListOf(clean)
        getSearchHistory(context).filterNot { it == clean }.take(7).forEach { next.add(it) }
        prefs(context).edit().putString(KEY_SEARCH_HISTORY, JSONArray(next).toString()).apply()
    }

    fun clearSearchHistory(context: Context) {
        prefs(context).edit().putString(KEY_SEARCH_HISTORY, "[]").apply()
    }

    fun saveBitmapToFile(context: Context, bitmap: Bitmap): String {
        return try {
            val dir = File(context.filesDir, "receipt_images").apply { mkdirs() }
            val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    fun ensureDefaultEmailPeriod(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_EMAIL_PERIOD_START) || !p.contains(KEY_EMAIL_PERIOD_END)) {
            val now = System.currentTimeMillis()
            val weekAgo = now - 7L * 24L * 3600L * 1000L
            p.edit()
                .putLong(KEY_EMAIL_PERIOD_START, weekAgo)
                .putLong(KEY_EMAIL_PERIOD_END, now)
                .apply()
        }
    }

    fun getEmailProvider(context: Context): String =
        prefs(context).getString(KEY_EMAIL_PROVIDER, "gmail") ?: "gmail"

    fun getEmailAddress(context: Context): String =
        prefs(context).getString(KEY_EMAIL_ADDR, "") ?: ""

    fun getEmailPassword(context: Context): String =
        prefs(context).getString(KEY_EMAIL_PASS, "") ?: ""

    fun saveEmailConfig(context: Context, provider: String, address: String, password: String) {
        prefs(context).edit()
            .putString(KEY_EMAIL_PROVIDER, provider)
            .putString(KEY_EMAIL_ADDR, address)
            .putString(KEY_EMAIL_PASS, password)
            .apply()
    }

    fun getEmailPeriodStart(context: Context): Long {
        ensureDefaultEmailPeriod(context)
        return prefs(context).getLong(KEY_EMAIL_PERIOD_START, System.currentTimeMillis() - 7L * 86_400_000L)
    }

    fun getEmailPeriodEnd(context: Context): Long {
        ensureDefaultEmailPeriod(context)
        return prefs(context).getLong(KEY_EMAIL_PERIOD_END, System.currentTimeMillis())
    }

    fun saveEmailPeriod(context: Context, startMs: Long, endMs: Long) {
        prefs(context).edit()
            .putLong(KEY_EMAIL_PERIOD_START, startMs)
            .putLong(KEY_EMAIL_PERIOD_END, endMs)
            .apply()
    }

    fun getEmailPeriodLabel(context: Context): String {
        val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
        return "${fmt.format(Date(getEmailPeriodStart(context)))} ~ ${fmt.format(Date(getEmailPeriodEnd(context)))}"
    }
}
