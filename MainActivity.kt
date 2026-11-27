package com.myledger.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session

class MainActivity : Activity() {

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

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        headerTitleText = TextView(this).apply {
            text = "💰 자동 가계부"
            setTextColor(Color.WHITE)
            textSize = sp(19f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(headerTitleText)

        headerBadgeText = TextView(this).apply {
            textSize = sp(11f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
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
            setPadding(dp(6), dp(8), dp(6), dp(10))
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

        val bgPage = if (isDark) Color.parseColor("#111827") else Color.parseColor("#F3F4F6")
        val bgHeader = if (isDark) Color.parseColor("#065F46") else Color.parseColor("#10B981")
        val bgNav = if (isDark) Color.parseColor("#18202C") else Color.WHITE
        val dividerColor = if (isDark) Color.parseColor("#2D3748") else Color.parseColor("#E5E7EB")

        rootLayout.setBackgroundColor(bgPage)
        headerLayout.setBackgroundColor(bgHeader)
        navDivider.setBackgroundColor(dividerColor)
        bottomNavBar.setBackgroundColor(bgNav)

        headerTitleText.textSize = sp(19f)
        headerTitleText.text = when (currentTab) {
            "analysis" -> "📊 카테고리 · 기간 분석"
            "settings" -> "⚙️ 앱 설정"
            else -> "💰 자동 가계부"
        }

        headerBadgeText.textSize = sp(11f)
        if (isNotificationGranted()) {
            headerBadgeText.text = "● 알림감지 ON"
            headerBadgeText.setTextColor(if (isDark) Color.parseColor("#A7F3D0") else Color.parseColor("#065F46"))
            headerBadgeText.background = roundBg(if (isDark) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5"), 12)
        } else {
            headerBadgeText.text = "⚠️ 권한 켜기"
            headerBadgeText.setTextColor(if (isDark) Color.parseColor("#FECACA") else Color.parseColor("#991B1B"))
            headerBadgeText.background = roundBg(if (isDark) Color.parseColor("#7F1D1D") else Color.parseColor("#FEE2E2"), 12)
        }

        renderBottomNavBar(isDark)

        contentHost.removeAllViews()
        when (currentTab) {
            "all" -> {
                val scroll = ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(24))
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
                    setPadding(dp(14), dp(14), dp(14), dp(24))
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
            Triple("all", "📋", "전체"),
            Triple("analysis", "📊", "분석"),
            Triple("settings", "⚙️", "설정")
        )

        val pillActiveBg = if (isDark) Color.parseColor("#1A3B5C") else Color.parseColor("#DBEAFE")
        val labelActiveColor = if (isDark) Color.parseColor("#93C5FD") else Color.parseColor("#1D4ED8")
        val labelInactiveColor = if (isDark) Color.parseColor("#D1D5DB") else Color.parseColor("#4B5563")

        for ((tabKey, iconEmoji, labelText) in tabs) {
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

            val iconPill = TextView(this).apply {
                text = iconEmoji
                textSize = sp(16f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(58), dp(30))
                background = if (isSelected) roundBg(pillActiveBg, 15) else null
            }
            tabItem.addView(iconPill)

            val labelView = TextView(this).apply {
                text = labelText
                textSize = sp(12f)
                gravity = Gravity.CENTER
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) labelActiveColor else labelInactiveColor)
                setPadding(0, dp(3), 0, 0)
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
        val textColor = if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#111827")
        val dropBg = if (isDark) Color.parseColor("#1F2937") else Color.WHITE
        val boxBg = if (isDark) Color.parseColor("#374151") else Color.parseColor("#F3F4F6")

        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(textColor)
                tv.textSize = sp(14f)
                tv.setPadding(dp(10), dp(10), dp(10), dp(10))
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
            background = roundBg(boxBg, 8)
            if (selectedIndex in items.indices) {
                setSelection(selectedIndex)
            }
        }
    }

    private fun styleDialogEditText(edit: EditText, isDark: Boolean) {
        val textColor = if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#111827")
        val hintColor = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
        val boxBg = if (isDark) Color.parseColor("#374151") else Color.parseColor("#F3F4F6")
        edit.setTextColor(textColor)
        edit.setHintTextColor(hintColor)
        edit.background = roundBg(boxBg, 8)
        edit.setPadding(dp(12), dp(10), dp(12), dp(10))
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
        val bgCard = if (isDark) Color.parseColor("#1F2937") else Color.WHITE
        val chipBg = if (isDark) Color.parseColor("#374151") else Color.parseColor("#F3F4F6")
        val textPrimary = if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#D1D5DB") else Color.parseColor("#4B5563")
        val activeColor = Color.parseColor("#10B981")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 12)
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
                background = roundBg(if (isActive) activeColor else chipBg, 8)
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
        val startLabel = if (filterStartMillis != null) "📅 ${btnFmt.format(Date(filterStartMillis!!))}" else "📅 시작일"
        val endLabel = if (filterEndMillis != null) "📅 ${btnFmt.format(Date(filterEndMillis!!))}" else "📅 종료일"

        val startBtn = TextView(this).apply {
            text = startLabel
            textSize = sp(if (compact) 11f else 12f)
            gravity = Gravity.CENTER
            setTextColor(if (filterStartMillis != null) Color.parseColor("#34D399") else textSecondary)
            setTypeface(null, if (filterStartMillis != null) Typeface.BOLD else Typeface.NORMAL)
            background = roundBg(chipBg, 8)
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
            setTextColor(if (filterEndMillis != null) Color.parseColor("#34D399") else textSecondary)
            setTypeface(null, if (filterEndMillis != null) Typeface.BOLD else Typeface.NORMAL)
            background = roundBg(chipBg, 8)
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
        val bgCard = if (isDark) Color.parseColor("#1F2937") else Color.WHITE
        val bgSubCard = if (isDark) Color.parseColor("#374151") else Color.parseColor("#E5E7EB")
        val textPrimary = if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#D1D5DB") else Color.parseColor("#4B5563")
        val incColor = if (isDark) Color.parseColor("#60A5FA") else Color.parseColor("#2563EB")
        val expColor = if (isDark) Color.parseColor("#F87171") else Color.parseColor("#DC2626")
        val trnColor = if (isDark) Color.parseColor("#C084FC") else Color.parseColor("#7C3AED")

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
                if (isSearchingOrOpen) Color.parseColor("#065F46") else bgCard,
                10
            )
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener {
                isSearchExpanded = !isSearchExpanded
                refreshUI()
            }
        }

        searchToggleBtn.addView(TextView(this).apply {
            text = if (currentQuery.isNotEmpty()) "🔍 \"$currentQuery\"" else if (isSearchExpanded) "🔍 검색 닫기" else "🔍 검색"
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
                setTextColor(Color.parseColor("#FCA5A5"))
                setOnClickListener {
                    currentQuery = ""
                    isSearchExpanded = false
                    refreshUI()
                }
            })
        }
        actionBar.addView(searchToggleBtn)

        val addModalBtn = TextView(this).apply {
            text = "➕ 내역 추가"
            textSize = sp(13f)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#10B981"), 10)
            setPadding(dp(10), dp(9), dp(10), dp(9))
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
                background = roundBg(bgCard, 10)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
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
                background = roundBg(Color.parseColor("#10B981"), 8)
                setPadding(dp(14), dp(9), dp(14), dp(9))
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
                        background = roundBg(bgSubCard, 12)
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
                    setTextColor(Color.parseColor("#F87171"))
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
            text = "내역 ${filteredList.size}건 (눌러서 상세 보기 · 수정)"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(10), dp(4), dp(6))
        })

        renderRecordCards(container, filteredList, bgCard, textSecondary, incColor, expColor, trnColor, nf, compact = false)
    }

    // =========================================================================
    // 2. [분석] 탭
    // =========================================================================
    private fun renderCombinedAnalysisTab(isDark: Boolean) {
        val bgSidebar = if (isDark) Color.parseColor("#18202C") else Color.parseColor("#E5E7EB")
        val bgCard = if (isDark) Color.parseColor("#1F2937") else Color.WHITE
        val barTrackColor = if (isDark) Color.parseColor("#374151") else Color.parseColor("#E5E7EB")
        val textPrimary = if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#D1D5DB") else Color.parseColor("#4B5563")
        val incColor = if (isDark) Color.parseColor("#60A5FA") else Color.parseColor("#2563EB")
        val expColor = if (isDark) Color.parseColor("#F87171") else Color.parseColor("#DC2626")
        val trnColor = if (isDark) Color.parseColor("#C084FC") else Color.parseColor("#7C3AED")

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
            layoutParams = LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val leftList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(16))
        }

        val leftCategories = listOf("전체") + NotificationParser.CATEGORIES
        for (cat in leftCategories) {
            val isSelected = (cat == selectedAnalysisCategory)
            val cnt = if (cat == "전체") periodRecords.size else (catCountInPeriod[cat] ?: 0)

            val catItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundBg(
                    if (isSelected) Color.parseColor("#10B981") else bgCard,
                    10
                )
                setPadding(dp(4), dp(10), dp(4), dp(10))
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
                textSize = sp(12f)
                gravity = Gravity.CENTER
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else textPrimary)
            })

            catItem.addView(TextView(this).apply {
                text = "${cnt}건"
                textSize = sp(10f)
                gravity = Gravity.CENTER
                setTextColor(if (isSelected) Color.parseColor("#ECFDF5") else textSecondary)
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
            setPadding(dp(10), dp(10), dp(12), dp(24))
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
        val subCatExpMap = mutableMapOf<String, Long>()
        val subCatCntMap = mutableMapOf<String, Int>()
        val merchantAmtMap = mutableMapOf<String, Long>()
        val merchantCntMap = mutableMapOf<String, Int>()

        for (obj in periodRecords) {
            val cat = obj.optString("category", "기타")
            if (selectedAnalysisCategory != "전체" && cat != selectedAnalysisCategory) continue

            targetRecords.add(obj)
            val type = obj.optString("type", "exp")
            val amount = obj.optLong("amount", 0L)
            val merchant = obj.optString("merchant", obj.optString("desc", "기타"))

            when (type) {
                "inc" -> {
                    selInc += amount
                    if (selectedAnalysisCategory == "수입") {
                        merchantAmtMap[merchant] = (merchantAmtMap[merchant] ?: 0L) + amount
                        merchantCntMap[merchant] = (merchantCntMap[merchant] ?: 0) + 1
                    }
                }
                "trn" -> {
                    selTrn += amount
                    if (selectedAnalysisCategory == "이체/계좌이동") {
                        merchantAmtMap[merchant] = (merchantAmtMap[merchant] ?: 0L) + amount
                        merchantCntMap[merchant] = (merchantCntMap[merchant] ?: 0) + 1
                    }
                }
                else -> {
                    selExp += amount
                    subCatExpMap[cat] = (subCatExpMap[cat] ?: 0L) + amount
                    subCatCntMap[cat] = (subCatCntMap[cat] ?: 0) + 1
                    merchantAmtMap[merchant] = (merchantAmtMap[merchant] ?: 0L) + amount
                    merchantCntMap[merchant] = (merchantCntMap[merchant] ?: 0) + 1
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

        val chartCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        if (selectedAnalysisCategory == "전체") {
            chartCard.addView(TextView(this).apply {
                text = "📊 카테고리별 순수 지출 비중 (이체 제외)"
                setTextColor(textPrimary)
                textSize = sp(13f)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })

            if (selExp == 0L) {
                chartCard.addView(TextView(this).apply {
                    text = "해당 기간에 지출 내역이 없습니다."
                    setTextColor(textSecondary)
                    textSize = sp(12f)
                })
            } else {
                for ((cat, amt) in subCatExpMap.entries.sortedByDescending { it.value }) {
                    val pct = ((amt.toDouble() / selExp.toDouble()) * 100).toInt()
                    val cnt = subCatCntMap[cat] ?: 0

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(5), 0, dp(3))
                    }
                    row.addView(TextView(this).apply {
                        text = "$cat (${cnt}건 · $pct%)"
                        setTextColor(textPrimary)
                        textSize = sp(12f)
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(this).apply {
                        text = "-${nf.format(amt)}원"
                        setTextColor(expColor)
                        textSize = sp(12f)
                        setTypeface(null, Typeface.BOLD)
                    })
                    chartCard.addView(row)

                    val track = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        background = roundBg(barTrackColor, 4)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
                    }
                    val ratio = (amt.toFloat() / selExp.toFloat()).coerceIn(0.03f, 1.0f)
                    track.addView(View(this).apply {
                        background = roundBg(Color.parseColor("#10B981"), 4)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio)
                    })
                    track.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - ratio)
                    })
                    chartCard.addView(track)
                }
            }
        } else {
            val topTitle = when (selectedAnalysisCategory) {
                "이체/계좌이동" -> "⇄ 주요 계좌 이동 경로 TOP 5"
                "수입" -> "💰 주요 수입처 TOP 5"
                else -> "🏆 [$selectedAnalysisCategory] 최다 결제처 TOP 5"
            }
            val topValueColor = when (selectedAnalysisCategory) {
                "이체/계좌이동" -> trnColor
                "수입" -> incColor
                else -> expColor
            }
            val topSign = when (selectedAnalysisCategory) {
                "이체/계좌이동" -> "⇄ "
                "수입" -> "+"
                else -> "-"
            }

            chartCard.addView(TextView(this).apply {
                text = topTitle
                setTextColor(textPrimary)
                textSize = sp(13f)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })

            val topMerchants = merchantAmtMap.entries.sortedByDescending { it.value }.take(5)
            if (topMerchants.isEmpty()) {
                chartCard.addView(TextView(this).apply {
                    text = "해당 기간의 내역이 없습니다."
                    setTextColor(textSecondary)
                    textSize = sp(12f)
                })
            } else {
                topMerchants.forEachIndexed { idx, (merchant, amt) ->
                    val cnt = merchantCntMap[merchant] ?: 0
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    row.addView(TextView(this).apply {
                        text = "${idx + 1}. $merchant (${cnt}회)"
                        setTextColor(textPrimary)
                        textSize = sp(12f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(this).apply {
                        text = "$topSign${nf.format(amt)}원"
                        setTextColor(topValueColor)
                        textSize = sp(12f)
                        setTypeface(null, Typeface.BOLD)
                    })
                    chartCard.addView(row)
                }
            }
        }
        rightContent.addView(chartCard)

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
    // 3. [설정] 탭 (내 계좌 이체 자동 인식 키워드 설정 포함)
    // =========================================================================
    private fun renderSettingsTab(isDark: Boolean, container: LinearLayout) {
        val bgCard = if (isDark) Color.parseColor("#1F2937") else Color.WHITE
        val chipBg = if (isDark) Color.parseColor("#374151") else Color.parseColor("#F3F4F6")
        val textPrimary = if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#D1D5DB") else Color.parseColor("#4B5563")

        fun makeSettingCard(title: String, desc: String, btnText: String, btnColor: String, onClick: () -> Unit): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(bgCard, 12)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            card.addView(TextView(this).apply {
                text = title
                setTextColor(textPrimary)
                textSize = sp(15f)
                setTypeface(null, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = desc
                setTextColor(textSecondary)
                textSize = sp(13f)
                setPadding(0, dp(4), 0, dp(10))
            })
            card.addView(Button(this).apply {
                text = btnText
                textSize = sp(13f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = roundBg(Color.parseColor(btnColor), 8)
                setOnClickListener { onClick() }
            })
            return card
        }

        // 1. 알림 접근 권한
        val granted = isNotificationGranted()
        container.addView(
            makeSettingCard(
                title = "🔔 결제 알림 자동 감지 권한",
                desc = if (granted) "현재 정상적으로 켜져 있습니다. 문자·카톡·카드앱 결제 알림이 오면 자동 기록됩니다." else "권한이 꺼져 있어 알림을 감지할 수 없습니다. 아래 버튼을 눌러 스위치를 켜주세요.",
                btnText = if (granted) "알림 접근 권한 설정 열기" else "⚠️ 지금 알림 접근 권한 켜기",
                btnColor = if (granted) "#10B981" else "#DC2626"
            ) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        // 2. 내 계좌 · 이체 자동 인식 키워드 설정 카드
        val trnCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        trnCard.addView(TextView(this).apply {
            text = "🏦 내 계좌 · 이체 자동 인식 설정"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
        })
        trnCard.addView(TextView(this).apply {
            text = "본인 이름이나 내 통장 별칭을 쉼표(,)로 구분해 적어두면, 해당 계좌로 이동한 알림은 지출(-)이 아닌 '⇄ 이체'로 자동 분류됩니다."
            setTextColor(textSecondary)
            textSize = sp(12f)
            setPadding(0, dp(4), 0, dp(8))
        })

        val trnKeywordsEdit = EditText(this).apply {
            setText(LedgerStorage.getTransferKeywordsRaw(this@MainActivity))
            hint = "예: 이정현, 토뱅, 카카오뱅크, 내계좌"
            textSize = sp(13f)
            styleDialogEditText(this, isDark)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        trnCard.addView(trnKeywordsEdit)

        trnCard.addView(Button(this).apply {
            text = "💾 이체 인식 키워드 저장"
            textSize = sp(13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#7C3AED"), 8)
            setOnClickListener {
                LedgerStorage.setTransferKeywordsRaw(this@MainActivity, trnKeywordsEdit.text.toString())
                Toast.makeText(this@MainActivity, "내 계좌 이체 인식 키워드가 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
        })
        container.addView(trnCard)

        // 3. 이메일 본문 직접 연동
        val savedEmail = LedgerStorage.getEmailAddress(this)
        val savedProv = if (LedgerStorage.getEmailProvider(this) == "naver") "네이버 메일" else "Gmail"
        container.addView(
            makeSettingCard(
                title = "📧 이메일 영수증 본문 직접 연동",
                desc = if (savedEmail.isNotEmpty()) "연동됨: $savedProv ($savedEmail)\n이메일 알림이 올 때 잘린 뒷부분까지 전체 본문을 직접 가져옵니다." else "Gmail이나 네이버 메일의 앱 비밀번호를 등록하면 영수증 메일 전체 내용을 자동으로 읽어옵니다.",
                btnText = if (savedEmail.isNotEmpty()) "이메일 연동 정보 수정 / 테스트" else "이메일 연동 설정하기",
                btnColor = "#2563EB"
            ) {
                showEmailConfigDialog()
            }
        )

        // 4. 화면 테마 (다크모드)
        container.addView(
            makeSettingCard(
                title = "🌓 화면 테마 설정",
                desc = if (isDark) "현재 어두운 다크 모드가 적용되어 있습니다." else "현재 밝은 라이트 모드가 적용되어 있습니다.",
                btnText = if (isDark) "☀️ 라이트 모드로 전환" else "🌙 다크 모드로 전환",
                btnColor = "#4B5563"
            ) {
                LedgerStorage.setDarkMode(this, !isDark)
                recreate()
            }
        )

        // 5. 글자 크기 조절
        val fontCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        fontCard.addView(TextView(this).apply {
            text = "🔠 글자 크기 조절"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
        })
        fontCard.addView(TextView(this).apply {
            text = "미리보기: 가나다라 12,500원 (원하는 글자 크기를 선택하세요)"
            setTextColor(textSecondary)
            textSize = sp(13f)
            setPadding(0, dp(4), 0, dp(10))
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
            scaleRow.addView(TextView(this).apply {
                text = label
                textSize = sp(12f)
                gravity = Gravity.CENTER
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else textPrimary)
                background = roundBg(if (isSelected) Color.parseColor("#10B981") else chipBg, 8)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(4)
                }
                setOnClickListener {
                    LedgerStorage.setFontScale(this@MainActivity, scaleVal)
                    refreshUI()
                }
            })
        }
        fontCard.addView(scaleRow)
        container.addView(fontCard)

        // 6. 데이터 관리
        val count = LedgerStorage.getRecords(this).length()
        container.addView(
            makeSettingCard(
                title = "🗂️ 가계부 데이터 관리",
                desc = "현재 저장된 거래 내역은 총 ${count}건입니다. 필요 시 전체 내역을 초기화할 수 있습니다.",
                btnText = "🗑️ 전체 거래 내역 초기화",
                btnColor = "#EF4444"
            ) {
                AlertDialog.Builder(this, getDialogTheme(isDark))
                    .setTitle("전체 내역 삭제")
                    .setMessage("저장된 ${count}건의 모든 가계부 내역을 삭제하시겠습니까? 삭제 후 복구할 수 없습니다.")
                    .setPositiveButton("모두 삭제") { _, _ ->
                        LedgerStorage.clearAllRecords(this)
                        refreshUI()
                        Toast.makeText(this, "모든 내역이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        )
    }

    // =========================================================================
    // 공통 UI 빌더 및 카드 렌더링 (+수입 / -지출 / ⇄이체 3색 구분)
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
            background = roundBg(bgCard, 12)
            setPadding(dp(if (compact) 10 else 16), dp(12), dp(if (compact) 10 else 16), dp(12))
            gravity = Gravity.CENTER

            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(textSecondary)
                textSize = sp(if (compact) 11f else 13f)
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
                background = roundBg(bgCard, 10)
                setPadding(dp(if (compact) 10 else 14), dp(10), dp(if (compact) 10 else 14), dp(10))
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
                if (isAuto) append(" 🤖")
                if (imagePath.isNotEmpty() && File(imagePath).exists()) append(" 📷")
            }
            leftCol.addView(TextView(this).apply {
                text = topMeta
                setTextColor(Color.parseColor("#10B981"))
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
                if (fromAcc.isNotEmpty() || toAcc.isNotEmpty()) {
                    append(" · 출금:${fromAcc.ifEmpty { "-" }} → 입금:${toAcc.ifEmpty { "-" }}")
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
        val dateBtn = Button(this).apply {
            text = "📅 날짜선택"
            textSize = sp(12f)
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#4B5563"), 8)
            setOnClickListener {
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
            }
        }
        dateRow.addView(dateText)
        dateRow.addView(dateBtn)
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

        val merchantInput = makeInput("제목 / 결제처", "예: 쿠팡, 농협 → 토뱅이정현")
        val fromInput = makeInput("보낸 곳 / 출금 계좌 (선택)", "예: 농협")
        val toInput = makeInput("받는 곳 / 입금 계좌 (선택)", "예: 토뱅이정현")
        val itemInput = makeInput("상세 품목 및 개별 금액 (선택)", "예: • 아메리카노 (2잔) : 9,000원", multiLine = true)
        val qtyInput = makeInput("총 수량 (선택)", "예: 총 2개")
        val amountInput = makeInput("금액 (원)", "숫자만 입력", isNumber = true)

        scroll.addView(container)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("➕ 내역 직접 추가")
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

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        box.addView(TextView(this).apply {
            text = "이메일 알림이 울릴 때 결제 영수증 본문 전체를 직접 읽어옵니다.\n(구글·네이버 보안 설정에서 발급한 16자리 '앱 비밀번호'를 입력하세요)"
            textSize = sp(12f)
            setTextColor(descColor)
            setPadding(0, 0, 0, dp(10))
        })

        val savedProv = LedgerStorage.getEmailProvider(this)
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
            hint = "이메일 주소 (예: example@gmail.com)"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(LedgerStorage.getEmailAddress(this@MainActivity))
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
            ).apply { bottomMargin = dp(10) }
        }
        box.addView(passEdit)

        val testBtn = Button(this).apply {
            text = "🔄 연결 테스트 및 최근 결제메일 1건 가져오기"
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#2563EB"), 8)
            setOnClickListener {
                val prov = if (providerSpinner.selectedItemPosition == 1) "naver" else "gmail"
                val addr = emailEdit.text.toString().trim()
                val pw = passEdit.text.toString().replace(" ", "").trim()
                if (addr.isEmpty() || pw.isEmpty()) {
                    Toast.makeText(this@MainActivity, "이메일과 앱 비밀번호를 먼저 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                LedgerStorage.saveEmailConfig(this@MainActivity, prov, addr, pw)
                Toast.makeText(this@MainActivity, "메일 서버에 접속 중...", Toast.LENGTH_SHORT).show()
                Thread {
                    val resultMsg = EmailImapFetcher.fetchLatestPaymentEmail(this@MainActivity, lookbackMs = 86_400_000L)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, resultMsg, Toast.LENGTH_LONG).show()
                        refreshUI()
                    }
                }.start()
            }
        }
        box.addView(testBtn)

        scroll.addView(box)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("이메일 본문 직접 연동 설정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val prov = if (providerSpinner.selectedItemPosition == 1) "naver" else "gmail"
                val addr = emailEdit.text.toString().trim()
                val pw = passEdit.text.toString().replace(" ", "").trim()
                LedgerStorage.saveEmailConfig(this, prov, addr, pw)
                refreshUI()
            }
            .setNeutralButton("연동 해제") { _, _ ->
                LedgerStorage.saveEmailConfig(this, "gmail", "", "")
                refreshUI()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // 상세 보기 및 수정 다이얼로그 (보낸 곳 / 받는 곳 / ⇄ 이체 전환 지원)
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
        val changeDateBtn = Button(this).apply {
            text = "📅 날짜변경"
            textSize = sp(12f)
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#4B5563"), 8)
            setOnClickListener {
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
            }
        }
        dateRow.addView(dateText)
        dateRow.addView(changeDateBtn)
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

        val attachBtn = Button(this).apply {
            text = "🖼️ 영수증 / 이미지 첨부하기"
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#10B981"), 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                startActivityForResult(Intent.createChooser(intent, "이미지 선택"), 1001)
            }
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
        val fromEdit = makeLabeledInput("보낸 곳 / 출금 계좌", oldFrom)
        val toEdit = makeLabeledInput("받는 곳 / 입금 계좌", oldTo)
        val itemEdit = makeLabeledInput("상세 품목 및 개별 금액", oldItem, multiLine = true)
        val qtyEdit = makeLabeledInput("총 수량", oldQty)
        val amtEdit = makeLabeledInput("금액 (원)", oldAmt.toString(), isNumber = true)
        val rawEdit = makeLabeledInput("결제 요약 및 원본 전문", oldRaw, multiLine = true)

        val parsedItemsArr = try { JSONArray(itemsJsonStr) } catch (_: Exception) { JSONArray() }
        var dialogRef: AlertDialog? = null

        if (parsedItemsArr.length() >= 2) {
            val splitBtn = Button(this).apply {
                text = "✂️ ${parsedItemsArr.length()}개 품목을 각각 별도 내역으로 분리하기"
                textSize = sp(12f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = roundBg(Color.parseColor("#2563EB"), 8)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnClickListener {
                    LedgerStorage.splitRecordIntoItems(
                        context = this@MainActivity,
                        recordObj = recordObj,
                        itemsArr = parsedItemsArr
                    )
                    dialogRef?.dismiss()
                    refreshUI()
                    Toast.makeText(this@MainActivity, "${parsedItemsArr.length()}건의 개별 내역으로 분리되었습니다.", Toast.LENGTH_SHORT).show()
                }
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
                if ((newFrom != oldFrom || newTo != oldTo) && (newFrom.isNotEmpty() || newTo.isNotEmpty())) {
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
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sp(base: Float): Float = base * LedgerStorage.getFontScale(this)

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }
}

class PaymentNotificationListener : NotificationListenerService() {

    private var lastSignature = ""
    private var lastTime = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return

        // 그룹 요약 중복 알림 무시
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

        val ownKeywords = LedgerStorage.getTransferKeywords(this)
        val parsed = NotificationParser.parse(title, fullText, fullText, ownKeywords)
        if (parsed != null) {
            val sig = "$pkg|${parsed.merchant}|${parsed.amount}"
            val now = System.currentTimeMillis()
            if (sig != lastSignature || now - lastTime >= 5000L) {
                lastSignature = sig
                lastTime = now

                // 문자 앱의 기본 사람 실루엣 아이콘 제외, 실제 첨부 큰 이미지만 추출
                val bmp = extractBitmap(extras, isSmsApp)
                val imagePath = if (bmp != null) LedgerStorage.saveBitmapToFile(this, bmp) else ""

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
                    auto = true
                )
                sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").setPackage(packageName))
            }
        }

        if (isEmailApp && LedgerStorage.getEmailAddress(this).isNotEmpty()) {
            Thread {
                try {
                    Thread.sleep(2000L)
                    EmailImapFetcher.fetchLatestPaymentEmail(this, lookbackMs = 180_000L)
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

        // 문자 앱은 연락처 기본 실루엣 아이콘을 보내므로 제외
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

    fun fetchLatestPaymentEmail(context: Context, lookbackMs: Long): String {
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
                put("mail.imaps.connectiontimeout", "10000")
                put("mail.imaps.timeout", "10000")
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

            val startIdx = maxOf(1, total - 5)
            val messages = inbox.getMessages(startIdx, total)
            val now = System.currentTimeMillis()
            var importedCount = 0
            val ownKeywords = LedgerStorage.getTransferKeywords(context)

            for (i in messages.indices.reversed()) {
                val msg = messages[i]
                val receivedTime = msg.receivedDate?.time ?: msg.sentDate?.time ?: now
                if (now - receivedTime > lookbackMs) continue

                val subject = msg.subject ?: ""
                val bodyText = extractMessageText(msg)
                val fullText = "$subject\n$bodyText".trim()

                val parsed = NotificationParser.parse(subject, fullText, fullText, ownKeywords)
                if (parsed != null) {
                    LedgerStorage.addOrMergeRecord(
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
                        auto = true
                    )
                    importedCount++
                    break
                }
            }

            inbox.close(false)
            store.close()

            if (importedCount > 0) {
                "연동 성공! 결제 메일 본문 전체를 가져왔습니다."
            } else {
                "연동 성공! (최근 범위 내 새 결제 메일은 없습니다)"
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

object NotificationParser {

    val CATEGORIES = listOf(
        "식비", "카페/간식", "쇼핑/생활", "교통/차량", "구독/통신", "의료/건강", "이체/계좌이동", "수입", "기타"
    )

    private val KEYWORDS = listOf("승인", "결제", "출금", "입금", "사용", "취소", "이체", "페이", "카드", "영수증", "KRW", "주문", "송금")

    private val GENERIC_TAGS = listOf("web발신", "국외발신", "국제발신", "광고", "알림", "발신전용")

    private val BANK_NAMES = listOf(
        "NH농협", "농협", "KB국민", "국민", "신한", "우리", "하나", "IBK기업", "기업",
        "카카오뱅크", "토스뱅크", "케이뱅크", "새마을금고", "새마을", "우체국", "수협", "신협",
        "대구", "부산", "경남", "광주", "전북", "제주", "SC제일", "씨티"
    )

    private val PG_OR_GENERIC_NAMES = listOf(
        "메시지", "문자", "신한카드", "삼성카드", "현대카드", "KB국민카드", "롯데카드",
        "우리카드", "하나카드", "NH농협카드", "카카오페이", "네이버페이", "토스", "Gmail",
        "NHN KCP", "KCP", "KG이니시스", "이니시스", "토스페이먼츠", "나이스페이", "다날", "발신전용"
    )

    data class LineItem(
        val name: String,
        val qty: Int,
        val price: Long
    )

    data class ParsedPayment(
        val type: String, // "exp", "inc", "trn"
        val category: String,
        val merchant: String,
        val fromAccount: String,
        val toAccount: String,
        val balanceInfo: String,
        val itemSummary: String,
        val totalQuantity: String,
        val amount: Long,
        val cleanRawText: String,
        val itemsJson: String
    )

    fun containsPaymentKeyword(text: String): Boolean {
        return KEYWORDS.any { text.contains(it) }
    }

    private fun stripBoilerplate(raw: String): String {
        val cutMarkers = listOf(
            "채무 지급보증 서비스",
            "상품의 교환/반품 안내",
            "교환/반품 불가 사유",
            "청약철회의 효력",
            "소비자 피해보상",
            "결제하신 물품에 대한",
            "본 메일은 발신전용",
            "이용약관",
            "개인정보처리방침",
            "고객센터 이용안내",
            "전자금융거래"
        )
        var cutIndex = raw.length
        for (marker in cutMarkers) {
            val idx = raw.indexOf(marker)
            if (idx in 20 until cutIndex) {
                cutIndex = idx
            }
        }
        return raw.substring(0, cutIndex).trim()
    }

    // 중복된 알림 줄 정리
    private fun deduplicateLines(text: String): String {
        val seen = LinkedHashSet<String>()
        for (rawLine in text.split("\n")) {
            val cleaned = rawLine.replace(Regex("""^[0-9\-]{8,14}\s*[:：]\s*"""), "").trim()
            if (cleaned.isNotEmpty()) {
                seen.add(cleaned)
            }
        }
        return seen.joinToString("\n")
    }

    fun parse(title: String, body: String, fullText: String, ownKeywords: List<String> = emptyList()): ParsedPayment? {
        val deduped = deduplicateLines(fullText)
        val cleanBody = stripBoilerplate(deduped)
        if (!containsPaymentKeyword(cleanBody)) return null

        // 1. 금액 추출 (잔액 숫자 제외하고 실제 거래 금액 우선 추출)
        val withoutBalance = cleanBody.replace(Regex("""잔액\s*[:：]?\s*[0-9,]+\s*원?"""), "")
        val totalAmtRegex = Regex(
            """(?:총\s*결제\s*금액|실\s*결제\s*금액|최종\s*결제\s*금액|총\s*주문\s*금액|결제\s*금액|승인\s*금액|출금\s*금액|입금\s*금액|이체\s*금액|합계\s*금액)\s*[:：]?\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원"""
        )
        val bankTxnAmtRegex = Regex(
            """(?:출금|입금|이체|송금)\s*[:：]?\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원"""
        )
        val cardAmtRegex = Regex(
            """(?:카드|일시불|할부|페이)\s*(?:\([^)]*\))?\s*[:：/]\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원"""
        )
        val fallbackAmtRegex = Regex("""([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원|KRW\s*([0-9,]+)""")

        val rawNum = totalAmtRegex.find(withoutBalance)?.groupValues?.get(1)
            ?: bankTxnAmtRegex.find(withoutBalance)?.groupValues?.get(1)
            ?: cardAmtRegex.find(withoutBalance)?.groupValues?.get(1)
            ?: fallbackAmtRegex.find(withoutBalance)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?: return null

        val amount = rawNum.replace(",", "").toLongOrNull() ?: return null
        if (amount <= 0L) return null

        // 잔액 추출
        val balanceMatch = Regex("""잔액\s*[:：]?\s*([0-9,]+)\s*원?""").find(cleanBody)
        val balanceInfo = if (balanceMatch != null) "잔액 ${balanceMatch.groupValues[1]}원" else ""

        // 2. 은행 계좌 이동(출금처 -> 입금처) 분석
        var fromAccount = ""
        var toAccount = ""
        val detectedBank = BANK_NAMES.firstOrNull { cleanBody.contains(it) } ?: ""
        val isCardApproval = cleanBody.contains("승인") || cleanBody.contains("카드") || cleanBody.contains("결제")

        if (detectedBank.isNotEmpty() && !isCardApproval) {
            // 계좌번호 뒤 ~ 잔액 앞의 상대방/입금계좌 추출 (예: 352-****-8134-23 토뱅이정현 잔액459,655원)
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
        }

        // 3. 거래 유형 판별: 이체("trn") vs 수입("inc") vs 지출("exp")
        val sanitizedForType = cleanBody
            .replace("부분취소카드", "")
            .replace(Regex("""할인금액\s*[:：]\s*-[0-9,]+원"""), "")

        val matchesOwnAccount = ownKeywords.any { kw ->
            kw.isNotBlank() && (
                toAccount.contains(kw, ignoreCase = true) ||
                fromAccount.contains(kw, ignoreCase = true) ||
                (!isCardApproval && detectedBank.isNotEmpty() && cleanBody.contains(kw, ignoreCase = true))
            )
        }

        val isInternalTransfer = !isCardApproval &&
            detectedBank.isNotEmpty() &&
            (cleanBody.contains("출금") || cleanBody.contains("입금") || cleanBody.contains("이체") || cleanBody.contains("송금")) &&
            matchesOwnAccount

        val isIncome = !isInternalTransfer && (
            sanitizedForType.contains("결제취소") ||
            sanitizedForType.contains("승인취소") ||
            sanitizedForType.contains("주문취소") ||
            sanitizedForType.contains("취소완료") ||
            sanitizedForType.contains("환불완료") ||
            sanitizedForType.contains("환불금액") ||
            Regex("""(?:입금|급여|용돈|이체입금|계좌입금)\s*(?:완료|금액|[:：]|[0-9,]+원)""").containsMatchIn(sanitizedForType)
        )

        val type = when {
            isInternalTransfer -> "trn"
            isIncome -> "inc"
            else -> "exp"
        }

        // 4. 결제처 / 이동 경로 제목 추출 (1588-2100 같은 대표전화번호 제외)
        val bracketBrand = Regex("""\[([^\]]+)]""").findAll(title)
            .map { it.groupValues[1].trim() }
            .firstOrNull { tag -> GENERIC_TAGS.none { tag.contains(it, ignoreCase = true) } } ?: ""

        val labeledMerchantRegex = Regex("""(?:구매상점명|가맹점명|상점명|사용처|결제처)\s*[:：]\s*([^\n\r]+)""")
        val labeledMerchant = labeledMerchantRegex.find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val orderedAtMerchant = Regex("""([가-힣a-zA-Z0-9()]{2,15})에서\s*(?:주문하신|결제하신)""")
            .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val cleanTitle = title.replace(Regex("""\[.*?]"""), "").trim()
        val isPhoneNumberTitle = cleanTitle.matches(Regex("""^[0-9\-]{7,15}$"""))
        val isGenericTitle = cleanTitle.isEmpty() ||
            isPhoneNumberTitle ||
            cleanTitle.contains("주문하신 내역") ||
            cleanTitle.contains("결제 내역입니다") ||
            PG_OR_GENERIC_NAMES.any { cleanTitle.contains(it, ignoreCase = true) }

        val transferRouteTitle = if (fromAccount.isNotEmpty() || toAccount.isNotEmpty()) {
            when {
                fromAccount.isNotEmpty() && toAccount.isNotEmpty() -> "$fromAccount → $toAccount"
                fromAccount.isNotEmpty() -> "$fromAccount 출금"
                else -> "$toAccount 입금"
            }
        } else ""

        val merchant = when {
            transferRouteTitle.isNotEmpty() -> transferRouteTitle
            labeledMerchant.isNotEmpty() -> labeledMerchant
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

        // 5. 상세 품목별 이름 · 수량 · 개별 결제금액 추출
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
            val totalCountMatch = Regex("""(총\s*[0-9]+\s*(?:건|개|종))""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val singleQtyMatch = Regex("""([0-9]+\s*(?:개|잔|건|박스|병|팩|매|세트|봉|통|권))(?!입)""")
                .find(if (labeledItem.isNotEmpty()) labeledItem else cleanBody)?.groupValues?.get(1)?.trim() ?: ""

            itemSummary = labeledItem
            totalQuantity = totalCountMatch.ifEmpty { singleQtyMatch }
        }

        val discountText = Regex("""할인금액\s*[:：]\s*([^\n\r]+)""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
        val shippingText = Regex("""배송비\s*[:：]\s*([^\n\r]+)""").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val formattedRaw = buildString {
            append("[거래 요약]\n")
            if (fromAccount.isNotEmpty() || toAccount.isNotEmpty()) {
                append("• 이동 경로: ${fromAccount.ifEmpty { "-" }} → ${toAccount.ifEmpty { "-" }}\n")
            } else {
                append("• 결제처: $merchant\n")
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

        val category = when (type) {
            "trn" -> "이체/계좌이동"
            "inc" -> "수입"
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
            itemsJson = itemsJsonArr.toString()
        )
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val result = mutableListOf<LineItem>()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val tableRowRegex = Regex(
            """^(.+?)\s*:\s*([0-9,]+)원\s*:\s*([0-9]+)\s*:\s*([0-9,]+)원(?:\s*:.*)?$"""
        )
        val simpleRowRegex = Regex("""^(.+?)\s*:\s*([0-9,]+)원$""")
        val skipLabels = listOf(
            "상품 가격", "상품가격", "할인금액", "할인 금액", "배송비", "총 결제금액",
            "결제금액", "실결제금액", "일시불", "할부", "카드", "승인금액", "포인트", "쿠폰", "잔액"
        )

        for (line in lines) {
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
                val pricePart = mB.groupValues[2].replace(",", "").toLongOrNull() ?: 0L
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
            }
        }
        return result
    }

    private fun classifyCategory(text: String): String {
        val rules = listOf(
            "카페/간식" to listOf("스타벅스", "투썸", "메가커피", "컴포즈", "빽다방", "이디야", "할리스", "폴바셋", "카페", "커피", "베이커리", "파리바게뜨", "뚜레쥬르", "베스킨", "하겐다즈", "아이스크림", "디저트"),
            "식비" to listOf("배달의민족", "배민", "쿠팡이츠", "요기요", "맥도날드", "버거킹", "롯데리아", "식당", "김밥", "국밥", "치킨", "피자", "족발", "보쌈", "횟집", "고기", "한식", "중식", "일식", "분식", "편의점", "GS25", "CU", "세븐일레븐"),
            "교통/차량" to listOf("택시", "카카오T", "티머니", "버스", "지하철", "코레일", "SRT", "KTX", "대한항공", "아시아나", "주유", "GS칼텍스", "SK에너지", "S-OIL", "하이패스", "주차"),
            "쇼핑/생활" to listOf("쿠팡", "네이버쇼핑", "11번가", "G마켓", "옥션", "SSG", "이마트", "홈플러스", "롯데마트", "다이소", "올리브영", "무신사", "마트", "마켓컬리"),
            "구독/통신" to listOf("SKT", "KT", "LGU+", "통신요금", "넷플릭스", "유튜브", "티빙", "웨이브", "디즈니", "구글", "애플", "멤버십", "와우"),
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
    private const val KEY_TRN_KEYWORDS = "transfer_keywords"
    private const val DEFAULT_TRN_KEYWORDS = "이정현, 토뱅, 토스뱅크, 카뱅, 카카오뱅크, 내계좌, 본인, 파킹, CMA, 적금"

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, false)
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

    fun getTransferKeywordsRaw(context: Context): String {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TRN_KEYWORDS, DEFAULT_TRN_KEYWORDS) ?: DEFAULT_TRN_KEYWORDS
    }

    fun setTransferKeywordsRaw(context: Context, raw: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_TRN_KEYWORDS, raw.trim()).apply()
    }

    fun getTransferKeywords(context: Context): List<String> {
        return getTransferKeywordsRaw(context)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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
        val oldArr = getRecords(context)
        val newArr = JSONArray()

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
        newArr.put(record)
        for (i in 0 until oldArr.length()) {
            newArr.put(oldArr.getJSONObject(i))
        }
        save(context, newArr)
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
        auto: Boolean
    ) {
        val oldArr = getRecords(context)
        val now = System.currentTimeMillis()

        if (auto) {
            for (i in 0 until oldArr.length()) {
                val existing = oldArr.getJSONObject(i)
                if (!existing.optBoolean("auto", false)) continue
                val exTime = existing.optLong("timestamp", 0L)
                val exAmt = existing.optLong("amount", 0L)
                if (exAmt == amount && (now - exTime) in 0..180_000L) {
                    val exRaw = existing.optString("raw", "")
                    if (raw.length >= exRaw.length) {
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
                    save(context, oldArr)
                    return
                }
            }
        }

        val newArr = JSONArray()
        val dateStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(now))

        val record = JSONObject().apply {
            put("id", now.toString())
            put("timestamp", now)
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
        newArr.put(record)
        for (i in 0 until oldArr.length()) {
            newArr.put(oldArr.getJSONObject(i))
        }
        save(context, newArr)
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
        save(context, newArr)
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
        save(context, arr)
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
        save(context, newArr)
    }

    fun clearAllRecords(context: Context) {
        save(context, JSONArray())
    }

    private fun save(context: Context, arr: JSONArray) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }
}
