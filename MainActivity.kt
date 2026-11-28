package com.myledger.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
private fun Context.sp(base: Float): Float = base * LedgerStorage.getFontScale(this)
private fun Context.roundBg(color: Int, radiusDp: Int): GradientDrawable {
    return GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }
}

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_IMAGE = 1001
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var navDivider: View
    private lateinit var bottomNavBar: LinearLayout

    private var currentTab = "all"
    private var selectedAnalysisCategory = "전체"
    private var currentQuery = ""
    private var isSearchExpanded = false

    private var lastAllScrollY = 0
    private var lastAnalysisRightScrollY = 0
    private var activeAllScrollView: ScrollView? = null
    private var activeAnalysisRightScrollView: ScrollView? = null

    private val pieIncludedCategories = linkedSetOf(
        "식비", "카페/간식", "쇼핑/생활", "교통/차량", "구독/통신", "의료/건강", "고정 지출", "기타"
    )

    private var filterStartMillis: Long? = null
    private var filterEndMillis: Long? = null

    private var pendingImagePath: String = ""
    private var dialogImageView: ImageView? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val isDark = LedgerStorage.isDarkMode(this)
        setTheme(
            if (isDark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)

        LedgerStorage.ensureDefaultRules(this)
        LedgerStorage.ensureDefaultEmailPeriod(this)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(contentHost)

        navDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }
        rootLayout.addView(navDivider)

        bottomNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(bottomNavBar)

        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.myledger.app.UPDATE_LEDGER")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
        refreshUI()
    }

    override fun onPause() {
        super.onPause()
        captureScrollPositions()
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Exception) {
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab != "all") {
            currentTab = "all"
            refreshUI()
        } else {
            super.onBackPressed()
        }
    }

    private fun isNotificationGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun captureScrollPositions() {
        activeAllScrollView?.let { lastAllScrollY = it.scrollY }
        activeAnalysisRightScrollView?.let { lastAnalysisRightScrollY = it.scrollY }
    }

    private fun refreshUI() {
        captureScrollPositions()
        activeAllScrollView = null
        activeAnalysisRightScrollView = null

        val isDark = LedgerStorage.isDarkMode(this)

        val bgPage = if (isDark) Color.parseColor("#0F0F0F") else Color.parseColor("#F4F4F6")
        val bgNav = if (isDark) Color.parseColor("#141414") else Color.WHITE
        val dividerColor = if (isDark) Color.parseColor("#262629") else Color.parseColor("#E5E5EA")

        rootLayout.setBackgroundColor(bgPage)
        navDivider.setBackgroundColor(dividerColor)
        bottomNavBar.setBackgroundColor(bgNav)

        renderBottomNavBar(isDark)

        contentHost.removeAllViews()
        when (currentTab) {
            "all" -> {
                val scroll = ScrollView(this)
                activeAllScrollView = scroll
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(24))
                }
                renderAllTab(isDark, container)
                scroll.addView(container)
                contentHost.addView(scroll)

                val restoreY = lastAllScrollY
                if (restoreY > 0) {
                    scroll.post { scroll.scrollTo(0, restoreY) }
                }
            }
            "analysis" -> {
                renderCombinedAnalysisTab(isDark)
                val rightScroll = activeAnalysisRightScrollView
                val restoreY = lastAnalysisRightScrollY
                if (rightScroll != null && restoreY > 0) {
                    rightScroll.post { rightScroll.scrollTo(0, restoreY) }
                }
            }
            "settings" -> {
                val scroll = ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(28))
                }
                renderSettingsTab(isDark, container)
                scroll.addView(container)
                contentHost.addView(scroll)
            }
        }
    }

    private fun renderBottomNavBar(isDark: Boolean) {
        bottomNavBar.removeAllViews()

        val tabs = listOf(
            Triple("all", "▤", "전체"),
            Triple("analysis", "◔", "분석"),
            Triple("settings", "⚙", "설정")
        )

        val activeAccent = Color.parseColor("#C83E4D")
        val activeText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val inactiveColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#6B7280")

        for ((tabKey, iconGlyph, labelText) in tabs) {
            val isSelected = (currentTab == tabKey)

            val tabItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setOnClickListener {
                    if (currentTab != tabKey) {
                        currentTab = tabKey
                        refreshUI()
                    }
                }
            }

            val iconView = TextView(this).apply {
                text = iconGlyph
                textSize = sp(19f)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isSelected) activeAccent else inactiveColor)
            }
            tabItem.addView(iconView)

            val labelView = TextView(this).apply {
                text = labelText
                textSize = sp(11f)
                gravity = Gravity.CENTER
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) activeText else inactiveColor)
                setPadding(0, dp(2), 0, 0)
            }
            tabItem.addView(labelView)

            bottomNavBar.addView(tabItem)
        }
    }

    private fun getDialogTheme(isDark: Boolean): Int {
        return if (isDark) {
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
        }
    }

    private fun makeThemedSpinner(isDark: Boolean, items: List<String>, selectedIndex: Int = 0): Spinner {
        val textColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val dropBg = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val boxBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(textColor)
                tv.textSize = sp(14f)
                tv.setPadding(dp(12), dp(10), dp(12), dp(10))
                return tv
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getDropDownView(position, convertView, parent) as TextView
                tv.setTextColor(textColor)
                tv.setBackgroundColor(dropBg)
                tv.textSize = sp(14f)
                tv.setPadding(dp(14), dp(12), dp(14), dp(12))
                return tv
            }
        }

        return Spinner(this).apply {
            this.adapter = adapter
            background = roundBg(boxBg, 10)
            if (selectedIndex in items.indices) {
                setSelection(selectedIndex)
            }
        }
    }

    private fun styleDialogEditText(edit: EditText, isDark: Boolean) {
        val textColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val hintColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#6B7280")
        val boxBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        edit.setTextColor(textColor)
        edit.setHintTextColor(hintColor)
        edit.background = roundBg(boxBg, 10)
        edit.setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun makeToggleSwitch(isDark: Boolean, isOn: Boolean, onToggle: () -> Unit): LinearLayout {
        val trackOn = Color.parseColor("#9E2A36")
        val trackOff = if (isDark) Color.parseColor("#3A3A3C") else Color.parseColor("#D1D5DB")
        val thumbColor = Color.WHITE

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isOn) (Gravity.END or Gravity.CENTER_VERTICAL) else (Gravity.START or Gravity.CENTER_VERTICAL)
            background = roundBg(if (isOn) trackOn else trackOff, 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(26))
            setOnClickListener { onToggle() }

            addView(View(this@MainActivity).apply {
                background = roundBg(thumbColor, 10)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            })
        }
    }

    private fun makePillButton(isDark: Boolean, label: String, isActive: Boolean = false, onClick: () -> Unit): TextView {
        val activeBg = Color.parseColor("#B93844")
        val neutralBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        val neutralText = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")

        return TextView(this).apply {
            text = if (isActive) "✓ $label" else label
            textSize = sp(12f)
            gravity = Gravity.CENTER
            setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isActive) Color.WHITE else neutralText)
            background = roundBg(if (isActive) activeBg else neutralBg, 18)
            setPadding(dp(13), dp(7), dp(13), dp(7))
            setOnClickListener { onClick() }
        }
    }

    private fun makeDivider(isDark: Boolean): View {
        val lineColor = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        return View(this).apply {
            setBackgroundColor(lineColor)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        }
    }

    private fun getRecordTimestamp(obj: JSONObject): Long {
        val ts = obj.optLong("timestamp", 0L)
        if (ts > 0L) return ts
        val idTs = obj.optString("id", "").toLongOrNull()
        if (idTs != null && idTs > 1_000_000_000_000L) return idTs
        val dateStr = obj.optString("date", "")
        return try {
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val parsed = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.KOREA).parse("$year/$dateStr")
            parsed?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun isWithinPeriod(obj: JSONObject): Boolean {
        val start = filterStartMillis
        val end = filterEndMillis
        if (start == null && end == null) return true
        val ts = getRecordTimestamp(obj)
        if (ts <= 0L) return true
        if (start != null && ts < start) return false
        if (end != null && ts > end) return false
        return true
    }

    private fun getPeriodDisplayLabel(): String {
        val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
        val s = filterStartMillis
        val e = filterEndMillis
        return when {
            s == null && e == null -> "전체 기간"
            s != null && e != null -> "${fmt.format(Date(s))} ~ ${fmt.format(Date(e))}"
            s != null -> "${fmt.format(Date(s))} ~ 현재"
            else -> "처음 ~ ${fmt.format(Date(e!!))}"
        }
    }

    private fun startOfDay(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun endOfDay(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(year, month, day, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    private fun getMaxDayOfMonth(year: Int, month: Int): Int {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    private fun isEndOfMonthToken(day: Int): Boolean = day <= 0 || day >= 31

    private fun resolveBoundDay(year: Int, month: Int, rawDay: Int, isEnd: Boolean): Int {
        val maxDay = getMaxDayOfMonth(year, month)
        if (isEndOfMonthToken(rawDay)) return maxDay
        if (!isEnd && rawDay <= 0) return 1
        return rawDay.coerceIn(1, maxDay)
    }

    private fun parseStartDayInput(raw: String): Int {
        val trimmed = raw.trim()
        if (trimmed.contains("말일") || trimmed == "0") return 0
        val num = trimmed.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
        return if (num >= 31) 0 else num.coerceIn(1, 30)
    }

    private fun parseEndDayInput(raw: String): Int {
        val trimmed = raw.trim()
        if (trimmed.contains("말일") || trimmed == "0") return 0
        val num = trimmed.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        return if (num >= 31) 0 else num.coerceIn(1, 30)
    }

    private fun formatBoundDayShort(day: Int): String {
        return if (isEndOfMonthToken(day)) "말일" else "${day.coerceIn(1, 30)}일"
    }

    private fun formatPayRangeLabel(startDay: Int, endDay: Int): String {
        val sIsEnd = isEndOfMonthToken(startDay)
        val eIsEnd = isEndOfMonthToken(endDay)
        if (sIsEnd && eIsEnd) return "매월 말일"
        if (sIsEnd) return "매월 말일 ~ ${formatBoundDayShort(endDay)}"
        if (eIsEnd) return "매월 ${formatBoundDayShort(startDay)} ~ 말일"
        val sLabel = formatBoundDayShort(startDay)
        val eLabel = formatBoundDayShort(endDay)
        return if (sLabel == eLabel) "매월 $sLabel" else "매월 $sLabel ~ $eLabel"
    }

    private fun buildPeriodFilterCard(isDark: Boolean, compact: Boolean = false): LinearLayout {
        val bgCard = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val chipBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        val textPrimary = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#98989D") else Color.parseColor("#6B7280")
        val activeColor = Color.parseColor("#B93844")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 14)
            setPadding(dp(if (compact) 8 else 10), dp(8), dp(if (compact) 8 else 10), dp(8))
        }

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makePresetBtn(label: String, isActive: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = sp(if (compact) 11f else 12f)
                gravity = Gravity.CENTER
                setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isActive) Color.WHITE else textPrimary)
                background = roundBg(if (isActive) activeColor else chipBg, 14)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(4)
                }
                setOnClickListener { onClick() }
            }
        }

        val isAllTime = (filterStartMillis == null && filterEndMillis == null)
        presetRow.addView(makePresetBtn("전체", isAllTime) {
            filterStartMillis = null
            filterEndMillis = null
            lastAllScrollY = 0
            lastAnalysisRightScrollY = 0
            refreshUI()
        })

        presetRow.addView(makePresetBtn("오늘", false) {
            val cal = Calendar.getInstance()
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH)
            val d = cal.get(Calendar.DAY_OF_MONTH)
            filterStartMillis = startOfDay(y, m, d)
            filterEndMillis = endOfDay(y, m, d)
            lastAllScrollY = 0
            lastAnalysisRightScrollY = 0
            refreshUI()
        })

        presetRow.addView(makePresetBtn("이번 달", false) {
            val cal = Calendar.getInstance()
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH)
            val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            filterStartMillis = startOfDay(y, m, 1)
            filterEndMillis = endOfDay(y, m, lastDay)
            lastAllScrollY = 0
            lastAnalysisRightScrollY = 0
            refreshUI()
        })

        presetRow.addView(makePresetBtn("최근 7일", false) {
            val endCal = Calendar.getInstance()
            val startCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -6) }
            filterStartMillis = startOfDay(startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH))
            filterEndMillis = endOfDay(endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH))
            lastAllScrollY = 0
            lastAnalysisRightScrollY = 0
            refreshUI()
        })
        card.addView(presetRow)

        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }

        val btnFmt = SimpleDateFormat("yy.MM.dd", Locale.KOREA)
        val startLabel = if (filterStartMillis != null) btnFmt.format(Date(filterStartMillis!!)) else "시작일"
        val endLabel = if (filterEndMillis != null) btnFmt.format(Date(filterEndMillis!!)) else "종료일"

        val startBtn = TextView(this).apply {
            text = startLabel
            textSize = sp(if (compact) 11f else 12f)
            gravity = Gravity.CENTER
            setTextColor(if (filterStartMillis != null) textPrimary else textSecondary)
            setTypeface(null, if (filterStartMillis != null) Typeface.BOLD else Typeface.NORMAL)
            background = roundBg(chipBg, 14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val initCal = Calendar.getInstance().apply {
                    if (filterStartMillis != null) timeInMillis = filterStartMillis!!
                }
                DatePickerDialog(
                    this@MainActivity,
                    getDialogTheme(isDark),
                    { _, y, m, d ->
                        val chosenStart = startOfDay(y, m, d)
                        filterStartMillis = chosenStart
                        if (filterEndMillis != null && filterEndMillis!! < chosenStart) {
                            filterEndMillis = endOfDay(y, m, d)
                        }
                        refreshUI()
                    },
                    initCal.get(Calendar.YEAR),
                    initCal.get(Calendar.MONTH),
                    initCal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }

        val tildeView = TextView(this).apply {
            text = "~"
            textSize = sp(13f)
            setTextColor(textSecondary)
            setPadding(dp(6), 0, dp(6), 0)
        }

        val endBtn = TextView(this).apply {
            text = endLabel
            textSize = sp(if (compact) 11f else 12f)
            gravity = Gravity.CENTER
            setTextColor(if (filterEndMillis != null) textPrimary else textSecondary)
            setTypeface(null, if (filterEndMillis != null) Typeface.BOLD else Typeface.NORMAL)
            background = roundBg(chipBg, 14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val initCal = Calendar.getInstance().apply {
                    if (filterEndMillis != null) timeInMillis = filterEndMillis!!
                }
                DatePickerDialog(
                    this@MainActivity,
                    getDialogTheme(isDark),
                    { _, y, m, d ->
                        val chosenEnd = endOfDay(y, m, d)
                        filterEndMillis = chosenEnd
                        if (filterStartMillis != null && filterStartMillis!! > chosenEnd) {
                            filterStartMillis = startOfDay(y, m, d)
                        }
                        refreshUI()
                    },
                    initCal.get(Calendar.YEAR),
                    initCal.get(Calendar.MONTH),
                    initCal.get(Calendar.DAY_OF_MONTH)
                ).show()
            }
        }

        customRow.addView(startBtn)
        customRow.addView(tildeView)
        customRow.addView(endBtn)
        card.addView(customRow)

        return card
    }

    private fun renderAllTab(isDark: Boolean, container: LinearLayout) {
        val bgCard = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val bgPill = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        val textPrimary = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#98989D") else Color.parseColor("#6B7280")
        val incColor = if (isDark) Color.parseColor("#6495ED") else Color.parseColor("#2563EB")
        val expColor = if (isDark) Color.parseColor("#E56B6F") else Color.parseColor("#C83E4D")
        val trnColor = if (isDark) Color.parseColor("#A78BFA") else Color.parseColor("#7C3AED")

        val periodCard = buildPeriodFilterCard(isDark, compact = false).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        container.addView(periodCard)

        val allRecords = LedgerStorage.getRecords(this)
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        val queryLower = currentQuery.lowercase(Locale.KOREA)

        var totalInc = 0L
        var totalExp = 0L
        var totalTrn = 0L
        val filteredList = mutableListOf<JSONObject>()

        for (i in 0 until allRecords.length()) {
            val obj = allRecords.getJSONObject(i)
            if (!isWithinPeriod(obj)) continue

            val type = obj.optString("type", "exp")
            val category = obj.optString("category", "기타")
            val merchant = obj.optString("merchant", obj.optString("desc", ""))
            val fromAcc = obj.optString("fromAccount", "")
            val toAcc = obj.optString("toAccount", "")
            val item = obj.optString("item", "")
            val amount = obj.optLong("amount", 0L)
            val date = obj.optString("date", "")
            val raw = obj.optString("raw", "")

            if (queryLower.isNotEmpty()) {
                val searchable = "$merchant $fromAcc $toAcc $item $category $raw $date".lowercase(Locale.KOREA)
                if (!searchable.contains(queryLower)) continue
            }

            filteredList.add(obj)
            when (type) {
                "inc" -> totalInc += amount
                "trn" -> totalTrn += amount
                else -> totalExp += amount
            }
        }

        val summaryTitle = "${getPeriodDisplayLabel()} 순수익 (${filteredList.size}건)"
        container.addView(
            buildSummaryCard(
                title = summaryTitle,
                totalInc = totalInc,
                totalExp = totalExp,
                totalTrn = totalTrn,
                bgCard = bgCard,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                incColor = incColor,
                expColor = expColor,
                trnColor = trnColor,
                compact = false
            )
        )

        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val isSearchingOrOpen = isSearchExpanded || currentQuery.isNotEmpty()
        val searchToggleBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundBg(
                if (isSearchingOrOpen) Color.parseColor("#B93844") else bgCard,
                18
            )
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
            setOnClickListener {
                isSearchExpanded = !isSearchExpanded
                refreshUI()
            }
        }

        searchToggleBtn.addView(TextView(this).apply {
            text = if (currentQuery.isNotEmpty()) "🔍 $currentQuery" else if (isSearchExpanded) "검색 닫기" else "🔍 검색"
            textSize = sp(13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isSearchingOrOpen) Color.WHITE else textPrimary)
            isSingleLine = true
        })

        if (currentQuery.isNotEmpty()) {
            searchToggleBtn.addView(TextView(this).apply {
                text = "  ✕"
                textSize = sp(13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    currentQuery = ""
                    isSearchExpanded = false
                    refreshUI()
                }
            })
        }
        actionBar.addView(searchToggleBtn)

        val addModalBtn = TextView(this).apply {
            text = "+ 내역 추가"
            textSize = sp(13f)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            background = roundBg(bgCard, 18)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                showManualAddDialog()
            }
        }
        actionBar.addView(addModalBtn)
        container.addView(actionBar)

        if (isSearchExpanded || currentQuery.isNotEmpty()) {
            val searchBoxCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(bgCard, 14)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val searchInput = EditText(this).apply {
                hint = "결제처, 통장명, 품목 검색..."
                setText(currentQuery)
                textSize = sp(13f)
                isSingleLine = true
                styleDialogEditText(this, isDark)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val goBtn = TextView(this).apply {
                text = "검색"
                textSize = sp(13f)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = roundBg(Color.parseColor("#B93844"), 16)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) }
                setOnClickListener {
                    val q = searchInput.text.toString().trim()
                    currentQuery = q
                    if (q.isNotEmpty()) {
                        LedgerStorage.addSearchHistory(this@MainActivity, q)
                    }
                    refreshUI()
                }
            }
            row.addView(searchInput)
            row.addView(goBtn)
            searchBoxCard.addView(row)

            val history = LedgerStorage.getSearchHistory(this)
            if (history.isNotEmpty()) {
                val historyScroll = HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                }
                val historyRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                historyRow.addView(TextView(this).apply {
                    text = "최근:"
                    setTextColor(textSecondary)
                    textSize = sp(11f)
                    setPadding(0, 0, dp(6), 0)
                })
                for (word in history) {
                    historyRow.addView(TextView(this).apply {
                        text = word
                        setTextColor(textPrimary)
                        textSize = sp(11f)
                        background = roundBg(bgPill, 12)
                        setPadding(dp(9), dp(3), dp(9), dp(3))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { rightMargin = dp(5) }
                        setOnClickListener {
                            currentQuery = word
                            refreshUI()
                        }
                    })
                }
                historyRow.addView(TextView(this).apply {
                    text = "삭제"
                    setTextColor(expColor)
                    textSize = sp(11f)
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                    setOnClickListener {
                        LedgerStorage.clearSearchHistory(this@MainActivity)
                        refreshUI()
                    }
                })
                historyScroll.addView(historyRow)
                searchBoxCard.addView(historyScroll)
            }
            container.addView(searchBoxCard)
        }

        container.addView(TextView(this).apply {
            text = "내역 ${filteredList.size}건 (눌러서 상세 · 수정)"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(12), dp(4), dp(6))
        })

        renderRecordCards(container, filteredList, bgCard, textSecondary, incColor, expColor, trnColor, nf, compact = false)
    }

    private fun renderCombinedAnalysisTab(isDark: Boolean) {
        val bgSidebar = if (isDark) Color.parseColor("#141414") else Color.parseColor("#EAEAEF")
        val bgCard = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val bgPill = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        val textPrimary = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#98989D") else Color.parseColor("#6B7280")
        val incColor = if (isDark) Color.parseColor("#6495ED") else Color.parseColor("#2563EB")
        val expColor = if (isDark) Color.parseColor("#E56B6F") else Color.parseColor("#C83E4D")
        val trnColor = if (isDark) Color.parseColor("#A78BFA") else Color.parseColor("#7C3AED")

        val splitRoot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val allRecords = LedgerStorage.getRecords(this)
        val periodRecords = mutableListOf<JSONObject>()
        val catCountInPeriod = mutableMapOf<String, Int>()
        var periodAllExp = 0L

        for (i in 0 until allRecords.length()) {
            val obj = allRecords.getJSONObject(i)
            if (!isWithinPeriod(obj)) continue
            periodRecords.add(obj)
            val cat = obj.optString("category", "기타")
            catCountInPeriod[cat] = (catCountInPeriod[cat] ?: 0) + 1
            if (obj.optString("type", "exp") == "exp") {
                periodAllExp += obj.optLong("amount", 0L)
            }
        }

        val leftScroll = ScrollView(this).apply {
            setBackgroundColor(bgSidebar)
            layoutParams = LinearLayout.LayoutParams(dp(98), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val leftList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(16))
        }

        val fixedItems = LedgerStorage.getFixedExpenses(this)
        val leftCategories = listOf("전체") + NotificationParser.CATEGORIES
        for (cat in leftCategories) {
            val isSelected = (cat == selectedAnalysisCategory)
            val cnt = if (cat == "고정 지출") {
                fixedItems.size
            } else if (cat == "전체") {
                periodRecords.size
            } else {
                catCountInPeriod[cat] ?: 0
            }

            val catItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundBg(
                    if (isSelected) Color.parseColor("#B93844") else bgCard,
                    12
                )
                setPadding(dp(3), dp(10), dp(3), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
                setOnClickListener {
                    selectedAnalysisCategory = cat
                    lastAnalysisRightScrollY = 0
                    refreshUI()
                }
            }

            catItem.addView(TextView(this).apply {
                text = cat
                textSize = sp(11.5f)
                gravity = Gravity.CENTER
                isSingleLine = true
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else textPrimary)
            })

            catItem.addView(TextView(this).apply {
                text = if (cat == "고정 지출") "${cnt}개" else "${cnt}건"
                textSize = sp(10f)
                gravity = Gravity.CENTER
                setTextColor(if (isSelected) Color.parseColor("#FDE8E8") else textSecondary)
                setPadding(0, dp(2), 0, 0)
            })

            leftList.addView(catItem)
        }
        leftScroll.addView(leftList)
        splitRoot.addView(leftScroll)

        val rightScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        activeAnalysisRightScrollView = rightScroll

        val rightContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(24))
        }

        val nf = NumberFormat.getNumberInstance(Locale.KOREA)

        if (selectedAnalysisCategory == "고정 지출") {
            renderFixedExpensePanel(
                isDark = isDark,
                rightContent = rightContent,
                bgCard = bgCard,
                bgPill = bgPill,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                expColor = expColor,
                incColor = incColor,
                nf = nf
            )
            rightScroll.addView(rightContent)
            splitRoot.addView(rightScroll)
            contentHost.addView(splitRoot)
            return
        }

        rightContent.addView(
            buildPeriodFilterCard(isDark, compact = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
        )

        var selInc = 0L
        var selExp = 0L
        var selTrn = 0L
        val targetRecords = mutableListOf<JSONObject>()
        val catAllAmtMap = mutableMapOf<String, Long>()
        val merchantAmtMap = mutableMapOf<String, Long>()

        for (obj in periodRecords) {
            val cat = obj.optString("category", "기타")
            val type = obj.optString("type", "exp")
            val amount = obj.optLong("amount", 0L)
            val merchant = obj.optString("merchant", obj.optString("desc", "기타"))

            catAllAmtMap[cat] = (catAllAmtMap[cat] ?: 0L) + amount

            if (selectedAnalysisCategory != "전체" && cat != selectedAnalysisCategory) continue

            targetRecords.add(obj)
            when (type) {
                "inc" -> {
                    selInc += amount
                    if (selectedAnalysisCategory == "수입") {
                        merchantAmtMap[merchant] = (merchantAmtMap[merchant] ?: 0L) + amount
                    }
                }
                "trn" -> {
                    selTrn += amount
                    if (selectedAnalysisCategory == "이체/계좌이동") {
                        merchantAmtMap[merchant] = (merchantAmtMap[merchant] ?: 0L) + amount
                    }
                }
                else -> {
                    selExp += amount
                    merchantAmtMap[merchant] = (merchantAmtMap[merchant] ?: 0L) + amount
                }
            }
        }

        val shareText = if (selectedAnalysisCategory != "전체" && selectedAnalysisCategory != "이체/계좌이동" && periodAllExp > 0L && selExp > 0L) {
            val sharePct = ((selExp.toDouble() / periodAllExp.toDouble()) * 100).toInt()
            " · 전체 지출의 $sharePct%"
        } else {
            ""
        }
        rightContent.addView(
            buildSummaryCard(
                title = "[$selectedAnalysisCategory] ${getPeriodDisplayLabel()}$shareText",
                totalInc = selInc,
                totalExp = selExp,
                totalTrn = selTrn,
                bgCard = bgCard,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                incColor = incColor,
                expColor = expColor,
                trnColor = trnColor,
                compact = true
            )
        )

        val pieCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 14)
            setPadding(dp(12), dp(12), dp(12), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val sliceColors = listOf(
            Color.parseColor("#C83E4D"),
            Color.parseColor("#3B82F6"),
            Color.parseColor("#10B981"),
            Color.parseColor("#F59E0B"),
            Color.parseColor("#8B5CF6"),
            Color.parseColor("#EC4899"),
            Color.parseColor("#14B8A6"),
            Color.parseColor("#F97316"),
            Color.parseColor("#6366F1")
        )

        val slices = mutableListOf<PieSlice>()

        if (selectedAnalysisCategory == "전체") {
            pieCard.addView(TextView(this).apply {
                text = "원형 그래프 (표시할 카테고리 선택)"
                setTextColor(textPrimary)
                textSize = sp(13f)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })

            val selectorScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            val selectorRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            for (cat in NotificationParser.CATEGORIES) {
                val isChecked = pieIncludedCategories.contains(cat)
                val chip = TextView(this).apply {
                    text = if (isChecked) "✓ $cat" else cat
                    textSize = sp(11f)
                    setTypeface(null, if (isChecked) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isChecked) Color.WHITE else textSecondary)
                    background = roundBg(if (isChecked) Color.parseColor("#B93844") else bgPill, 14)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(5) }
                    setOnClickListener {
                        if (pieIncludedCategories.contains(cat)) {
                            if (pieIncludedCategories.size > 1) {
                                pieIncludedCategories.remove(cat)
                            }
                        } else {
                            pieIncludedCategories.add(cat)
                        }
                        refreshUI()
                    }
                }
                selectorRow.addView(chip)
            }
            selectorScroll.addView(selectorRow)
            pieCard.addView(selectorScroll)

            val selectedEntries = catAllAmtMap.entries
                .filter { pieIncludedCategories.contains(it.key) && it.value > 0L }
                .sortedByDescending { it.value }

            selectedEntries.forEachIndexed { idx, (cat, amt) ->
                slices.add(PieSlice(cat, amt, sliceColors[idx % sliceColors.size]))
            }
        } else {
            pieCard.addView(TextView(this).apply {
                text = "[$selectedAnalysisCategory] 항목별 비중 원형 그래프"
                setTextColor(textPrimary)
                textSize = sp(13f)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            })

            val sortedMerchants = merchantAmtMap.entries
                .filter { it.value > 0L }
                .sortedByDescending { it.value }
                .take(7)

            sortedMerchants.forEachIndexed { idx, (merchant, amt) ->
                slices.add(PieSlice(merchant, amt, sliceColors[idx % sliceColors.size]))
            }
        }

        val pieTotal = slices.sumOf { it.value }

        val pieView = PieChartView(this, slices, bgCard, textPrimary, textSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(190)
            ).apply { bottomMargin = dp(8) }
        }
        pieCard.addView(pieView)

        if (pieTotal > 0L) {
            for (slice in slices) {
                val pct = (slice.value.toDouble() / pieTotal.toDouble()) * 100.0
                val pctStr = String.format(Locale.KOREA, "%.1f%%", pct)

                val legRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                }

                legRow.addView(View(this).apply {
                    background = roundBg(slice.color, 5)
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                        rightMargin = dp(8)
                    }
                })

                legRow.addView(TextView(this).apply {
                    text = "${slice.label} ($pctStr)"
                    setTextColor(textPrimary)
                    textSize = sp(12f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                legRow.addView(TextView(this).apply {
                    text = "${nf.format(slice.value)}원"
                    setTextColor(textSecondary)
                    textSize = sp(12f)
                    setTypeface(null, Typeface.BOLD)
                })

                pieCard.addView(legRow)
            }
        }
        rightContent.addView(pieCard)

        rightContent.addView(TextView(this).apply {
            text = "[$selectedAnalysisCategory] 내역 ${targetRecords.size}건"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(2), dp(12), dp(2), dp(6))
        })

        renderRecordCards(rightContent, targetRecords, bgCard, textSecondary, incColor, expColor, trnColor, nf, compact = true)

        rightScroll.addView(rightContent)
        splitRoot.addView(rightScroll)
        contentHost.addView(splitRoot)
    }

    private fun renderFixedExpensePanel(
        isDark: Boolean,
        rightContent: LinearLayout,
        bgCard: Int,
        bgPill: Int,
        textPrimary: Int,
        textSecondary: Int,
        expColor: Int,
        incColor: Int,
        nf: NumberFormat
    ) {
        val nowCal = Calendar.getInstance()
        val curYear = nowCal.get(Calendar.YEAR)
        val curMonth = nowCal.get(Calendar.MONTH)
        val curDay = nowCal.get(Calendar.DAY_OF_MONTH)
        val monthLabel = "${curYear}.${String.format(Locale.KOREA, "%02d", curMonth + 1)}"

        val fixedList = LedgerStorage.getFixedExpenses(this)
        val allRecords = LedgerStorage.getRecords(this)

        val thisMonthExpRecords = mutableListOf<Pair<JSONObject, Int>>()
        for (i in 0 until allRecords.length()) {
            val r = allRecords.getJSONObject(i)
            if (r.optString("type", "exp") != "exp") continue
            val ts = getRecordTimestamp(r)
            if (ts <= 0L) continue
            val c = Calendar.getInstance().apply { timeInMillis = ts }
            if (c.get(Calendar.YEAR) == curYear && c.get(Calendar.MONTH) == curMonth) {
                thisMonthExpRecords.add(r to c.get(Calendar.DAY_OF_MONTH))
            }
        }

        var plannedTotal = 0L
        var doneTotal = 0L
        var remainingTotal = 0L

        val statusPairs = fixedList.map { fe ->
            val startD = resolveBoundDay(curYear, curMonth, fe.startDay, isEnd = false)
            val endD = resolveBoundDay(curYear, curMonth, fe.endDay, isEnd = true).coerceAtLeast(startD)
            val kw = fe.keyword.ifEmpty { fe.title }.lowercase(Locale.KOREA)
            val prefix = fe.title.take(2).lowercase(Locale.KOREA)

            val matchedEntry = thisMonthExpRecords.firstOrNull { (r, rDay) ->
                val m = r.optString("merchant", "").lowercase(Locale.KOREA)
                val itm = r.optString("item", "").lowercase(Locale.KOREA)
                val amt = r.optLong("amount", 0L)
                val inRange = rDay in startD..endD
                val nameMatched = (kw.isNotEmpty() && (m.contains(kw) || itm.contains(kw))) ||
                    (kotlin.math.abs(amt - fe.amount) <= 1000L && prefix.isNotEmpty() && m.contains(prefix))
                inRange && nameMatched
            }

            val isDone = matchedEntry != null
            val effectiveAmt = matchedEntry?.first?.optLong("amount", fe.amount) ?: fe.amount
            plannedTotal += fe.amount
            if (isDone) {
                doneTotal += effectiveAmt
            } else {
                remainingTotal += fe.amount
            }
            Triple(fe, isDone, effectiveAmt)
        }

        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 14)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        summaryCard.addView(TextView(this).apply {
            text = "$monthLabel 고정 지출 예정 합계"
            setTextColor(textSecondary)
            textSize = sp(11f)
            gravity = Gravity.CENTER
        })
        summaryCard.addView(TextView(this).apply {
            text = "${nf.format(plannedTotal)}원"
            setTextColor(textPrimary)
            textSize = sp(22f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        })

        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        subRow.addView(TextView(this).apply {
            text = "완료 ${nf.format(doneTotal)}원"
            setTextColor(incColor)
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        })
        subRow.addView(TextView(this).apply {
            text = "남은 예정 ${nf.format(remainingTotal)}원"
            setTextColor(expColor)
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        })
        summaryCard.addView(subRow)

        summaryCard.addView(TextView(this).apply {
            text = "항목별 지정 기간(시작일 ~ 종료일) 내 결제를 자동 확인합니다."
            setTextColor(textSecondary)
            textSize = sp(10.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        })
        rightContent.addView(summaryCard)

        val topBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        topBtnRow.addView(makePillButton(isDark, "+ 고정비 추가", isActive = true) {
            showAddFixedExpenseDialog()
        })
        rightContent.addView(topBtnRow)

        if (statusPairs.isEmpty()) {
            rightContent.addView(TextView(this).apply {
                text = "등록된 고정 지출이 없습니다.\n'+ 고정비 추가'로 월세, 관리비, 구독료 등을 등록해 보세요."
                setTextColor(textSecondary)
                textSize = sp(12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }

        for ((fe, isDone, effectiveAmt) in statusPairs) {
            val startD = resolveBoundDay(curYear, curMonth, fe.startDay, isEnd = false)
            val endD = resolveBoundDay(curYear, curMonth, fe.endDay, isEnd = true).coerceAtLeast(startD)
            val dDiff = startD - curDay

            val dDayLabel = when {
                isDone -> "✓ 완료"
                curDay in startD..endD -> "결제 기간 중"
                dDiff > 0 -> "예정 (D-$dDiff)"
                else -> "기간 지남 (${curDay - endD}일)"
            }
            val badgeColor = when {
                isDone -> incColor
                curDay in startD..endD || dDiff in 0..2 -> expColor
                else -> textSecondary
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(bgCard, 12)
                setPadding(dp(11), dp(10), dp(11), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) }
            }

            val topLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val rangeLabel = formatPayRangeLabel(fe.startDay, fe.endDay)
            val hasMonthEnd = isEndOfMonthToken(fe.startDay) || isEndOfMonthToken(fe.endDay)
            val resolvedRangeHint = if (hasMonthEnd) " (${startD}~${endD}일)" else ""

            infoCol.addView(TextView(this).apply {
                text = "[$dDayLabel] · $rangeLabel$resolvedRangeHint"
                setTextColor(badgeColor)
                textSize = sp(10.5f)
                setTypeface(null, Typeface.BOLD)
            })

            infoCol.addView(TextView(this).apply {
                text = fe.title
                setTextColor(textPrimary)
                textSize = sp(14f)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            })

            val subMeta = if (fe.keyword.isNotEmpty() && fe.keyword != fe.title) {
                "매칭어: ${fe.keyword}"
            } else {
                "예상 고정비"
            }
            infoCol.addView(TextView(this).apply {
                text = subMeta
                setTextColor(textSecondary)
                textSize = sp(10.5f)
            })
            topLine.addView(infoCol)

            topLine.addView(TextView(this).apply {
                text = "${nf.format(effectiveAmt)}원"
                setTextColor(if (isDone) incColor else expColor)
                textSize = sp(14f)
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(topLine)

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }

            if (!isDone) {
                val recordNowBtn = TextView(this).apply {
                    text = "장부에 기록"
                    textSize = sp(11f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = roundBg(Color.parseColor("#B93844"), 12)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { rightMargin = dp(6) }
                    setOnClickListener {
                        val targetDay = if (curDay in startD..endD) curDay else startD
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.YEAR, curYear)
                            set(Calendar.MONTH, curMonth)
                            set(Calendar.DAY_OF_MONTH, targetDay)
                        }
                        val ts = cal.timeInMillis
                        val dStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(ts))
                        LedgerStorage.addManualRecordWithDate(
                            context = this@MainActivity,
                            type = "exp",
                            category = "고정 지출",
                            merchant = fe.title,
                            fromAccount = "",
                            toAccount = "",
                            item = "• ${fe.title} (1건) : ${nf.format(fe.amount)}원",
                            quantity = "1건",
                            amount = fe.amount,
                            dateStr = dStr,
                            timestamp = ts
                        )
                        refreshUI()
                        Toast.makeText(this@MainActivity, "\"${fe.title}\" 지출이 장부에 기록되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
                btnRow.addView(recordNowBtn)
            }

            val editFixedBtn = TextView(this).apply {
                text = "수정"
                textSize = sp(11f)
                setTextColor(textPrimary)
                background = roundBg(bgPill, 12)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
                setOnClickListener {
                    showEditFixedExpenseDialog(fe)
                }
            }
            btnRow.addView(editFixedBtn)

            val delFixedBtn = TextView(this).apply {
                text = "삭제"
                textSize = sp(11f)
                setTextColor(textSecondary)
                background = roundBg(bgPill, 12)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener {
                    LedgerStorage.removeFixedExpense(this@MainActivity, fe.id)
                    refreshUI()
                }
            }
            btnRow.addView(delFixedBtn)
            card.addView(btnRow)

            rightContent.addView(card)
        }
    }

    private fun showAddFixedExpenseDialog() {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        fun addField(label: String, hintStr: String, isNumber: Boolean = false): EditText {
            box.addView(TextView(this).apply {
                text = label
                textSize = sp(12f)
                setTextColor(labelColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(3))
            })
            return EditText(this).apply {
                hint = hintStr
                textSize = sp(14f)
                if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER
                styleDialogEditText(this, isDark)
                box.addView(this)
            }
        }

        val titleEdit = addField("고정 지출 이름", "예: 월세, 관리비, 통신비, 유튜브")

        box.addView(TextView(this).apply {
            text = "결제 기간 (시작일 ~ 종료일 · 말일은 0 또는 말일)"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(3))
        })

        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val startDayEdit = EditText(this).apply {
            hint = "시작일 (예: 20, 말일)"
            textSize = sp(14f)
            inputType = InputType.TYPE_CLASS_TEXT
            styleDialogEditText(this, isDark)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tildeView = TextView(this).apply {
            text = "~"
            textSize = sp(16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(labelColor)
            setPadding(dp(8), 0, dp(8), 0)
        }

        val endDayEdit = EditText(this).apply {
            hint = "종료일 (예: 25, 말일)"
            textSize = sp(14f)
            inputType = InputType.TYPE_CLASS_TEXT
            styleDialogEditText(this, isDark)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        rangeRow.addView(startDayEdit)
        rangeRow.addView(tildeView)
        rangeRow.addView(endDayEdit)
        box.addView(rangeRow)

        val amtEdit = addField("예상 금액 (원)", "숫자만 입력", isNumber = true)
        val kwEdit = addField("알림/메일 자동 매칭 단어 (선택)", "비워두면 이름 기준으로 자동 확인")

        scroll.addView(box)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("고정 지출 추가")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val title = titleEdit.text.toString().trim()
                val startRaw = startDayEdit.text.toString().trim()
                val endRaw = endDayEdit.text.toString().trim()
                val startD = when {
                    startRaw.isEmpty() && endRaw.isEmpty() -> 1
                    startRaw.isEmpty() -> parseEndDayInput(endRaw)
                    else -> parseStartDayInput(startRaw)
                }
                val endD = when {
                    endRaw.isEmpty() && isEndOfMonthToken(startD) -> 0
                    endRaw.isEmpty() -> startD
                    else -> parseEndDayInput(endRaw)
                }
                val amt = amtEdit.text.toString().trim().toLongOrNull() ?: 0L
                val kw = kwEdit.text.toString().trim().ifEmpty { title }

                if (title.isNotEmpty() && amt > 0L) {
                    LedgerStorage.addFixedExpense(this, title, startD, endD, amt, kw)
                    refreshUI()
                } else {
                    Toast.makeText(this, "항목 이름과 금액을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditFixedExpenseDialog(item: FixedExpenseItem) {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        fun addField(label: String, initialValue: String, hintStr: String, isNumber: Boolean = false): EditText {
            box.addView(TextView(this).apply {
                text = label
                textSize = sp(12f)
                setTextColor(labelColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(3))
            })
            return EditText(this).apply {
                setText(initialValue)
                hint = hintStr
                textSize = sp(14f)
                if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER
                styleDialogEditText(this, isDark)
                box.addView(this)
            }
        }

        val titleEdit = addField("고정 지출 이름", item.title, "예: 월세, 관리비, 통신비, 유튜브")

        box.addView(TextView(this).apply {
            text = "결제 기간 (시작일 ~ 종료일 · 말일은 0 또는 말일)"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(3))
        })

        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val startInitial = if (isEndOfMonthToken(item.startDay)) "말일" else item.startDay.toString()
        val startDayEdit = EditText(this).apply {
            setText(startInitial)
            hint = "시작일 (예: 20, 말일)"
            textSize = sp(14f)
            inputType = InputType.TYPE_CLASS_TEXT
            styleDialogEditText(this, isDark)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tildeView = TextView(this).apply {
            text = "~"
            textSize = sp(16f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(labelColor)
            setPadding(dp(8), 0, dp(8), 0)
        }

        val endInitial = if (isEndOfMonthToken(item.endDay)) "말일" else item.endDay.toString()
        val endDayEdit = EditText(this).apply {
            setText(endInitial)
            hint = "종료일 (예: 25, 말일)"
            textSize = sp(14f)
            inputType = InputType.TYPE_CLASS_TEXT
            styleDialogEditText(this, isDark)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        rangeRow.addView(startDayEdit)
        rangeRow.addView(tildeView)
        rangeRow.addView(endDayEdit)
        box.addView(rangeRow)

        val amtEdit = addField("예상 금액 (원)", item.amount.toString(), "숫자만 입력", isNumber = true)
        val kwEdit = addField("알림/메일 자동 매칭 단어 (선택)", item.keyword, "비워두면 이름 기준으로 자동 확인")

        scroll.addView(box)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("고정 지출 수정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val title = titleEdit.text.toString().trim()
                val startRaw = startDayEdit.text.toString().trim()
                val endRaw = endDayEdit.text.toString().trim()
                val startD = when {
                    startRaw.isEmpty() && endRaw.isEmpty() -> 1
                    startRaw.isEmpty() -> parseEndDayInput(endRaw)
                    else -> parseStartDayInput(startRaw)
                }
                val endD = when {
                    endRaw.isEmpty() && isEndOfMonthToken(startD) -> 0
                    endRaw.isEmpty() -> startD
                    else -> parseEndDayInput(endRaw)
                }
                val amt = amtEdit.text.toString().trim().toLongOrNull() ?: 0L
                val kw = kwEdit.text.toString().trim().ifEmpty { title }

                if (title.isNotEmpty() && amt > 0L) {
                    LedgerStorage.updateFixedExpense(this, item.id, title, startD, endD, amt, kw)
                    refreshUI()
                } else {
                    Toast.makeText(this, "항목 이름과 금액을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun renderSettingsTab(isDark: Boolean, container: LinearLayout) {
        val bgCard = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val bgPill = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        val textPrimary = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#98989D") else Color.parseColor("#6B7280")

        val groupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 18)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val granted = isNotificationGranted()
        val rowNotif = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val notifTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }
        notifTextCol.addView(TextView(this).apply {
            text = "🔔  결제 알림 자동 감지"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
        })
        notifTextCol.addView(TextView(this).apply {
            text = if (granted) "앱 종료 후에도 백그라운드에서 자동 감지 중" else "꺼짐 (스위치를 눌러 권한 허용)"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setPadding(0, dp(3), 0, 0)
        })
        rowNotif.addView(notifTextCol)
        rowNotif.addView(makeToggleSwitch(isDark, granted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        groupCard.addView(rowNotif)
        groupCard.addView(makeDivider(isDark))

        val savedEmail = LedgerStorage.getEmailAddress(this)
        val savedProv = if (LedgerStorage.getEmailProvider(this) == "naver") "네이버 메일" else "Gmail"
        val emailPeriodLabel = LedgerStorage.getEmailPeriodLabel(this)

        val rowEmail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val emailTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }
        emailTextCol.addView(TextView(this).apply {
            text = "✉️  이메일 영수증 직접 연동"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
        })
        emailTextCol.addView(TextView(this).apply {
            text = if (savedEmail.isNotEmpty()) {
                "● 연결됨: $savedProv ($savedEmail)\n조회 기간: $emailPeriodLabel"
            } else {
                "연결된 이메일 없음"
            }
            setTextColor(if (savedEmail.isNotEmpty()) Color.parseColor("#34D399") else textSecondary)
            textSize = sp(12f)
            setPadding(0, dp(3), 0, 0)
        })
        rowEmail.addView(emailTextCol)
        rowEmail.addView(makePillButton(isDark, if (savedEmail.isNotEmpty()) "연동 설정" else "이메일 연결") {
            showEmailConfigDialog()
        })
        groupCard.addView(rowEmail)
        groupCard.addView(makeDivider(isDark))

        val rulesHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val rulesTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        }
        rulesTitleCol.addView(TextView(this).apply {
            text = "🏷️  단어 자동 분류 · 삭제 규칙"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
        })
        rulesTitleCol.addView(TextView(this).apply {
            text = "특정 단어가 포함된 알림을 자동 삭제하거나 이체·카테고리로 분류"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setPadding(0, dp(3), 0, 0)
        })
        rulesHeaderRow.addView(rulesTitleCol)
        rulesHeaderRow.addView(makePillButton(isDark, "+ 규칙 추가") {
            showAddRuleDialog()
        })
        groupCard.addView(rulesHeaderRow)

        val rules = LedgerStorage.getAutoRules(this)
        if (rules.isNotEmpty()) {
            val rulesBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            for (rule in rules) {
                val badgeLabel = when (rule.action) {
                    "delete" -> "자동삭제"
                    "transfer" -> "⇄ 이체"
                    else -> rule.category
                }
                val badgeColor = when (rule.action) {
                    "delete" -> Color.parseColor("#E56B6F")
                    "transfer" -> Color.parseColor("#A78BFA")
                    else -> Color.parseColor("#34D399")
                }

                val rRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundBg(bgPill, 12)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                }

                rRow.addView(TextView(this).apply {
                    text = "[$badgeLabel]"
                    setTextColor(badgeColor)
                    textSize = sp(11f)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, dp(8), 0)
                })

                rRow.addView(TextView(this).apply {
                    text = "\"${rule.keyword}\""
                    setTextColor(textPrimary)
                    textSize = sp(13f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                rRow.addView(TextView(this).apply {
                    text = "삭제"
                    setTextColor(textSecondary)
                    textSize = sp(11f)
                    setPadding(dp(8), dp(2), dp(2), dp(2))
                    setOnClickListener {
                        LedgerStorage.removeAutoRule(this@MainActivity, rule.id)
                        refreshUI()
                    }
                })
                rulesBox.addView(rRow)
            }

            val applyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(4), 0, 0)
            }
            applyRow.addView(makePillButton(isDark, "기존 내역에도 규칙 적용") {
                val changed = LedgerStorage.applyAutoRulesToExistingRecords(this)
                refreshUI()
                Toast.makeText(this, "기존 내역 ${changed}건에 규칙이 반영되었습니다.", Toast.LENGTH_SHORT).show()
            })
            rulesBox.addView(applyRow)
            groupCard.addView(rulesBox)
        }
        groupCard.addView(makeDivider(isDark))

        val rowDark = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rowDark.addView(TextView(this).apply {
            text = "🌙  다크 모드"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        rowDark.addView(makeToggleSwitch(isDark, isDark) {
            LedgerStorage.setDarkMode(this, !isDark)
            recreate()
        })
        groupCard.addView(rowDark)
        groupCard.addView(makeDivider(isDark))

        groupCard.addView(TextView(this).apply {
            text = "A   글자 크기"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })

        val scaleOptions = listOf(
            "작게" to 0.85f,
            "보통" to 1.0f,
            "크게" to 1.15f,
            "아주 크게" to 1.30f
        )
        val currentScale = LedgerStorage.getFontScale(this)
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        for ((label, scaleVal) in scaleOptions) {
            val isSelected = kotlin.math.abs(currentScale - scaleVal) < 0.02f
            val btn = makePillButton(isDark, label, isSelected) {
                LedgerStorage.setFontScale(this@MainActivity, scaleVal)
                refreshUI()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(6)
                }
            }
            scaleRow.addView(btn)
        }
        groupCard.addView(scaleRow)

        container.addView(groupCard)
    }

    private fun showAddRuleDialog() {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        box.addView(TextView(this).apply {
            text = "인식할 특정 단어"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        val keywordEdit = EditText(this).apply {
            hint = "예: 토뱅, 스타벅스, 광고, 캐시백"
            textSize = sp(14f)
            styleDialogEditText(this, isDark)
        }
        box.addView(keywordEdit)

        box.addView(TextView(this).apply {
            text = "해당 단어가 포함될 때 실행할 동작"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        val actionSpinner = makeThemedSpinner(
            isDark,
            listOf(
                "🗑️ 자동 삭제 (가계부에 기록 안 함)",
                "⇄ 내 통장간 이동 (이체로 자동 분류)",
                "🏷️ 특정 카테고리로 자동 분류"
            ),
            2
        )
        box.addView(actionSpinner)

        val catLabel = TextView(this).apply {
            text = "지정할 카테고리"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        val catSpinner = makeThemedSpinner(isDark, NotificationParser.CATEGORIES, 0)
        box.addView(catLabel)
        box.addView(catSpinner)

        actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val showCat = (position == 2)
                catLabel.visibility = if (showCat) View.VISIBLE else View.GONE
                catSpinner.visibility = if (showCat) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        scroll.addView(box)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("단어 자동 인식 규칙 추가")
            .setView(scroll)
            .setPositiveButton("규칙 저장") { _, _ ->
                val kw = keywordEdit.text.toString().trim()
                if (kw.isEmpty()) {
                    Toast.makeText(this, "인식할 단어를 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val action = when (actionSpinner.selectedItemPosition) {
                    0 -> "delete"
                    1 -> "transfer"
                    else -> "category"
                }
                val targetCat = if (action == "transfer") "이체/계좌이동" else catSpinner.selectedItem.toString()
                LedgerStorage.addAutoRule(this, kw, action, targetCat)
                refreshUI()
                Toast.makeText(this, "\"$kw\" 규칙이 추가되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun buildSummaryCard(
        title: String,
        totalInc: Long,
        totalExp: Long,
        totalTrn: Long,
        bgCard: Int,
        textPrimary: Int,
        textSecondary: Int,
        incColor: Int,
        expColor: Int,
        trnColor: Int,
        compact: Boolean
    ): LinearLayout {
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 14)
            setPadding(dp(if (compact) 10 else 16), dp(14), dp(if (compact) 10 else 16), dp(14))
            gravity = Gravity.CENTER

            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(textSecondary)
                textSize = sp(if (compact) 11f else 12f)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "${nf.format(totalInc - totalExp)}원"
                setTextColor(textPrimary)
                textSize = sp(if (compact) 21f else 25f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(6))
            })

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.addView(TextView(this@MainActivity).apply {
                text = "+${nf.format(totalInc)}원"
                setTextColor(incColor)
                textSize = sp(if (compact) 12f else 14f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), 0, dp(8), 0)
            })
            row.addView(TextView(this@MainActivity).apply {
                text = "-${nf.format(totalExp)}원"
                setTextColor(expColor)
                textSize = sp(if (compact) 12f else 14f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), 0, dp(8), 0)
            })
            if (totalTrn > 0L) {
                row.addView(TextView(this@MainActivity).apply {
                    text = "⇄${nf.format(totalTrn)}원"
                    setTextColor(trnColor)
                    textSize = sp(if (compact) 12f else 14f)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(8), 0, dp(8), 0)
                })
            }
            addView(row)
        }
    }

    private fun renderRecordCards(
        targetContainer: LinearLayout,
        records: List<JSONObject>,
        bgCard: Int,
        textSecondary: Int,
        incColor: Int,
        expColor: Int,
        trnColor: Int,
        nf: NumberFormat,
        compact: Boolean
    ) {
        if (records.isEmpty()) {
            targetContainer.addView(TextView(this).apply {
                text = "해당 조건의 내역이 없습니다."
                setTextColor(textSecondary)
                textSize = sp(13f)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }

        for (obj in records) {
            val type = obj.optString("type", "exp")
            val category = obj.optString("category", "기타")
            val merchant = obj.optString("merchant", obj.optString("desc", ""))
            val fromAcc = obj.optString("fromAccount", "")
            val toAcc = obj.optString("toAccount", "")
            val balanceInfo = obj.optString("balanceInfo", "")
            val item = obj.optString("item", "")
            val quantity = obj.optString("quantity", "")
            val amount = obj.optLong("amount", 0L)
            val date = obj.optString("date", "")
            val imagePath = obj.optString("imagePath", "")
            val isAuto = obj.optBoolean("auto", false)

            val sign = when (type) {
                "inc" -> "+"
                "trn" -> "⇄ "
                else -> "-"
            }
            val entryColor = when (type) {
                "inc" -> incColor
                "trn" -> trnColor
                else -> expColor
            }

            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(bgCard, 12)
                setPadding(dp(if (compact) 10 else 14), dp(11), dp(if (compact) 10 else 14), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) }
                setOnClickListener { showEditDialog(obj) }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val topMeta = buildString {
                append("[$category]")
                if (quantity.isNotEmpty()) append(" · $quantity")
                if (isAuto) append(" · 자동")
                if (imagePath.isNotEmpty() && File(imagePath).exists()) append(" · 첨부")
            }
            leftCol.addView(TextView(this).apply {
                text = topMeta
                setTextColor(textSecondary)
                textSize = sp(10f)
                setTypeface(null, Typeface.BOLD)
            })

            leftCol.addView(TextView(this).apply {
                text = merchant
                setTextColor(entryColor)
                textSize = sp(if (compact) 14f else 15f)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(2))
            })

            val subLine = buildString {
                append(date)
                if (type == "trn" && (fromAcc.isNotEmpty() || toAcc.isNotEmpty())) {
                    append(" · 출금:${fromAcc.ifEmpty { "-" }} → 입금:${toAcc.ifEmpty { "-" }}")
                } else if (fromAcc.isNotEmpty()) {
                    append(" · $fromAcc")
                }
                if (balanceInfo.isNotEmpty()) {
                    append(" ($balanceInfo)")
                }
            }
            leftCol.addView(TextView(this).apply {
                text = subLine
                setTextColor(textSecondary)
                textSize = sp(11f)
            })
            topRow.addView(leftCol)

            val rightCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(dp(6), 0, 0, 0)
            }
            if (item.contains("\n")) {
                rightCol.addView(TextView(this).apply {
                    text = "총 결제금액"
                    setTextColor(textSecondary)
                    textSize = sp(9f)
                })
            }
            rightCol.addView(TextView(this).apply {
                text = "$sign${nf.format(amount)}원"
                setTextColor(entryColor)
                textSize = sp(if (compact) 13f else 15f)
                setTypeface(null, Typeface.BOLD)
            })
            topRow.addView(rightCol)
            itemCard.addView(topRow)

            if (item.isNotEmpty()) {
                itemCard.addView(TextView(this).apply {
                    text = item
                    setTextColor(textSecondary)
                    textSize = sp(if (compact) 11f else 12f)
                    setPadding(0, dp(6), 0, 0)
                })
            }

            targetContainer.addView(itemCard)
        }
    }

    private fun showManualAddDialog() {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        var selectedTimestamp = System.currentTimeMillis()
        var selectedDateStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(selectedTimestamp))

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val dateText = TextView(this).apply {
            text = "날짜: $selectedDateStr"
            textSize = sp(13f)
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateRow.addView(dateText)
        dateRow.addView(makePillButton(isDark, "날짜선택") {
            val cal = Calendar.getInstance().apply { timeInMillis = selectedTimestamp }
            DatePickerDialog(
                this@MainActivity,
                getDialogTheme(isDark),
                { _, y, m, d ->
                    cal.set(Calendar.YEAR, y)
                    cal.set(Calendar.MONTH, m)
                    cal.set(Calendar.DAY_OF_MONTH, d)
                    selectedTimestamp = cal.timeInMillis
                    selectedDateStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(selectedTimestamp))
                    dateText.text = "날짜: $selectedDateStr"
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        })
        container.addView(dateRow)

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        val typeSpinner = makeThemedSpinner(isDark, listOf("- (지출)", "+ (수입)", "⇄ (이체)"), 0).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }
        val catSpinner = makeThemedSpinner(isDark, NotificationParser.CATEGORIES, 0).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        row1.addView(typeSpinner)
        row1.addView(catSpinner)
        container.addView(row1)

        fun makeInput(label: String, hintStr: String, isNumber: Boolean = false, multiLine: Boolean = false): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = sp(12f)
                setTextColor(labelColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(3))
            })
            return EditText(this).apply {
                hint = hintStr
                textSize = sp(14f)
                if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER
                if (multiLine) {
                    minLines = 2
                    isSingleLine = false
                }
                styleDialogEditText(this, isDark)
                container.addView(this)
            }
        }

        val merchantInput = makeInput("제목 / 결제처", "예: 쿠팡, Google Play, 농협 → 토뱅이정현")
        val fromInput = makeInput("보낸 곳 / 결제수단 (선택)", "예: 국민 신용카드, Toss: 이정*")
        val toInput = makeInput("받는 곳 / 입금 계좌 (선택)", "예: 토뱅이정현")
        val itemInput = makeInput("상세 품목 및 개별 금액 (선택)", "예: • Google One (1개월) : 29,000원", multiLine = true)
        val qtyInput = makeInput("총 수량 (선택)", "예: 총 2개")
        val amountInput = makeInput("금액 (원)", "숫자만 입력", isNumber = true)

        scroll.addView(container)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("내역 직접 추가")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val rawMerchant = merchantInput.text.toString().trim()
                val fromAcc = fromInput.text.toString().trim()
                val toAcc = toInput.text.toString().trim()
                val merchant = rawMerchant.ifEmpty {
                    if (fromAcc.isNotEmpty() || toAcc.isNotEmpty()) "${fromAcc.ifEmpty { "출금" }} → ${toAcc.ifEmpty { "입금" }}" else ""
                }
                val amt = amountInput.text.toString().trim().toLongOrNull() ?: 0L
                if (merchant.isNotEmpty() && amt > 0) {
                    val type = when (typeSpinner.selectedItemPosition) {
                        1 -> "inc"
                        2 -> "trn"
                        else -> "exp"
                    }
                    val cat = if (type == "trn" && catSpinner.selectedItemPosition == 0) {
                        "이체/계좌이동"
                    } else {
                        catSpinner.selectedItem.toString()
                    }
                    val item = itemInput.text.toString().trim()
                    val qty = qtyInput.text.toString().trim()

                    LedgerStorage.addManualRecordWithDate(
                        context = this,
                        type = type,
                        category = cat,
                        merchant = merchant,
                        fromAccount = fromAcc,
                        toAccount = toAcc,
                        item = item,
                        quantity = qty,
                        amount = amt,
                        dateStr = selectedDateStr,
                        timestamp = selectedTimestamp
                    )
                    refreshUI()
                } else {
                    Toast.makeText(this, "제목(또는 계좌)과 금액을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEmailConfigDialog() {
        val isDark = LedgerStorage.isDarkMode(this)
        val descColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")
        val labelColor = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val statusBoxBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")

        var tempStart = LedgerStorage.getEmailPeriodStart(this)
        var tempEnd = LedgerStorage.getEmailPeriodEnd(this)

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        val savedProv = LedgerStorage.getEmailProvider(this)
        val savedAddr = LedgerStorage.getEmailAddress(this)
        val provLabel = if (savedProv == "naver") "네이버 메일" else "Gmail"

        val statusBanner = TextView(this).apply {
            text = if (savedAddr.isNotEmpty()) {
                "● 현재 연결된 이메일: $provLabel ($savedAddr)"
            } else {
                "○ 현재 연결된 이메일이 없습니다."
            }
            textSize = sp(13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (savedAddr.isNotEmpty()) Color.parseColor("#34D399") else descColor)
            background = roundBg(statusBoxBg, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        box.addView(statusBanner)

        val providerSpinner = makeThemedSpinner(
            isDark,
            listOf("Gmail (imap.gmail.com)", "네이버 메일 (imap.naver.com)"),
            if (savedProv == "naver") 1 else 0
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        box.addView(providerSpinner)

        val emailEdit = EditText(this).apply {
            hint = "이메일 주소 (예: cksqlsgus@gmail.com)"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(savedAddr)
            textSize = sp(14f)
            styleDialogEditText(this, isDark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        box.addView(emailEdit)

        val passEdit = EditText(this).apply {
            hint = "앱 비밀번호 (16자리, 띄어쓰기 없이 입력)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(LedgerStorage.getEmailPassword(this@MainActivity))
            textSize = sp(14f)
            styleDialogEditText(this, isDark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        box.addView(passEdit)

        box.addView(TextView(this).apply {
            text = "자동으로 불러올 이메일 기간 설정"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(6))
        })

        val fmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
        val rangeDisplay = TextView(this).apply {
            text = "${fmt.format(Date(tempStart))} ~ ${fmt.format(Date(tempEnd))}"
            textSize = sp(13f)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#34D399"))
            background = roundBg(statusBoxBg, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        box.addView(rangeDisplay)

        fun updateRangeLabel() {
            rangeDisplay.text = "${fmt.format(Date(tempStart))} ~ ${fmt.format(Date(tempEnd))}"
        }

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        fun addRangePreset(label: String, daysBack: Int, isThisMonth: Boolean = false) {
            val btn = makePillButton(isDark, label) {
                val nowCal = Calendar.getInstance()
                val y = nowCal.get(Calendar.YEAR)
                val m = nowCal.get(Calendar.MONTH)
                val d = nowCal.get(Calendar.DAY_OF_MONTH)
                if (isThisMonth) {
                    tempStart = startOfDay(y, m, 1)
                    tempEnd = endOfDay(y, m, nowCal.getActualMaximum(Calendar.DAY_OF_MONTH))
                } else {
                    val startCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -daysBack) }
                    tempStart = startOfDay(startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH))
                    tempEnd = endOfDay(y, m, d)
                }
                updateRangeLabel()
            }.apply {
                textSize = sp(11f)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(4)
                }
            }
            presetRow.addView(btn)
        }

        addRangePreset("오늘", 0)
        addRangePreset("7일", 6)
        addRangePreset("30일", 29)
        addRangePreset("이번 달", 0, isThisMonth = true)
        box.addView(presetRow)

        val datePickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val startPickBtn = makePillButton(isDark, "시작일 변경") {
            val cal = Calendar.getInstance().apply { timeInMillis = tempStart }
            DatePickerDialog(
                this@MainActivity,
                getDialogTheme(isDark),
                { _, y, m, d ->
                    tempStart = startOfDay(y, m, d)
                    if (tempEnd < tempStart) tempEnd = endOfDay(y, m, d)
                    updateRangeLabel()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }
        val endPickBtn = makePillButton(isDark, "종료일 변경") {
            val cal = Calendar.getInstance().apply { timeInMillis = tempEnd }
            DatePickerDialog(
                this@MainActivity,
                getDialogTheme(isDark),
                { _, y, m, d ->
                    tempEnd = endOfDay(y, m, d)
                    if (tempStart > tempEnd) tempStart = startOfDay(y, m, d)
                    updateRangeLabel()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        datePickRow.addView(startPickBtn)
        datePickRow.addView(endPickBtn)
        box.addView(datePickRow)

        fun triggerEmailSync(startMs: Long, endMs: Long) {
            Toast.makeText(this@MainActivity, "설정한 기간의 이메일 내역을 불러오는 중...", Toast.LENGTH_SHORT).show()
            Thread {
                val resultMsg = EmailImapFetcher.fetchEmailsInPeriod(this@MainActivity, startMs, endMs)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, resultMsg, Toast.LENGTH_LONG).show()
                    refreshUI()
                }
            }.start()
        }

        val syncNowBtn = makePillButton(isDark, "설정한 기간 이메일 지금 가져오기", isActive = true) {
            val prov = if (providerSpinner.selectedItemPosition == 1) "naver" else "gmail"
            val addr = emailEdit.text.toString().trim()
            val pw = passEdit.text.toString().replace(" ", "").trim()
            if (addr.isEmpty() || pw.isEmpty()) {
                Toast.makeText(this@MainActivity, "이메일과 앱 비밀번호를 먼저 입력하세요.", Toast.LENGTH_SHORT).show()
                return@makePillButton
            }
            LedgerStorage.saveEmailConfig(this@MainActivity, prov, addr, pw)
            LedgerStorage.saveEmailPeriod(this@MainActivity, tempStart, tempEnd)
            val pName = if (prov == "naver") "네이버 메일" else "Gmail"
            statusBanner.text = "● 현재 연결된 이메일: $pName ($addr)"
            statusBanner.setTextColor(Color.parseColor("#34D399"))
            triggerEmailSync(tempStart, tempEnd)
        }
        box.addView(syncNowBtn)

        scroll.addView(box)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("이메일 본문 직접 연동")
            .setView(scroll)
            .setPositiveButton("저장 및 가져오기") { _, _ ->
                val prov = if (providerSpinner.selectedItemPosition == 1) "naver" else "gmail"
                val addr = emailEdit.text.toString().trim()
                val pw = passEdit.text.toString().replace(" ", "").trim()
                LedgerStorage.saveEmailConfig(this, prov, addr, pw)
                LedgerStorage.saveEmailPeriod(this, tempStart, tempEnd)
                refreshUI()
                if (addr.isNotEmpty() && pw.isNotEmpty()) {
                    triggerEmailSync(tempStart, tempEnd)
                }
            }
            .setNeutralButton("연동 해제") { _, _ ->
                LedgerStorage.saveEmailConfig(this, "gmail", "", "")
                refreshUI()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showEditDialog(recordObj: JSONObject) {
        captureScrollPositions()
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val recordId = recordObj.optString("id", "")
        val oldType = recordObj.optString("type", "exp")
        val oldCat = recordObj.optString("category", "기타")
        val oldMerchant = recordObj.optString("merchant", recordObj.optString("desc", ""))
        val oldFrom = recordObj.optString("fromAccount", "")
        val oldTo = recordObj.optString("toAccount", "")
        val oldItem = recordObj.optString("item", "")
        val oldQty = recordObj.optString("quantity", "")
        val oldAmt = recordObj.optLong("amount", 0L)
        val oldRaw = recordObj.optString("raw", "")
        val itemsJsonStr = recordObj.optString("itemsJson", "[]")
        var editedDateStr = recordObj.optString("date", "")
        var editedTimestamp = getRecordTimestamp(recordObj).let { if (it > 0L) it else System.currentTimeMillis() }
        pendingImagePath = recordObj.optString("imagePath", "")

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val dateText = TextView(this).apply {
            text = "기록 일시: $editedDateStr"
            textSize = sp(13f)
            setTextColor(labelColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        dateRow.addView(dateText)
        dateRow.addView(makePillButton(isDark, "날짜변경") {
            val cal = Calendar.getInstance().apply { timeInMillis = editedTimestamp }
            DatePickerDialog(
                this@MainActivity,
                getDialogTheme(isDark),
                { _, y, m, d ->
                    cal.set(Calendar.YEAR, y)
                    cal.set(Calendar.MONTH, m)
                    cal.set(Calendar.DAY_OF_MONTH, d)
                    editedTimestamp = cal.timeInMillis
                    editedDateStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(editedTimestamp))
                    dateText.text = "기록 일시: $editedDateStr"
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        })
        container.addView(dateRow)

        val imgView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(160)
            ).apply { bottomMargin = dp(8) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        if (pendingImagePath.isNotEmpty() && File(pendingImagePath).exists()) {
            val bmp = BitmapFactory.decodeFile(pendingImagePath)
            if (bmp != null) {
                imgView.setImageBitmap(bmp)
                imgView.visibility = View.VISIBLE
            }
        }
        dialogImageView = imgView
        container.addView(imgView)

        val attachBtn = makePillButton(isDark, "영수증 / 이미지 첨부") {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            startActivityForResult(Intent.createChooser(intent, "이미지 선택"), REQ_PICK_IMAGE)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        container.addView(attachBtn)

        val spinnerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        val initialTypeIdx = when (oldType) {
            "inc" -> 1
            "trn" -> 2
            else -> 0
        }
        val typeSpinner = makeThemedSpinner(
            isDark,
            listOf("- (지출)", "+ (수입)", "⇄ (이체)"),
            initialTypeIdx
        ).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }
        val catIdx = NotificationParser.CATEGORIES.indexOf(oldCat).coerceAtLeast(0)
        val catSpinner = makeThemedSpinner(isDark, NotificationParser.CATEGORIES, catIdx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        spinnerRow.addView(typeSpinner)
        spinnerRow.addView(catSpinner)
        container.addView(spinnerRow)

        fun makeLabeledInput(label: String, initial: String, isNumber: Boolean = false, multiLine: Boolean = false): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = sp(12f)
                setTextColor(labelColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(3))
            })
            return EditText(this).apply {
                setText(initial)
                textSize = sp(14f)
                if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER
                if (multiLine) {
                    minLines = 3
                    isSingleLine = false
                }
                styleDialogEditText(this, isDark)
                container.addView(this)
            }
        }

        val merchantEdit = makeLabeledInput("제목 / 이동 경로", oldMerchant)
        val fromEdit = makeLabeledInput("보낸 곳 / 결제수단", oldFrom)
        val toEdit = makeLabeledInput("받는 곳 / 입금 계좌", oldTo)
        val itemEdit = makeLabeledInput("상세 품목 및 개별 금액", oldItem, multiLine = true)
        val qtyEdit = makeLabeledInput("총 수량", oldQty)
        val amtEdit = makeLabeledInput("금액 (원)", oldAmt.toString(), isNumber = true)
        val rawEdit = makeLabeledInput("결제 요약 및 원본 전문", oldRaw, multiLine = true)

        val parsedItemsArr = try { JSONArray(itemsJsonStr) } catch (_: Exception) { JSONArray() }
        var dialogRef: AlertDialog? = null

        if (parsedItemsArr.length() >= 2) {
            val splitBtn = makePillButton(isDark, "${parsedItemsArr.length()}개 품목을 각각 별도 내역으로 분리", isActive = true) {
                LedgerStorage.splitRecordIntoItems(
                    context = this@MainActivity,
                    recordObj = recordObj,
                    itemsArr = parsedItemsArr
                )
                dialogRef?.dismiss()
                refreshUI()
                Toast.makeText(this@MainActivity, "${parsedItemsArr.length()}건의 개별 내역으로 분리되었습니다.", Toast.LENGTH_SHORT).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
            }
            container.addView(splitBtn)
        }

        scroll.addView(container)

        dialogRef = AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("내역 상세 및 수정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val newType = when (typeSpinner.selectedItemPosition) {
                    1 -> "inc"
                    2 -> "trn"
                    else -> "exp"
                }
                val newCat = catSpinner.selectedItem.toString()
                val newFrom = fromEdit.text.toString().trim()
                val newTo = toEdit.text.toString().trim()
                var newMerchant = merchantEdit.text.toString().trim().ifEmpty { oldMerchant }
                if (newType == "trn" && (newFrom != oldFrom || newTo != oldTo) && (newFrom.isNotEmpty() || newTo.isNotEmpty())) {
                    if (newMerchant.contains("→") || newMerchant.matches(Regex("^[0-9\\-]+$"))) {
                        newMerchant = "${newFrom.ifEmpty { "출금" }} → ${newTo.ifEmpty { "입금" }}"
                    }
                }
                val newItem = itemEdit.text.toString().trim()
                val newQty = qtyEdit.text.toString().trim()
                val newAmt = amtEdit.text.toString().trim().toLongOrNull() ?: oldAmt
                val newRaw = rawEdit.text.toString().trim()

                LedgerStorage.updateRecord(
                    context = this,
                    id = recordId,
                    type = newType,
                    category = newCat,
                    merchant = newMerchant,
                    fromAccount = newFrom,
                    toAccount = newTo,
                    item = newItem,
                    quantity = newQty,
                    amount = newAmt,
                    raw = newRaw,
                    imagePath = pendingImagePath,
                    dateStr = editedDateStr,
                    timestamp = editedTimestamp
                )
                dialogImageView = null
                refreshUI()
            }
            .setNeutralButton("삭제") { _, _ ->
                LedgerStorage.deleteRecordById(this, recordId)
                dialogImageView = null
                refreshUI()
            }
            .setNegativeButton("취소") { _, _ ->
                dialogImageView = null
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return

        if (requestCode == REQ_PICK_IMAGE) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    if (bmp != null) {
                        val savedPath = LedgerStorage.saveBitmapToFile(this, bmp)
                        if (savedPath.isNotEmpty()) {
                            pendingImagePath = savedPath
                            dialogImageView?.setImageBitmap(bmp)
                            dialogImageView?.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
