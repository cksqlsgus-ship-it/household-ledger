package com.myledger.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session

class MainActivity : Activity() {

    companion object {
        private const val REQ_PICK_IMAGE = 1001
        private const val REQ_EXPORT_JSON = 1002
        private const val REQ_IMPORT_JSON = 1003
    }

    private lateinit var rootLayout: LinearLayout
    private lateinit var headerLayout: LinearLayout
    private lateinit var headerTitleText: TextView
    private lateinit var headerBadgeText: TextView

    private lateinit var contentHost: FrameLayout
    private lateinit var navDivider: View
    private lateinit var bottomNavBar: LinearLayout

    private var currentTab = "all" // "all", "analysis", "settings"
    private var selectedAnalysisCategory = "전체"
    private var currentQuery = ""
    private var isSearchExpanded = false

    private val pieIncludedCategories = linkedSetOf(
        "식비", "카페/간식", "쇼핑/생활", "교통/차량", "구독/통신", "의료/건강", "기타"
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

        headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }

        headerTitleText = TextView(this).apply {
            text = "전체"
            textSize = sp(22f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(headerTitleText)

        headerBadgeText = TextView(this).apply {
            textSize = sp(11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                if (!isNotificationGranted()) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } else {
                    currentTab = "settings"
                    refreshUI()
                }
            }
        }
        headerLayout.addView(headerBadgeText)
        rootLayout.addView(headerLayout)

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

    private fun refreshUI() {
        val isDark = LedgerStorage.isDarkMode(this)

        val bgPage = if (isDark) Color.parseColor("#0F0F0F") else Color.parseColor("#F4F4F6")
        val bgNav = if (isDark) Color.parseColor("#141414") else Color.WHITE
        val dividerColor = if (isDark) Color.parseColor("#262629") else Color.parseColor("#E5E5EA")
        val textPrimary = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val pillBg = if (isDark) Color.parseColor("#1C1C1E") else Color.parseColor("#E5E5EA")

        rootLayout.setBackgroundColor(bgPage)
        headerLayout.setBackgroundColor(bgPage)
        navDivider.setBackgroundColor(dividerColor)
        bottomNavBar.setBackgroundColor(bgNav)

        headerTitleText.setTextColor(textPrimary)
        headerTitleText.textSize = sp(22f)
        headerTitleText.text = when (currentTab) {
            "analysis" -> "분석"
            "settings" -> "설정"
            else -> "전체"
        }

        headerBadgeText.textSize = sp(11f)
        if (isNotificationGranted()) {
            headerBadgeText.text = "● 알림감지 ON"
            headerBadgeText.setTextColor(if (isDark) Color.parseColor("#D1D5DB") else Color.parseColor("#374151"))
            headerBadgeText.background = roundBg(pillBg, 14)
        } else {
            headerBadgeText.text = "● 알림감지 OFF"
            headerBadgeText.setTextColor(Color.WHITE)
            headerBadgeText.background = roundBg(Color.parseColor("#B93844"), 14)
        }

        renderBottomNavBar(isDark)

        contentHost.removeAllViews()
        when (currentTab) {
            "all" -> {
                val scroll = ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(4), dp(16), dp(24))
                }
                renderAllTab(isDark, container)
                scroll.addView(container)
                contentHost.addView(scroll)
            }
            "analysis" -> {
                renderCombinedAnalysisTab(isDark)
            }
            "settings" -> {
                val scroll = ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(4), dp(16), dp(28))
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
            refreshUI()
        })

        presetRow.addView(makePresetBtn("오늘", false) {
            val cal = Calendar.getInstance()
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH)
            val d = cal.get(Calendar.DAY_OF_MONTH)
            filterStartMillis = startOfDay(y, m, d)
            filterEndMillis = endOfDay(y, m, d)
            refreshUI()
        })

        presetRow.addView(makePresetBtn("이번 달", false) {
            val cal = Calendar.getInstance()
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH)
            val lastDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            filterStartMillis = startOfDay(y, m, 1)
            filterEndMillis = endOfDay(y, m, lastDay)
            refreshUI()
        })

        presetRow.addView(makePresetBtn("최근 7일", false) {
            val endCal = Calendar.getInstance()
            val startCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -6) }
            filterStartMillis = startOfDay(startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH))
            filterEndMillis = endOfDay(endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH))
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

    // =========================================================================
    // 1. [전체] 탭
    // =========================================================================
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

    // =========================================================================
    // 2. [분석] 탭
    // =========================================================================
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

        val leftCategories = listOf("전체") + NotificationParser.CATEGORIES
        for (cat in leftCategories) {
            val isSelected = (cat == selectedAnalysisCategory)
            val cnt = if (cat == "전체") periodRecords.size else (catCountInPeriod[cat] ?: 0)

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
                text = "${cnt}건"
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
        val rightContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(24))
        }

        rightContent.addView(
            buildPeriodFilterCard(isDark, compact = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
        )

        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
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

    // =========================================================================
    // 3. [설정] 탭
    // =========================================================================
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

        // 1) 결제 알림 자동 감지
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

        // 2) 연결된 이메일 및 기간 자동 조회 설정
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

        // 3) 특정 단어 자동 인식 규칙
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

        // 4) 다크 모드
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

        // 5) 글자 크기 조절
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
        groupCard.addView(makeDivider(isDark))

        // 6) 데이터 관리 (기록 내보내기 · 가져오기 · 초기화)
        val count = LedgerStorage.getRecords(this).length()
        groupCard.addView(TextView(this).apply {
            text = "💾  기록 내보내기 · 가져오기"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
        })
        groupCard.addView(TextView(this).apply {
            text = "현재 저장된 거래 내역 총 ${count}건 (JSON 파일로 백업 및 복원)"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setPadding(0, dp(3), 0, dp(10))
        })

        val backupBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val exportBtn = makePillButton(isDark, "내보내기 (백업)") {
            val dateTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "자동가계부_백업_$dateTag.json")
            }
            startActivityForResult(intent, REQ_EXPORT_JSON)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }

        val importBtn = makePillButton(isDark, "가져오기 (복원)") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQ_IMPORT_JSON)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
        }

        val resetBtn = makePillButton(isDark, "초기화") {
            AlertDialog.Builder(this, getDialogTheme(isDark))
                .setTitle("전체 내역 삭제")
                .setMessage("저장된 ${count}건의 모든 가계부 내역을 삭제하시겠습니까?")
                .setPositiveButton("모두 삭제") { _, _ ->
                    LedgerStorage.clearAllRecords(this)
                    refreshUI()
                    Toast.makeText(this, "모든 내역이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
        }

        backupBtnRow.addView(exportBtn)
        backupBtnRow.addView(importBtn)
        backupBtnRow.addView(resetBtn)
        groupCard.addView(backupBtnRow)

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

    // =========================================================================
    // 공통 UI 빌더 및 카드 렌더링
    // =========================================================================
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
                    if (newMerchant.contains("→") || newMerchant.matches(Regex("""^[0-9\-]+$"""))) {
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

        when (requestCode) {
            REQ_PICK_IMAGE -> {
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

            REQ_EXPORT_JSON -> {
                try {
                    val jsonStr = LedgerStorage.exportBackupJson(this)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonStr.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    val count = LedgerStorage.getRecords(this).length()
                    Toast.makeText(this, "가계부 기록 ${count}건을 파일로 내보냈습니다.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "내보내기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            REQ_IMPORT_JSON -> {
                try {
                    val content = contentResolver.openInputStream(uri)?.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                    } ?: ""
                    val previewCount = LedgerStorage.countRecordsInBackupJson(content)
                    if (previewCount < 0) {
                        Toast.makeText(this, "올바른 가계부 백업 JSON 파일이 아닙니다.", Toast.LENGTH_LONG).show()
                        return
                    }
                    val isDark = LedgerStorage.isDarkMode(this)
                    AlertDialog.Builder(this, getDialogTheme(isDark))
                        .setTitle("가계부 기록 가져오기")
                        .setMessage("파일에서 총 ${previewCount}건의 거래 내역을 찾았습니다.\n어떤 방식으로 불러올까요?")
                        .setPositiveButton("기존 내역에 합치기") { _, _ ->
                            val added = LedgerStorage.importBackupJson(this, content, overwrite = false)
                            refreshUI()
                            Toast.makeText(this, "새 내역 ${added}건을 합쳤습니다. (중복 제외)", Toast.LENGTH_LONG).show()
                        }
                        .setNeutralButton("기존 내역 덮어쓰기") { _, _ ->
                            val total = LedgerStorage.importBackupJson(this, content, overwrite = true)
                            refreshUI()
                            Toast.makeText(this, "총 ${total}건으로 복원되었습니다.", Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton("취소", null)
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(this, "가져오기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sp(base: Float): Float = base * LedgerStorage.getFontScale(this)

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }
}

// =========================================================================
// 커스텀 원형(도넛) 그래프 뷰
// =========================================================================
data class PieSlice(
    val label: String,
    val value: Long,
    val color: Int
)

class PieChartView(
    context: Context,
    private val slices: List<PieSlice>,
    private val holeColor: Int,
    private val primaryTextColor: Int,
    private val secondaryTextColor: Int
) : View(context) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = holeColor
    }
    private val sliceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val centerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryTextColor
        textAlign = Paint.Align.CENTER
    }
    private val centerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryTextColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) * 0.44f
        val innerRadius = radius * 0.54f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val total = slices.sumOf { it.value }
        if (total <= 0L) {
            arcPaint.color = Color.parseColor("#3A3A3C")
            canvas.drawCircle(cx, cy, radius, arcPaint)
            canvas.drawCircle(cx, cy, innerRadius, holePaint)
            centerTitlePaint.textSize = radius * 0.16f
            canvas.drawText("표시할 데이터 없음", cx, cy + (radius * 0.05f), centerTitlePaint)
            return
        }

        val gapAngle = if (slices.size > 1) 1.5f else 0f
        var startAngle = -90f
        sliceTextPaint.textSize = radius * 0.13f

        for (slice in slices) {
            val rawSweep = (slice.value.toFloat() / total.toFloat()) * 360f
            val sweep = (rawSweep - gapAngle).coerceAtLeast(1f)
            arcPaint.color = slice.color
            canvas.drawArc(oval, startAngle, sweep, true, arcPaint)

            if (rawSweep >= 26f) {
                val midRad = Math.toRadians((startAngle + rawSweep / 2f).toDouble())
                val textR = (radius + innerRadius) / 2f
                val tx = cx + (textR * Math.cos(midRad)).toFloat()
                val ty = cy + (textR * Math.sin(midRad)).toFloat() + (sliceTextPaint.textSize * 0.35f)
                val pctInt = ((slice.value.toDouble() / total.toDouble()) * 100).toInt()
                canvas.drawText("$pctInt%", tx, ty, sliceTextPaint)
            }
            startAngle += rawSweep
        }

        canvas.drawCircle(cx, cy, innerRadius, holePaint)
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        centerTitlePaint.textSize = radius * 0.13f
        centerValuePaint.textSize = radius * 0.16f

        canvas.drawText("선택 합계", cx, cy - (radius * 0.05f), centerTitlePaint)
        canvas.drawText("${nf.format(total)}원", cx, cy + (radius * 0.14f), centerValuePaint)
    }
}

class PaymentNotificationListener : NotificationListenerService() {

    private var lastSignature = ""
    private var lastTime = 0L

    // 시스템에 의해 알림 리스너 연결이 일시 끊겼을 때 자동 재바인딩 요청
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, PaymentNotificationListener::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        val linesBuilder = StringBuilder()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        textLines?.forEach { line ->
            if (!line.isNullOrBlank()) linesBuilder.append("\n").append(line.toString().trim())
        }

        val bundleMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        bundleMessages?.forEach { parcel ->
            if (parcel is Bundle) {
                val mSender = parcel.getCharSequence("sender")?.toString()?.trim() ?: ""
                val mText = parcel.getCharSequence("text")?.toString()?.trim() ?: ""
                if (mText.isNotEmpty()) {
                    linesBuilder.append("\n")
                    if (mSender.isNotEmpty()) linesBuilder.append("$mSender: ")
                    linesBuilder.append(mText)
                }
            }
        }

        val mainBody = if (bigText.length > text.length) bigText else text
        val combinedBody = buildString {
            append(mainBody)
            if (subText.isNotEmpty() && !mainBody.contains(subText)) append("\n$subText")
            val extraLinesStr = linesBuilder.toString().trim()
            if (extraLinesStr.isNotEmpty() && !mainBody.contains(extraLinesStr)) {
                append("\n$extraLinesStr")
            }
        }.trim()

        var fullText = "$title\n$combinedBody".trim()
        if (fullText.isEmpty()) return

        val pkgLower = pkg.lowercase(Locale.ROOT)
        val isSmsApp = pkgLower.contains("messaging") || pkgLower.contains("sms") || pkgLower.contains("mms")
        val isEmailApp = pkgLower.contains("gm") || pkgLower.contains("mail") || pkgLower.contains("outlook")

        if (isSmsApp) {
            val smsFullBody = readLatestInboxSms(title, combinedBody)
            if (smsFullBody.isNotEmpty()) {
                fullText = "$title\n$smsFullBody"
            }
        }

        val autoRules = LedgerStorage.getAutoRules(this)
        val parsed = NotificationParser.parse(title, fullText, fullText, autoRules)
        if (parsed != null) {
            val sig = "$pkg|${parsed.merchant}|${parsed.amount}"
            val now = System.currentTimeMillis()
            if (sig != lastSignature || now - lastTime >= 5000L) {
                lastSignature = sig
                lastTime = now

                val bmp = extractBitmap(extras, isSmsApp)
                val imagePath = if (bmp != null) LedgerStorage.saveBitmapToFile(this, bmp) else ""
                val recordTs = parsed.parsedTimestamp ?: now

                LedgerStorage.addOrMergeRecord(
                    context = this,
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
                    imagePath = imagePath,
                    auto = true,
                    customTimestamp = recordTs
                )
                sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").setPackage(packageName))
            }
        }

        if (isEmailApp && LedgerStorage.getEmailAddress(this).isNotEmpty()) {
            Thread {
                try {
                    Thread.sleep(2000L)
                    val now = System.currentTimeMillis()
                    EmailImapFetcher.fetchEmailsInPeriod(this, now - 180_000L, now + 60_000L)
                    sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").setPackage(packageName))
                } catch (_: Exception) {
                }
            }.start()
        }
    }

    private fun readLatestInboxSms(senderTitle: String, snippet: String): String {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return ""
        }
        return try {
            val now = System.currentTimeMillis()
            val cursor = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?",
                arrayOf((now - 30_000L).toString()),
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use { c ->
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                var bestMatch = ""
                while (c.moveToNext()) {
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx) ?: "" else ""
                    val addr = if (addrIdx >= 0) c.getString(addrIdx) ?: "" else ""
                    if (body.isEmpty()) continue
                    if (snippet.length >= 6 && body.contains(snippet.take(10))) {
                        return body
                    }
                    if (senderTitle.isNotEmpty() && addr.contains(senderTitle.replace("-", ""))) {
                        bestMatch = body
                    }
                    if (bestMatch.isEmpty() && NotificationParser.containsPaymentKeyword(body)) {
                        bestMatch = body
                    }
                }
                bestMatch
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractBitmap(extras: Bundle, isSmsApp: Boolean): Bitmap? {
        val pic = extras.get(Notification.EXTRA_PICTURE)
        if (pic is Bitmap) return pic
        if (isSmsApp) return null

        val largeBig = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
        if (largeBig is Bitmap) return largeBig
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && largeBig is Icon) {
            return drawableToBitmap(largeBig.loadDrawable(this))
        }
        return null
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 128
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 128
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}

object EmailImapFetcher {

    fun fetchEmailsInPeriod(context: Context, startMillis: Long, endMillis: Long): String {
        val provider = LedgerStorage.getEmailProvider(context)
        val email = LedgerStorage.getEmailAddress(context)
        val password = LedgerStorage.getEmailPassword(context)
        if (email.isEmpty() || password.isEmpty()) {
            return "이메일 설정이 비어 있습니다."
        }

        val host = if (provider == "naver") "imap.naver.com" else "imap.gmail.com"
        return try {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", host)
                put("mail.imaps.port", "993")
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "12000")
                put("mail.imaps.timeout", "12000")
            }

            val session = Session.getInstance(props)
            val store = session.getStore("imaps")
            store.connect(host, email, password)

            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val total = inbox.messageCount
            if (total == 0) {
                inbox.close(false)
                store.close()
                return "받은편지함이 비어 있습니다."
            }

            val startIdx = maxOf(1, total - 299)
            val messages = inbox.getMessages(startIdx, total)
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
            }
            inbox.fetch(messages, fp)

            var importedCount = 0
            var olderStreak = 0
            val autoRules = LedgerStorage.getAutoRules(context)

            for (i in messages.indices.reversed()) {
                val msg = messages[i]
                val receivedTime = msg.receivedDate?.time ?: msg.sentDate?.time ?: continue

                if (receivedTime > endMillis) continue
                if (receivedTime < startMillis) {
                    olderStreak++
                    if (olderStreak >= 10) break
                    continue
                }
                olderStreak = 0

                val subject = msg.subject ?: ""
                val bodyText = extractMessageText(msg)
                val fullText = "$subject\n$bodyText".trim()

                val parsed = NotificationParser.parse(subject, fullText, fullText, autoRules)
                if (parsed != null) {
                    val recordTs = parsed.parsedTimestamp ?: receivedTime
                    val inserted = LedgerStorage.addOrMergeRecord(
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
                        customTimestamp = recordTs
                    )
                    if (inserted) importedCount++
                }
            }

            inbox.close(false)
            store.close()

            if (importedCount > 0) {
                "연동 완료! 설정 기간 내 결제 메일 ${importedCount}건을 가져왔습니다."
            } else {
                "연동 완료! (설정 기간 내 새로 추가할 결제 메일이 없습니다)"
            }
        } catch (e: Exception) {
            "연동 실패: ${e.message ?: "이메일/앱 비밀번호를 확인하세요"}"
        }
    }

    private fun extractMessageText(message: Message): String {
        return try {
            val content = message.content
            when (content) {
                is String -> htmlToStructuredText(content)
                is Multipart -> extractFromMultipart(content)
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractFromMultipart(multipart: Multipart): String {
        var plainText = ""
        var htmlText = ""
        for (i in 0 until multipart.count) {
            val part = multipart.getBodyPart(i)
            val content = part.content
            if (part.isMimeType("text/plain") && content is String) {
                plainText += "\n" + content
            } else if (part.isMimeType("text/html") && content is String) {
                htmlText += "\n" + htmlToStructuredText(content)
            } else if (content is Multipart) {
                val nested = extractFromMultipart(content)
                if (nested.isNotEmpty()) htmlText += "\n" + nested
            }
        }
        return if (htmlText.trim().length >= plainText.trim().length) {
            htmlText.trim()
        } else {
            plainText.trim()
        }
    }

    private fun htmlToStructuredText(rawHtml: String): String {
        if (!rawHtml.contains("<")) return rawHtml.trim()
        val withMarkers = rawHtml
            .replace(Regex("""(?i)</(tr|p|div|li|h[1-6])>|<br\s*/?>"""), "___NL___")
            .replace(Regex("""(?i)</(td|th)>"""), " : ")
        val plain = Jsoup.parse(withMarkers).text()
        return plain.split("___NL___")
            .map { line ->
                line.replace(Regex("""\s+"""), " ")
                    .replace(Regex("""^[:\s]+|[:\s]+$"""), "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}

data class AutoRule(
    val id: String,
    val keyword: String,
    val action: String, // "delete", "transfer", "category"
    val category: String
)

object NotificationParser {

    val CATEGORIES = listOf(
        "식비", "카페/간식", "쇼핑/생활", "교통/차량", "구독/통신", "의료/건강", "이체/계좌이동", "수입", "기타"
    )

    private val KEYWORDS = listOf(
        "승인", "결제", "출금", "입금", "사용", "취소", "이체", "페이", "카드",
        "영수증", "KRW", "₩", "주문", "송금", "월회비", "구독", "갱신"
    )

    private val GENERIC_TAGS = listOf(
        "web발신", "국외발신", "국제발신", "광고", "알림", "발신전용",
        "신용카드", "체크카드", "계좌이체", "휴대폰결제", "간편결제", "카드", "현금영수증"
    )

    private val BANK_NAMES = listOf(
        "NH농협", "농협", "KB국민", "국민", "신한", "우리", "하나", "IBK기업", "기업",
        "카카오뱅크", "토스뱅크", "케이뱅크", "새마을금고", "새마을", "우체국", "수협", "신협",
        "대구", "부산", "경남", "광주", "전북", "제주", "SC제일", "씨티"
    )

    private val PG_OR_GENERIC_NAMES = listOf(
        "메시지", "문자", "신용카드", "체크카드", "신한카드", "삼성카드", "현대카드", "KB국민카드", "롯데카드",
        "우리카드", "하나카드", "NH농협카드", "카카오페이", "네이버페이", "토스", "Gmail",
        "NHN KCP", "KCP", "KG이니시스", "이니시스", "토스페이먼츠", "나이스페이", "다날", "발신전용", "이지페이"
    )

    data class LineItem(
        val name: String,
        val qty: Int,
        val price: Long
    )

    data class ParsedPayment(
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
        val itemsJson: String,
        val parsedTimestamp: Long?
    )

    fun containsPaymentKeyword(text: String): Boolean {
        return KEYWORDS.any { text.contains(it) }
    }

    // 불필요한 하단 포인트·약관·개발자 면책·사업자 안내 제거
    private fun stripBoilerplate(raw: String): String {
        val cutMarkers = listOf(
            "채무 지급보증 서비스",
            "상품의 교환/반품 안내",
            "교환/반품 불가 사유",
            "청약철회의 효력",
            "소비자 피해보상",
            "결제하신 물품에 대한",
            "본 메일은 발신전용",
            "본 메일은 발신 전용",
            "발신 전용으로 회신되지",
            "상점정보 : 상점 바로가기",
            "상점정보: 상점 바로가기",
            "상점 바로가기",
            "사업자등록번호",
            "통신판매업신고",
            "Play 포인트 적립",
            "포인트 내역에서 사용 가능한",
            "문의사항이 있는 경우",
            "Google Play 고객센터",
            "구독 취소 및 환불 정책",
            "이용약관",
            "개인정보처리방침",
            "고객센터 이용안내",
            "전자금융거래"
        )
        var cutIndex = raw.length
        for (marker in cutMarkers) {
            val idx = raw.indexOf(marker)
            if (idx in 15 until cutIndex) {
                cutIndex = idx
            }
        }
        var trimmed = raw.substring(0, cutIndex).trim()

        val promoStart = trimmed.indexOf("다양한 와우 혜택을 이용해보세요")
        val promoEnd = trimmed.indexOf("월회비 결제 정보")
        if (promoStart > 0 && promoEnd > promoStart) {
            trimmed = (trimmed.substring(0, promoStart).trim() + "\n" + trimmed.substring(promoEnd).trim()).trim()
        }

        return trimmed
    }

    private fun deduplicateLines(text: String): String {
        val seen = LinkedHashSet<String>()
        val noiseExact = setOf(
            "결제정보 : English Receipt 영수증 출력",
            "English Receipt 영수증 출력",
            "상품보기",
            "바로가기",
            "."
        )
        for (rawLine in text.split("\n")) {
            var cleaned = rawLine.replace(Regex("""^[0-9\-]{8,14}\s*[:：]\s*"""), "").trim()
            if (cleaned.isEmpty() || noiseExact.contains(cleaned)) continue

            val halfMatch = Regex("""^(.{4,30})\s+\1$""").find(cleaned)
            if (halfMatch != null) {
                cleaned = halfMatch.groupValues[1].trim()
            }
            seen.add(cleaned)
        }
        return seen.joinToString("\n")
    }

    // 실제 주문·결제 시각 추출 (콜론 형식 '21:36' 및 Google Play 한글 형식 '오후 10시 31분' 모두 지원)
    private fun extractActualPaymentTimestamp(text: String): Long? {
        val dtRegex = Regex("""(?:결제일시|승인일시|거래일시)\s*[:：]?\s*(20[0-9]{2})[./-]([0-9]{1,2})[./-]([0-9]{1,2})\s+([0-9]{1,2}):([0-9]{2})""")
        val inlineDtRegex = Regex("""(20[0-9]{2})[./-]([0-9]{1,2})[./-]([0-9]{1,2})\s+([0-9]{1,2}):([0-9]{2})에\s*(?:결제|승인)""")
        val m = dtRegex.find(text) ?: inlineDtRegex.find(text)
        if (m != null) {
            return try {
                val y = m.groupValues[1].toInt()
                val mon = m.groupValues[2].toInt() - 1
                val d = m.groupValues[3].toInt()
                val h = m.groupValues[4].toInt()
                val min = m.groupValues[5].toInt()
                Calendar.getInstance().apply {
                    set(y, mon, d, h, min, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } catch (_: Exception) {
                null
            }
        }

        // Google Play 주문 날짜: "2026. 10. 27. 오후 10시 31분 41초" 또는 "2026. 11. 27. 오후 2:30"
        val gpDtRegex = Regex(
            """주문\s*날짜\s*[:：]?\s*(20[0-9]{2})[./-]\s*([0-9]{1,2})[./-]\s*([0-9]{1,2})[.\s]+(오전|오후)?\s*([0-9]{1,2})\s*(?:시|:)\s*([0-9]{1,2})\s*분?"""
        )
        val gm = gpDtRegex.find(text)
        if (gm != null) {
            return try {
                val y = gm.groupValues[1].toInt()
                val mon = gm.groupValues[2].toInt() - 1
                val d = gm.groupValues[3].toInt()
                val ampm = gm.groupValues[4]
                val rawH = gm.groupValues[5].toInt()
                val min = gm.groupValues[6].toInt()
                val h = when {
                    ampm == "오후" && rawH < 12 -> rawH + 12
                    ampm == "오전" && rawH == 12 -> 0
                    else -> rawH
                }
                Calendar.getInstance().apply {
                    set(y, mon, d, h, min, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    fun parse(title: String, body: String, fullText: String, rules: List<AutoRule> = emptyList()): ParsedPayment? {
        val deduped = deduplicateLines(fullText)
        val cleanBody = stripBoilerplate(deduped)

        val deleteRule = rules.firstOrNull {
            it.action == "delete" && it.keyword.isNotBlank() && cleanBody.contains(it.keyword, ignoreCase = true)
        }
        if (deleteRule != null) return null

        if (!containsPaymentKeyword(cleanBody)) return null

        val actualTimestamp = extractActualPaymentTimestamp(cleanBody)

        // 1. 금액 추출
        val withoutBalance = cleanBody.replace(Regex("""잔액\s*[:：]?\s*[0-9,]+\s*원?"""), "")
        val totalAmtRegex = Regex(
            """(?:총\s*결제\s*금액|실\s*결제\s*금액|최종\s*결제\s*금액|총\s*주문\s*금액|결제\s*금액|승인\s*금액|출금\s*금액|입금\s*금액|이체\s*금액|합계\s*금액|상품\s*금액|총액|합계)\s*[:：]?\s*(?:(?:₩|KRW)\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})|([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원)"""
        )
        val bankTxnAmtRegex = Regex(
            """(?:출금|입금|이체|송금)\s*[:：]?\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원"""
        )
        val cardAmtRegex = Regex(
            """(?:카드|일시불|할부|페이)\s*(?:\([^)]*\))?\s*[:：/]\s*(?:₩|KRW)?\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원?"""
        )
        val fallbackAmtRegex = Regex("""([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원|(?:₩|KRW)\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})""")

        val rawNum = totalAmtRegex.find(withoutBalance)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?: bankTxnAmtRegex.find(withoutBalance)?.groupValues?.get(1)
            ?: cardAmtRegex.find(withoutBalance)?.groupValues?.get(1)
            ?: fallbackAmtRegex.find(withoutBalance)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?: return null

        val amount = rawNum.replace(",", "").toLongOrNull() ?: return null
        if (amount <= 0L) return null

        val balanceMatch = Regex("""잔액\s*[:：]?\s*([0-9,]+)\s*원?""").find(cleanBody)
        val balanceInfo = if (balanceMatch != null) "잔액 ${balanceMatch.groupValues[1]}원" else ""

        // 2. 출금 계좌 / 결제수단 추출 ('결제 방법:\nToss: 이정*' 줄바꿈 형식 포함)
        var fromAccount = ""
        var toAccount = ""
        val detectedBank = BANK_NAMES.firstOrNull { cleanBody.contains(it) } ?: ""
        val isCardApproval = cleanBody.contains("승인") || cleanBody.contains("카드") || cleanBody.contains("결제") || cleanBody.contains("Google Play")

        if (detectedBank.isNotEmpty() && !isCardApproval) {
            val afterAcctRegex = Regex("""[0-9*]{2,6}-[0-9*]{2,6}-[0-9*]{2,6}(?:-[0-9*]{1,4})?\s+([^\n\r]+?)(?=\s*잔액|\s*$|[\n\r])""")
            val afterTimeRegex = Regex("""[0-9]{1,2}:[0-9]{2}\s+([^\n\r]+?)(?=\s*잔액|\s*$|[\n\r])""")
            val counterparty = (afterAcctRegex.find(cleanBody)?.groupValues?.get(1)
                ?: afterTimeRegex.find(cleanBody)?.groupValues?.get(1)
                ?: "").replace(Regex("""^[0-9*\-\s]+"""), "").trim()

            if (cleanBody.contains("출금") || cleanBody.contains("송금")) {
                fromAccount = detectedBank
                toAccount = counterparty
            } else if (cleanBody.contains("입금")) {
                toAccount = detectedBank
                fromAccount = counterparty
            }
        } else {
            val payOrg = Regex("""결제\s*기관[ \t]*[:：][ \t]*([^\n\r]+)""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val payMethodSameLine = Regex("""결제\s*(?:수단|방법)[ \t]*[:：][ \t]*([^\n\r]+)""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val payMethodNextLine = if (payMethodSameLine.isEmpty()) {
                Regex("""결제\s*(?:수단|방법)[ \t]*[:：][ \t]*\n+[ \t]*([^\n\r]+)""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            } else ""
            val payMethod = payMethodSameLine.ifEmpty { payMethodNextLine }

            fromAccount = when {
                payOrg.isNotEmpty() && payMethod.isNotEmpty() -> "$payOrg $payMethod"
                payMethod.contains("/") -> payMethod.substringAfter("/").trim()
                payMethod.isNotEmpty() -> payMethod
                payOrg.isNotEmpty() -> payOrg
                else -> ""
            }
        }

        // 3. 자동 규칙 및 거래 구분 판별
        val matchedTransferRule = rules.firstOrNull {
            it.action == "transfer" && it.keyword.isNotBlank() && cleanBody.contains(it.keyword, ignoreCase = true)
        }
        val matchedCategoryRule = rules.firstOrNull {
            it.action == "category" && it.keyword.isNotBlank() && cleanBody.contains(it.keyword, ignoreCase = true)
        }

        val sanitizedForType = cleanBody
            .replace("부분취소카드", "")
            .replace(Regex("""할인금액\s*[:：]\s*-[0-9,]+원"""), "")

        val isInternalTransfer = (matchedTransferRule != null && !isCardApproval)

        val isIncome = !isInternalTransfer && (
            matchedCategoryRule?.category == "수입" ||
            sanitizedForType.contains("결제취소") ||
            sanitizedForType.contains("승인취소") ||
            sanitizedForType.contains("주문취소") ||
            sanitizedForType.contains("취소완료") ||
            sanitizedForType.contains("환불완료") ||
            sanitizedForType.contains("환불금액") ||
            Regex("""(?:입금|급여|용돈|이체입금|계좌입금)\s*(?:완료|금액|[:：]|[0-9,]+원)""").containsMatchIn(sanitizedForType)
        )

        val type = when {
            isInternalTransfer || matchedCategoryRule?.category == "이체/계좌이동" -> "trn"
            isIncome -> "inc"
            else -> "exp"
        }

        // 4. 결제처(상점명) 추출
        val labeledMerchantRegex = Regex("""(?:상호|구매상점명|가맹점명|상점명|사용처|결제처)\s*[:：]\s*([^\n\r]+)""")
        val labeledMerchant = labeledMerchantRegex.find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val bracketBrand = Regex("""\[([^\]]+)]""").findAll(title)
            .map { it.groupValues[1].trim() }
            .firstOrNull { tag -> GENERIC_TAGS.none { tag.equals(it, ignoreCase = true) || tag.contains(it, ignoreCase = true) } } ?: ""

        val orderedAtMerchant = Regex("""([가-힣a-zA-Z0-9()]{2,20})에서\s*(?:\[[^\]]+]\s*)?(?:주문하신|결제하신)""")
            .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val cleanTitle = title.replace(Regex("""\[.*?]"""), "").trim()
        val isPhoneNumberTitle = cleanTitle.matches(Regex("""^[0-9\-]{7,15}$"""))
        val isGenericTitle = cleanTitle.isEmpty() ||
            isPhoneNumberTitle ||
            cleanTitle.contains("주문하신 내역") ||
            cleanTitle.contains("결제하신 내역") ||
            cleanTitle.contains("결제 내역입니다") ||
            PG_OR_GENERIC_NAMES.any { cleanTitle.contains(it, ignoreCase = true) }

        val transferRouteTitle = if (type == "trn" && (fromAccount.isNotEmpty() || toAccount.isNotEmpty())) {
            when {
                fromAccount.isNotEmpty() && toAccount.isNotEmpty() -> "$fromAccount → $toAccount"
                fromAccount.isNotEmpty() -> "$fromAccount 출금"
                else -> "$toAccount 입금"
            }
        } else ""

        val merchant = when {
            transferRouteTitle.isNotEmpty() -> transferRouteTitle
            labeledMerchant.isNotEmpty() -> labeledMerchant
            title.contains("Google Play", ignoreCase = true) || cleanBody.contains("Google Play 주문 영수증") -> "Google Play"
            bracketBrand.isNotEmpty() -> bracketBrand
            orderedAtMerchant.isNotEmpty() -> orderedAtMerchant
            !isGenericTitle -> cleanTitle
            else -> {
                val lines = cleanBody.replace(Regex("""\[.*?]"""), "")
                    .split("\n")
                    .map { it.trim() }
                    .filter { line ->
                        line.isNotEmpty() &&
                            !line.matches(Regex("""^[0-9\-]{7,15}$""")) &&
                            !line.contains("잔액") &&
                            !line.contains("누적") &&
                            !line.contains("안전하게 결제") &&
                            !line.contains("확인해주세요") &&
                            PG_OR_GENERIC_NAMES.none { pg -> line.equals(pg, ignoreCase = true) }
                    }
                (lines.firstOrNull { it.contains("페이") || it.contains("마트") || it.contains("스토어") || it.contains("점") }
                    ?: lines.firstOrNull()
                    ?: "거래 내역").take(26)
            }
        }

        // 5. 상세 품목 추출 (콜론 있는 줄 + Google Play의 콜론 없는 '2000 Manus Credits (Manus AI) ₩29,000' 줄 모두 지원)
        val lineItems = extractLineItems(cleanBody)
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        val itemsJsonArr = JSONArray()

        val itemSummary: String
        val totalQuantity: String

        if (lineItems.isNotEmpty()) {
            var sumQty = 0
            val sb = StringBuilder()
            for (li in lineItems) {
                sumQty += li.qty
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("• ${li.name} (${li.qty}개) : ${nf.format(li.price)}원")

                itemsJsonArr.put(JSONObject().apply {
                    put("name", li.name)
                    put("qty", li.qty)
                    put("price", li.price)
                })
            }
            itemSummary = sb.toString()
            totalQuantity = if (lineItems.size >= 2) "총 ${sumQty}개" else "${sumQty}개"
        } else {
            val labeledItem = Regex("""(?:주문상품명|상품명|품목명|품목)\s*[:：]\s*([^\n\r]+)""")
                .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val membershipItem = Regex("""([가-힣a-zA-Z0-9\s]+(?:월회비|멤버십))(?:\s*결제내역)?""")
                .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val validPeriod = Regex("""적용기간\s*[:：]\s*([^\n\r]+)""")
                .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

            val resolvedItemName = labeledItem.ifEmpty { membershipItem }

            if (resolvedItemName.isNotEmpty()) {
                val periodSuffix = if (validPeriod.isNotEmpty()) " ($validPeriod)" else " (1개)"
                itemSummary = "• $resolvedItemName$periodSuffix : ${nf.format(amount)}원"
                totalQuantity = if (validPeriod.isNotEmpty()) "1개월" else "1개"
            } else {
                val safeLines = cleanBody.split("\n").filter { line ->
                    !line.contains("사업자") && !line.contains("통신판매") &&
                        !line.contains("승인번호") && !line.contains("연락처") && !line.contains("고객센터")
                }
                val safeBody = safeLines.joinToString("\n")
                val totalCountMatch = Regex("""(총[ \t]*[0-9]{1,3}[ \t]*(?:건|개|종))""").find(safeBody)?.groupValues?.get(1)?.trim() ?: ""
                val singleQtyMatch = Regex("""(?<![0-9\-])([1-9][0-9]{0,2}[ \t]*(?:개|잔|건|박스|병|팩|매|세트|봉|통|권))(?!입)""")
                    .find(safeBody)?.groupValues?.get(1)?.trim() ?: ""

                itemSummary = ""
                totalQuantity = totalCountMatch.ifEmpty { singleQtyMatch }
            }
        }

        val discountText = Regex("""할인금액\s*[:：]\s*([^\n\r]+)""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
        val shippingText = Regex("""배송비\s*[:：]\s*([^\n\r]+)""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val formattedRaw = buildString {
            append("[거래 요약]\n")
            if (type == "trn" && (fromAccount.isNotEmpty() || toAccount.isNotEmpty())) {
                append("• 이동 경로: ${fromAccount.ifEmpty { "-" }} → ${toAccount.ifEmpty { "-" }}\n")
            } else {
                append("• 결제처: $merchant\n")
                if (fromAccount.isNotEmpty()) {
                    append("• 결제수단: $fromAccount\n")
                }
            }
            append("• 거래 금액: ${nf.format(amount)}원")
            if (balanceInfo.isNotEmpty()) append(" ($balanceInfo)")
            if (discountText.isNotEmpty() || shippingText.isNotEmpty()) {
                val extra = listOfNotNull(
                    if (discountText.isNotEmpty()) "할인 $discountText" else null,
                    if (shippingText.isNotEmpty()) "배송비 $shippingText" else null
                ).joinToString(" / ")
                append(" ($extra)")
            }
            append("\n")
            if (itemSummary.isNotEmpty()) {
                append("• 상세 품목별 금액:\n$itemSummary\n")
            }
            append("\n[원본 알림 내용]\n")
            append(cleanBody)
        }

        val category = when {
            type == "trn" -> "이체/계좌이동"
            matchedCategoryRule != null -> matchedCategoryRule.category
            type == "inc" -> "수입"
            else -> classifyCategory("$merchant $itemSummary $cleanBody")
        }

        return ParsedPayment(
            type = type,
            category = category,
            merchant = merchant.ifEmpty { "거래 내역" },
            fromAccount = fromAccount,
            toAccount = toAccount,
            balanceInfo = balanceInfo,
            itemSummary = itemSummary,
            totalQuantity = totalQuantity,
            amount = amount,
            cleanRawText = formattedRaw,
            itemsJson = itemsJsonArr.toString(),
            parsedTimestamp = actualTimestamp
        )
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val result = mutableListOf<LineItem>()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val tableRowRegex = Regex(
            """^(.+?)\s*:\s*([0-9,]+)원\s*:\s*([0-9]+)\s*:\s*([0-9,]+)원(?:\s*:.*)?$"""
        )
        // 패턴 B: 콜론 구분 ("항목명 : ₩29,000/월" 또는 "상품명 : 11,900원")
        val simpleRowRegex = Regex(
            """^(.+?)\s*:\s*(?:(?:₩|KRW)\s*([0-9,]+)|([0-9,]+)\s*원)(?:\s*/\s*(?:월|년|주|일))?$"""
        )
        // 패턴 C: Google Play 영수증처럼 콜론 없이 공백으로 이어진 줄 ("2000 Manus Credits (Manus AI) ₩29,000")
        val inlineCurrencyRowRegex = Regex(
            """^(.+?)\s+(?:₩|KRW)\s*([0-9,]+)(?:\s*/\s*(?:월|년|주|일))?$"""
        )

        val skipLabels = listOf(
            "상품 금액", "상품금액", "할인금액", "할인 금액", "배송비", "총 결제금액",
            "결제금액", "실결제금액", "일시불", "할부", "카드", "승인금액", "포인트",
            "쿠폰", "잔액", "배달비", "총액", "합계", "부가세", "세금", "VAT"
        )

        for (line in lines) {
            if (line.startsWith("(") && line.endsWith(")")) continue

            val mA = tableRowRegex.find(line)
            if (mA != null) {
                var rawName = mA.groupValues[1].trim()
                val qty = mA.groupValues[3].toIntOrNull() ?: 1
                val itemTotalPrice = mA.groupValues[4].replace(",", "").toLongOrNull() ?: 0L

                rawName = rawName.replace(Regex("""[,\s]+${qty}개$"""), "").trim()
                if (rawName.isNotEmpty() && itemTotalPrice > 0L) {
                    result.add(LineItem(rawName, qty, itemTotalPrice))
                }
                continue
            }

            val mB = simpleRowRegex.find(line)
            if (mB != null) {
                val labelPart = mB.groupValues[1].trim()
                val rawPriceStr = mB.groupValues[2].ifEmpty { mB.groupValues[3] }
                val pricePart = rawPriceStr.replace(",", "").toLongOrNull() ?: 0L
                if (pricePart > 0L &&
                    !labelPart.contains("/") &&
                    skipLabels.none { labelPart.contains(it, ignoreCase = true) }
                ) {
                    val qtyInName = Regex("""[,\s]+([0-9]+)개$""").find(labelPart)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val cleanName = labelPart.replace(Regex("""[,\s]+[0-9]+개$"""), "").trim()
                    if (cleanName.length >= 2) {
                        result.add(LineItem(cleanName, qtyInName, pricePart))
                    }
                }
                continue
            }

            val mC = inlineCurrencyRowRegex.find(line)
            if (mC != null) {
                val labelPart = mC.groupValues[1].trim()
                val pricePart = mC.groupValues[2].replace(",", "").toLongOrNull() ?: 0L
                if (pricePart > 0L &&
                    skipLabels.none { labelPart.contains(it, ignoreCase = true) }
                ) {
                    val qtyInName = Regex("""[,\s]+([0-9]+)개$""").find(labelPart)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val cleanName = labelPart.replace(Regex("""[,\s]+[0-9]+개$"""), "").trim()
                    if (cleanName.length >= 2) {
                        result.add(LineItem(cleanName, qtyInName, pricePart))
                    }
                }
            }
        }
        return result
    }

    private fun classifyCategory(text: String): String {
        val rules = listOf(
            "구독/통신" to listOf("월회비", "로켓와우", "와우 멤버십", "멤버십", "정기결제", "구독", "SKT", "KT", "LGU+", "통신요금", "넷플릭스", "유튜브", "티빙", "웨이브", "디즈니", "구글", "Google", "애플", "라프텔", "듀오링고"),
            "카페/간식" to listOf("스타벅스", "투썸", "메가커피", "컴포즈", "빽다방", "이디야", "할리스", "폴바셋", "카페", "커피", "베이커리", "파리바게뜨", "뚜레쥬르", "베스킨", "하겐다즈", "아이스크림", "디저트"),
            "식비" to listOf("배달의민족", "배민", "쿠팡이츠", "요기요", "맥도날드", "버거킹", "롯데리아", "식당", "김밥", "국밥", "치킨", "피자", "족발", "보쌈", "횟집", "고기", "한식", "중식", "일식", "분식", "편의점", "GS25", "CU", "세븐일레븐", "바나나"),
            "교통/차량" to listOf("택시", "카카오T", "티머니", "버스", "지하철", "코레일", "SRT", "KTX", "대한항공", "아시아나", "주유", "GS칼텍스", "SK에너지", "S-OIL", "하이패스", "주차"),
            "쇼핑/생활" to listOf("쿠팡", "네이버쇼핑", "11번가", "G마켓", "옥션", "SSG", "이마트", "홈플러스", "롯데마트", "다이소", "올리브영", "무신사", "마트", "마켓컬리"),
            "의료/건강" to listOf("병원", "의원", "치과", "한의원", "약국", "안과", "피부과", "내과", "정형외과", "건강검진")
        )

        for ((cat, words) in rules) {
            if (words.any { text.contains(it, ignoreCase = true) }) {
                return cat
            }
        }
        return "기타"
    }
}

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

    fun exportBackupJson(context: Context): String {
        val root = JSONObject()
        root.put("app", "AutoLedger")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("records", getRecords(context))
        val rulesRaw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUTO_RULES, "[]") ?: "[]"
        root.put("rules", JSONArray(rulesRaw))
        return root.toString(2)
    }

    fun countRecordsInBackupJson(jsonStr: String): Int {
        return try {
            val trimmed = jsonStr.trim()
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed).length()
            } else {
                val obj = JSONObject(trimmed)
                obj.getJSONArray("records").length()
            }
        } catch (_: Exception) {
            -1
        }
    }

    fun importBackupJson(context: Context, jsonStr: String, overwrite: Boolean): Int {
        val trimmed = jsonStr.trim()
        val incomingRecords: JSONArray
        var incomingRules: JSONArray? = null

        if (trimmed.startsWith("[")) {
            incomingRecords = JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            incomingRecords = root.getJSONArray("records")
            if (root.has("rules")) {
                incomingRules = root.optJSONArray("rules")
            }
        }

        if (overwrite) {
            saveSorted(context, incomingRecords)
            if (incomingRules != null && incomingRules.length() > 0) {
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_AUTO_RULES, incomingRules.toString()).apply()
            }
            return incomingRecords.length()
        } else {
            val existing = getRecords(context)
            val existingIds = HashSet<String>()
            val existingSignatures = HashSet<String>()

            for (i in 0 until existing.length()) {
                val e = existing.getJSONObject(i)
                existingIds.add(e.optString("id"))
                existingSignatures.add("${e.optLong("timestamp")}_${e.optLong("amount")}_${e.optString("merchant")}")
            }

            var addedCount = 0
            for (i in 0 until incomingRecords.length()) {
                val item = incomingRecords.getJSONObject(i)
                val id = item.optString("id")
                val sig = "${item.optLong("timestamp")}_${item.optLong("amount")}_${item.optString("merchant")}"
                if ((id.isNotEmpty() && existingIds.contains(id)) || existingSignatures.contains(sig)) {
                    continue
                }
                existing.put(item)
                addedCount++
            }
            saveSorted(context, existing)
            return addedCount
        }
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
