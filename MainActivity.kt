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

    // 원형 그래프에 포함할 카테고리 선택 집합
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

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 담백한 상단 헤더 (배너 색 없이 배경과 자연스럽게 통합)
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

        // 담백한 무채색 차콜 / 라이트 팔레트
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

    // 하단 내비게이션 바 (배경 박스 없이 아이콘·텍스트 색상만으로 담백하게 구분)
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

    // 담백한 ON/OFF 토글 스위치 위젯
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

    // 담백한 무채색 알약(Pill) 버튼
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

        // 담백한 무채색 알약 액션 바 (검색 / + 내역 추가)
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
    // 2. [분석] 탭 (좌측 카테고리 목록 + 우측 원형 그래프(카테고리 선택) + 목록)
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

        // 왼쪽 카테고리 목록
        val leftScroll = ScrollView(this).apply {
            setBackgroundColor(bgSidebar)
            layoutParams = LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.MATCH_PARENT)
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
                setTextColor(if (isSelected) Color.parseColor("#FDE8E8") else textSecondary)
                setPadding(0, dp(2), 0, 0)
            })

            leftList.addView(catItem)
        }
        leftScroll.addView(leftList)
        splitRoot.addView(leftScroll)

        // 오른쪽 통계 및 내역
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

        // 원형 그래프(도넛 차트) 카드
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

            // 원하는 카테고리를 골라서 원형 그래프로 볼 수 있는 선택 바
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

        // 원형 그래프 뷰 배치
        val pieView = PieChartView(this, slices, bgCard, textPrimary, textSecondary).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(190)
            ).apply { bottomMargin = dp(8) }
        }
        pieCard.addView(pieView)

        // 원형 그래프 범례 (색상 점 + 이름 + 비율% + 금액)
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
    // 3. [설정] 탭 (담백한 단일 카드 그룹 + 토글/알약 버튼 + 연결된 이메일 표시 + 자동 규칙)
    // =========================================================================
    private fun renderSettingsTab(isDark: Boolean, container: LinearLayout) {
        val bgCard = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val bgPill = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        val textPrimary = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#98989D") else Color.parseColor("#6B7280")

        // 통합 설정 카드 (시안처럼 1개의 깔끔한 다크 카드 안에 구분선으로 항목 배치)
        val groupCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 18)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1) 결제 알림 자동 감지 (토글 스위치)
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
            text = if (granted) "문자·카톡·카드앱 알림 자동 기록 중" else "꺼짐 (스위치를 눌러 권한 허용)"
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

        // 2) 연결된 이메일 확인 및 설정
        val savedEmail = LedgerStorage.getEmailAddress(this)
        val savedProv = if (LedgerStorage.getEmailProvider(this) == "naver") "네이버 메일" else "Gmail"
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
                "● 연결됨: $savedProv ($savedEmail)"
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

        // 3) 특정 단어 자동 인식 규칙 (자동 삭제 / 통장간 이동 / 특정 카테고리 자동 분류)
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

            // 기존 내역에도 규칙 일괄 적용 알약 버튼
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

        // 4) 다크 모드 (토글 스위치)
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

        // 5) 글자 크기 조절 (시안의 알약 버튼 스타일)
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

        // 6) 데이터 관리
        val count = LedgerStorage.getRecords(this).length()
        val rowData = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dataTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(10)
            }
        }
        dataTextCol.addView(TextView(this).apply {
            text = "🗑️  데이터 관리"
            setTextColor(textPrimary)
            textSize = sp(15f)
            setTypeface(null, Typeface.BOLD)
        })
        dataTextCol.addView(TextView(this).apply {
            text = "저장된 거래 내역 총 ${count}건"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setPadding(0, dp(3), 0, 0)
        })
        rowData.addView(dataTextCol)
        rowData.addView(makePillButton(isDark, "내역 초기화") {
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
        })
        groupCard.addView(rowData)

        container.addView(groupCard)
    }

    // 단어 자동 규칙 추가 팝업
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

        val merchantInput = makeInput("제목 / 결제처", "예: 쿠팡, 농협 → 토뱅이정현")
        val fromInput = makeInput("보낸 곳 / 출금 계좌 (선택)", "예: 농협")
        val toInput = makeInput("받는 곳 / 입금 계좌 (선택)", "예: 토뱅이정현")
        val itemInput = makeInput("상세 품목 및 개별 금액 (선택)", "예: • 아메리카노 (2잔) : 9,000원", multiLine = true)
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

    // 이메일 연동 설정 및 연결된 이메일 확인 다이얼로그
    private fun showEmailConfigDialog() {
        val isDark = LedgerStorage.isDarkMode(this)
        val descColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")
        val statusBoxBg = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        val savedProv = LedgerStorage.getEmailProvider(this)
        val savedAddr = LedgerStorage.getEmailAddress(this)
        val provLabel = if (savedProv == "naver") "네이버 메일" else "Gmail"

        // 현재 연결된 이메일 상태를 한눈에 보여주는 배너
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

        box.addView(TextView(this).apply {
            text = "이메일 알림이 울릴 때 영수증 본문 전체를 직접 읽어옵니다.\n(구글·네이버 보안 설정에서 발급한 16자리 '앱 비밀번호'를 입력하세요)"
            textSize = sp(12f)
            setTextColor(descColor)
            setPadding(0, 0, 0, dp(10))
        })

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

        val testBtn = makePillButton(isDark, "연결 테스트 및 최근 결제메일 가져오기", isActive = true) {
            val prov = if (providerSpinner.selectedItemPosition == 1) "naver" else "gmail"
            val addr = emailEdit.text.toString().trim()
            val pw = passEdit.text.toString().replace(" ", "").trim()
            if (addr.isEmpty() || pw.isEmpty()) {
                Toast.makeText(this@MainActivity, "이메일과 앱 비밀번호를 먼저 입력하세요.", Toast.LENGTH_SHORT).show()
                return@makePillButton
            }
            LedgerStorage.saveEmailConfig(this@MainActivity, prov, addr, pw)
            val pName = if (prov == "naver") "네이버 메일" else "Gmail"
            statusBanner.text = "● 현재 연결된 이메일: $pName ($addr)"
            statusBanner.setTextColor(Color.parseColor("#34D399"))
            Toast.makeText(this@MainActivity, "메일 서버에 접속 중...", Toast.LENGTH_SHORT).show()
            Thread {
                val resultMsg = EmailImapFetcher.fetchLatestPaymentEmail(this@MainActivity, lookbackMs = 86_400_000L)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, resultMsg, Toast.LENGTH_LONG).show()
                    refreshUI()
                }
            }.start()
        }
        box.addView(testBtn)

        scroll.addView(box)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("이메일 본문 직접 연동")
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
        val oldAmt = recordObj.optLong("
