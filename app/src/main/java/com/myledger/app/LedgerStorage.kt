package com.myledger.app

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AutoRule(
    val id: String,
    val keyword: String,
    val action: String,
    val category: String
)

data class FixedExpenseItem(
    val id: String,
    val title: String,
    val startDay: Int,
    val endDay: Int,
    val amount: Long,
    val keyword: String
)

object LedgerStorage {
    private const val PREF_NAME = "AutoLedgerPrefs"
    private const val KEY_RECORDS = "records"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_HISTORY = "search_history"
    private const val KEY_EMAIL_PROV = "email_provider"
    private const val KEY_EMAIL_ADDR = "email_address"
    private const val KEY_EMAIL_PASS = "email_password"
    private const val KEY_EMAIL_PERIOD_START = "email_period_start"
    private const val KEY_EMAIL_PERIOD_END = "email_period_end"
    private const val KEY_AUTO_RULES = "auto_rules_v1"
    private const val KEY_RULES_INIT = "auto_rules_initialized"
    private const val KEY_FIXED_EXPENSES = "fixed_expenses_v1"

    fun getFixedExpenses(context: Context): List<FixedExpenseItem> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FIXED_EXPENSES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val sDay = o.optInt("startDay", o.optInt("payDay", 1)).coerceIn(0, 31)
                val eDay = o.optInt("endDay", o.optInt("payDay", sDay)).coerceIn(0, 31)
                FixedExpenseItem(
                    id = o.optString("id", i.toString()),
                    title = o.optString("title", ""),
                    startDay = sDay,
                    endDay = eDay,
                    amount = o.optLong("amount", 0L),
                    keyword = o.optString("keyword", "")
                )
            }.sortedBy { if (it.startDay <= 0 || it.startDay >= 31) 32 else it.startDay }
        } catch (_: Exception) {
            emptyList()
        }
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
                id = System.currentTimeMillis().toString(),
                title = title,
                startDay = startDay.coerceIn(0, 31),
                endDay = endDay.coerceIn(0, 31),
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
        val updated = getFixedExpenses(context).map { item ->
            if (item.id == id) {
                FixedExpenseItem(
                    id = item.id,
                    title = title,
                    startDay = startDay.coerceIn(0, 31),
                    endDay = endDay.coerceIn(0, 31),
                    amount = amount,
                    keyword = keyword
                )
            } else {
                item
            }
        }
        saveFixedExpenses(context, updated)
    }

    fun removeFixedExpense(context: Context, id: String) {
        val filtered = getFixedExpenses(context).filter { it.id != id }
        saveFixedExpenses(context, filtered)
    }

    private fun saveFixedExpenses(context: Context, items: List<FixedExpenseItem>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("startDay", it.startDay)
                put("endDay", it.endDay)
                put("amount", it.amount)
                put("keyword", it.keyword)
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_FIXED_EXPENSES, arr.toString()).apply()
    }

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, true)
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, enabled).apply()
    }

    fun getFontScale(context: Context): Float {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_FONT_SCALE, 1.0f)
    }

    fun setFontScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    fun ensureDefaultEmailPeriod(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_EMAIL_PERIOD_START)) {
            val nowCal = Calendar.getInstance()
            val endMs = Calendar.getInstance().apply {
                set(nowCal.get(Calendar.YEAR), nowCal.get(Calendar.MONTH), nowCal.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
            }.timeInMillis
            val startCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -6) }
            val startMs = Calendar.getInstance().apply {
                set(startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            }.timeInMillis
            saveEmailPeriod(context, startMs, endMs)
        }
    }

    fun saveEmailPeriod(context: Context, startMillis: Long, endMillis: Long) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_EMAIL_PERIOD_START, startMillis)
            .putLong(KEY_EMAIL_PERIOD_END, endMillis)
            .apply()
    }

    fun getEmailPeriodStart(context: Context): Long {
        ensureDefaultEmailPeriod(context)
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EMAIL_PERIOD_START, System.currentTimeMillis() - 6 * 86_400_000L)
    }

    fun getEmailPeriodEnd(context: Context): Long {
        ensureDefaultEmailPeriod(context)
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_EMAIL_PERIOD_END, System.currentTimeMillis())
    }

    fun getEmailPeriodLabel(context: Context): String {
        val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
        return "${fmt.format(Date(getEmailPeriodStart(context)))} ~ ${fmt.format(Date(getEmailPeriodEnd(context)))}"
    }

    fun ensureDefaultRules(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_RULES_INIT, false)) return
        val defaults = listOf("이정현", "토뱅", "토스뱅크", "카뱅", "카카오뱅크", "내계좌")
        val arr = JSONArray()
        defaults.forEachIndexed { idx, kw ->
            arr.put(JSONObject().apply {
                put("id", "def_$idx")
                put("keyword", kw)
                put("action", "transfer")
                put("category", "이체/계좌이동")
            })
        }
        prefs.edit()
            .putString(KEY_AUTO_RULES, arr.toString())
            .putBoolean(KEY_RULES_INIT, true)
            .apply()
    }

    fun getAutoRules(context: Context): List<AutoRule> {
        ensureDefaultRules(context)
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUTO_RULES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AutoRule(
                    id = o.optString("id", i.toString()),
                    keyword = o.optString("keyword", ""),
                    action = o.optString("action", "category"),
                    category = o.optString("category", "기타")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addAutoRule(context: Context, keyword: String, action: String, category: String) {
        val current = getAutoRules(context).toMutableList()
        current.add(
            0,
            AutoRule(
                id = System.currentTimeMillis().toString(),
                keyword = keyword,
                action = action,
                category = category
            )
        )
        saveAutoRules(context, current)
    }

    fun removeAutoRule(context: Context, id: String) {
        val filtered = getAutoRules(context).filter { it.id != id }
        saveAutoRules(context, filtered)
    }

    private fun saveAutoRules(context: Context, rules: List<AutoRule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("keyword", r.keyword)
                put("action", r.action)
                put("category", r.category)
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTO_RULES, arr.toString()).apply()
    }

    fun applyAutoRulesToExistingRecords(context: Context): Int {
        val rules = getAutoRules(context)
        if (rules.isEmpty()) return 0
        val oldArr = getRecords(context)
        val newArr = JSONArray()
        var changedCount = 0

        for (i in 0 until oldArr.length()) {
            val obj = oldArr.getJSONObject(i)
            val combined = "${obj.optString("merchant")} ${obj.optString("fromAccount")} ${obj.optString("toAccount")} ${obj.optString("item")} ${obj.optString("raw")}"

            val delRule = rules.firstOrNull { it.action == "delete" && it.keyword.isNotBlank() && combined.contains(it.keyword, ignoreCase = true) }
            if (delRule != null) {
                changedCount++
                continue
            }

            val trnRule = rules.firstOrNull { it.action == "transfer" && it.keyword.isNotBlank() && combined.contains(it.keyword, ignoreCase = true) }
            if (trnRule != null && obj.optString("type") != "trn") {
                obj.put("type", "trn")
                obj.put("category", "이체/계좌이동")
                changedCount++
                newArr.put(obj)
                continue
            }

            val catRule = rules.firstOrNull { it.action == "category" && it.keyword.isNotBlank() && combined.contains(it.keyword, ignoreCase = true) }
            if (catRule != null && obj.optString("category") != catRule.category) {
                obj.put("category", catRule.category)
                if (catRule.category == "수입") obj.put("type", "inc")
                else if (catRule.category == "이체/계좌이동") obj.put("type", "trn")
                else if (obj.optString("type") == "inc") obj.put("type", "exp")
                changedCount++
            }
            newArr.put(obj)
        }
        saveSorted(context, newArr)
        return changedCount
    }

    fun saveEmailConfig(context: Context, provider: String, address: String, password: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_EMAIL_PROV, provider)
            .putString(KEY_EMAIL_ADDR, address)
            .putString(KEY_EMAIL_PASS, password)
            .apply()
    }

    fun getEmailProvider(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_EMAIL_PROV, "gmail") ?: "gmail"

    fun getEmailAddress(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_EMAIL_ADDR, "") ?: ""

    fun getEmailPassword(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_EMAIL_PASS, "") ?: ""

    fun getSearchHistory(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSearchHistory(context: Context, word: String) {
        val current = getSearchHistory(context).toMutableList()
        current.remove(word)
        current.add(0, word)
        val trimmed = current.take(10)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it) }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearSearchHistory(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, "[]").apply()
    }

    fun saveBitmapToFile(context: Context, bitmap: Bitmap): String {
        return try {
            val dir = File(context.filesDir, "receipt_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "img_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    fun getRecords(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            JSONArray(str)
        } catch (_: Exception) {
            JSONArray()
        }
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
        val arr = getRecords(context)
        val record = JSONObject().apply {
            put("id", System.currentTimeMillis().toString())
            put("timestamp", timestamp)
            put("type", type)
            put("category", category)
            put("merchant", merchant)
            put("fromAccount", fromAccount)
            put("toAccount", toAccount)
            put("balanceInfo", "")
            put("item", item)
            put("quantity", quantity)
            put("amount", amount)
            put("date", dateStr)
            put("raw", "직접 입력 내역")
            put("itemsJson", "[]")
            put("imagePath", "")
            put("auto", false)
        }
        arr.put(record)
        saveSorted(context, arr)
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
        itemsJson: String,
        imagePath: String,
        auto: Boolean,
        customTimestamp: Long = System.currentTimeMillis()
    ): Boolean {
        val oldArr = getRecords(context)
        val normNewMerchant = merchant.replace("(주)", "").replace(" ", "").lowercase(Locale.KOREA)

        if (auto) {
            for (i in 0 until oldArr.length()) {
                val existing = oldArr.getJSONObject(i)
                if (!existing.optBoolean("auto", false)) continue
                val exTime = existing.optLong("timestamp", 0L)
                val exAmt = existing.optLong("amount", 0L)
                val exMerchant = existing.optString("merchant", "").replace("(주)", "").replace(" ", "").lowercase(Locale.KOREA)

                val timeDiff = kotlin.math.abs(customTimestamp - exTime)
                val sameMerchant = normNewMerchant == exMerchant ||
                    normNewMerchant.contains(exMerchant) ||
                    exMerchant.contains(normNewMerchant)

                if (exAmt == amount && (timeDiff <= 300_000L || (timeDiff <= 43_200_000L && sameMerchant))) {
                    val exRaw = existing.optString("raw", "")
                    if (raw.length > exRaw.length) {
                        existing.put("raw", raw)
                        existing.put("type", type)
                        if (item.isNotEmpty()) existing.put("item", item)
                        if (quantity.isNotEmpty()) existing.put("quantity", quantity)
                        if (merchant != "거래 내역") existing.put("merchant", merchant)
                        if (fromAccount.isNotEmpty()) existing.put("fromAccount", fromAccount)
                        if (toAccount.isNotEmpty()) existing.put("toAccount", toAccount)
                        if (balanceInfo.isNotEmpty()) existing.put("balanceInfo", balanceInfo)
                        if (category != "기타") existing.put("category", category)
                        if (itemsJson != "[]") existing.put("itemsJson", itemsJson)
                    }
                    if (imagePath.isNotEmpty() && existing.optString("imagePath").isEmpty()) {
                        existing.put("imagePath", imagePath)
                    }
                    saveSorted(context, oldArr)
                    return false
                }
            }
        }

        val dateStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(customTimestamp))
        val uniqueId = "${customTimestamp}_${System.nanoTime() % 10000}"

        val record = JSONObject().apply {
            put("id", uniqueId)
            put("timestamp", customTimestamp)
            put("type", type)
            put("category", category)
            put("merchant", merchant)
            put("fromAccount", fromAccount)
            put("toAccount", toAccount)
            put("balanceInfo", balanceInfo)
            put("item", item)
            put("quantity", quantity)
            put("amount", amount)
            put("date", dateStr)
            put("raw", raw)
            put("itemsJson", itemsJson)
            put("imagePath", imagePath)
            put("auto", auto)
        }
        oldArr.put(record)
        saveSorted(context, oldArr)
        return true
    }

    fun splitRecordIntoItems(context: Context, recordObj: JSONObject, itemsArr: JSONArray) {
        val targetId = recordObj.optString("id", "")
        val type = recordObj.optString("type", "exp")
        val category = recordObj.optString("category", "기타")
        val merchant = recordObj.optString("merchant", "")
        val fromAccount = recordObj.optString("fromAccount", "")
        val toAccount = recordObj.optString("toAccount", "")
        val dateStr = recordObj.optString("date", "")
        val timestamp = recordObj.optLong("timestamp", System.currentTimeMillis())
        val raw = recordObj.optString("raw", "")
        val imagePath = recordObj.optString("imagePath", "")
        val auto = recordObj.optBoolean("auto", true)

        val oldArr = getRecords(context)
        val newArr = JSONArray()

        for (i in 0 until oldArr.length()) {
            val cur = oldArr.getJSONObject(i)
            if (cur.optString("id") == targetId) {
                for (j in 0 until itemsArr.length()) {
                    val itemObj = itemsArr.getJSONObject(j)
                    val iName = itemObj.optString("name", "")
                    val iQty = itemObj.optInt("qty", 1)
                    val iPrice = itemObj.optLong("price", 0L)

                    newArr.put(JSONObject().apply {
                        put("id", "${timestamp}_${j}")
                        put("timestamp", timestamp)
                        put("type", type)
                        put("category", category)
                        put("merchant", "$merchant - $iName")
                        put("fromAccount", fromAccount)
                        put("toAccount", toAccount)
                        put("balanceInfo", "")
                        put("item", "• $iName (${iQty}개) : ${NumberFormat.getNumberInstance(Locale.KOREA).format(iPrice)}원")
                        put("quantity", "${iQty}개")
                        put("amount", iPrice)
                        put("date", dateStr)
                        put("raw", raw)
                        put("itemsJson", "[]")
                        put("imagePath", imagePath)
                        put("auto", auto)
                    })
                }
            } else {
                newArr.put(cur)
            }
        }
        saveSorted(context, newArr)
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
        val arr = getRecords(context)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") == id) {
                obj.put("type", type)
                obj.put("category", category)
                obj.put("merchant", merchant)
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
        saveSorted(context, arr)
    }

    fun deleteRecordById(context: Context, id: String) {
        val oldArr = getRecords(context)
        val newArr = JSONArray()
        for (i in 0 until oldArr.length()) {
            val obj = oldArr.getJSONObject(i)
            if (obj.optString("id") != id) {
                newArr.put(obj)
            }
        }
        saveSorted(context, newArr)
    }

    fun clearAllRecords(context: Context) {
        saveSorted(context, JSONArray())
    }

    private fun saveSorted(context: Context, arr: JSONArray) {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            list.add(arr.getJSONObject(i))
        }
        list.sortByDescending { it.optLong("timestamp", 0L) }
        val sortedArr = JSONArray()
        list.forEach { sortedArr.put(it) }

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDS, sortedArr.toString()).apply()
    }
}
