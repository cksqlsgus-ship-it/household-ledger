package com.myledger.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var emailBtn: Button
    private lateinit var themeBtn: Button
    private lateinit var statusBtn: Button
    private lateinit var smsPermBtn: Button

    private lateinit var summaryCard: LinearLayout
    private lateinit var summaryLabel: TextView
    private lateinit var balanceText: TextView
    private lateinit var incText: TextView
    private lateinit var expText: TextView

    private lateinit var searchCard: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var historyRow: LinearLayout
    private lateinit var categoryRow: LinearLayout

    private lateinit var toggleAddBtn: Button
    private lateinit var inputCard: LinearLayout
    private lateinit var listHeaderText: TextView
    private lateinit var listContainer: LinearLayout

    private var selectedCategory = "전체"
    private var currentQuery = ""
    private var isAddFormVisible = false

    private var pendingImagePath: String = ""
    private var dialogImageView: ImageView? = null

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. 상단 헤더
        headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "💰 자동 가계부"
            setTextColor(Color.WHITE)
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleView)

        emailBtn = Button(this).apply {
            text = "📧 메일연동"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) }
            setOnClickListener { showEmailConfigDialog() }
        }
        topBar.addView(emailBtn)

        themeBtn = Button(this).apply {
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                val nextDark = !LedgerStorage.isDarkMode(this@MainActivity)
                LedgerStorage.setDarkMode(this@MainActivity, nextDark)
                refreshUI()
            }
        }
        topBar.addView(themeBtn)
        headerLayout.addView(topBar)

        statusBtn = Button(this).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        headerLayout.addView(statusBtn)

        smsPermBtn = Button(this).apply {
            text = "📩 문자함 원본 읽기 권한 허용하기 (눌러서 허용)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#92400E"))
            background = roundBg(Color.parseColor("#FEF3C7"), 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.READ_SMS), 2001)
            }
        }
        headerLayout.addView(smsPermBtn)

        rootLayout.addView(headerLayout)

        // 2. 스크롤 본문
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(28))
        }

        // 3. 요약 카드 (+ / - 기호만 표시)
        summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER
        }
        summaryLabel = TextView(this).apply {
            text = "전체 잔액"
            textSize = 13f
            gravity = Gravity.CENTER
        }
        summaryCard.addView(summaryLabel)

        balanceText = TextView(this).apply {
            text = "0원"
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        summaryCard.addView(balanceText)

        val incExpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        incText = TextView(this).apply {
            text = "+0원"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), 0, dp(16), 0)
        }
        expText = TextView(this).apply {
            text = "-0원"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), 0, dp(16), 0)
        }
        incExpRow.addView(incText)
        incExpRow.addView(expText)
        summaryCard.addView(incExpRow)
        content.addView(summaryCard)

        // 4. 검색 및 검색 기록 카드
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        val searchBoxRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(this).apply {
            hint = "🔍 결제처, 품목, 내용 검색..."
            textSize = 14f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val searchBtn = Button(this).apply {
            text = "검색"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#10B981"), 8)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(38)).apply { leftMargin = dp(6) }
            setOnClickListener {
                val q = searchInput.text.toString().trim()
                currentQuery = q
                if (q.isNotEmpty()) {
                    LedgerStorage.addSearchHistory(this@MainActivity, q)
                }
                refreshUI()
            }
        }
        val clearSearchBtn = Button(this).apply {
            text = "초기화"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            background = roundBg(Color.parseColor("#E5E7EB"), 8)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(38)).apply { leftMargin = dp(4) }
            setOnClickListener {
                searchInput.setText("")
                currentQuery = ""
                refreshUI()
            }
        }
        searchBoxRow.addView(searchInput)
        searchBoxRow.addView(searchBtn)
        searchBoxRow.addView(clearSearchBtn)
        searchCard.addView(searchBoxRow)

        val historyScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historyScroll.addView(historyRow)
        searchCard.addView(historyScroll)
        content.addView(searchCard)

        // 5. 카테고리 필터 가로 바
        val catScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        catScroll.addView(categoryRow)
        content.addView(catScroll)

        // 6. 직접 내역 추가 (접기/펼치기)
        toggleAddBtn = Button(this).apply {
            text = "➕ 직접 내역 추가하기"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener {
                isAddFormVisible = !isAddFormVisible
                inputCard.visibility = if (isAddFormVisible) View.VISIBLE else View.GONE
                text = if (isAddFormVisible) "▲ 직접 입력 창 닫기" else "➕ 직접 내역 추가하기"
            }
        }
        content.addView(toggleAddBtn)

        inputCard = buildManualInputCard()
        inputCard.visibility = View.GONE
        content.addView(inputCard)

        // 7. 내역 리스트 헤더 및 목록
        listHeaderText = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(12), dp(4), dp(6))
        }
        content.addView(listHeaderText)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer)

        scroll.addView(content)
        rootLayout.addView(scroll)
        setContentView(rootLayout)
    }

    private fun buildManualInputCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("-", "+")
            )
        }
        val catSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                NotificationParser.CATEGORIES
            )
        }
        row1.addView(typeSpinner)
        row1.addView(catSpinner)
        card.addView(row1)

        val merchantInput = EditText(this).apply {
            hint = "결제처 / 제목 (예: 쿠팡)"
            textSize = 14f
        }
        card.addView(merchantInput)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val itemInput = EditText(this).apply {
            hint = "품목 (예: 아메리카노)"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
        }
        val qtyInput = EditText(this).apply {
            hint = "수량 (예: 2잔)"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row2.addView(itemInput)
        row2.addView(qtyInput)
        card.addView(row2)

        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val amountInput = EditText(this).apply {
            hint = "금액 (숫자만 입력)"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = Button(this).apply {
            text = "저장"
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#10B981"), 8)
            setOnClickListener {
                val merchant = merchantInput.text.toString().trim()
                val amt = amountInput.text.toString().trim().toLongOrNull() ?: 0L
                if (merchant.isNotEmpty() && amt > 0) {
                    val type = if (typeSpinner.selectedItemPosition == 1) "inc" else "exp"
                    val cat = catSpinner.selectedItem.toString()
                    val item = itemInput.text.toString().trim()
                    val qty = qtyInput.text.toString().trim()

                    LedgerStorage.addOrMergeRecord(
                        context = this@MainActivity,
                        type = type,
                        category = cat,
                        merchant = merchant,
                        item = item,
                        quantity = qty,
                        amount = amt,
                        raw = "직접 입력 내역",
                        imagePath = "",
                        auto = false
                    )
                    merchantInput.setText("")
                    itemInput.setText("")
                    qtyInput.setText("")
                    amountInput.setText("")
                    refreshUI()
                }
            }
        }
        row3.addView(amountInput)
        row3.addView(addBtn)
        card.addView(row3)

        return card
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUI()
    }

    private fun isNotificationGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun isSmsGranted(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshUI() {
        val isDark = LedgerStorage.isDarkMode(this)

        val bgPage = if (isDark) Color.parseColor("#111827") else Color.parseColor("#F3F4F6")
        val bgHeader = if (isDark) Color.parseColor("#065F46") else Color.parseColor("#10B981")
        val bgCard = if (isDark) Color.parseColor("#1F2937") else Color.WHITE
        val bgSubCard = if (isDark) Color.parseColor("#374151") else Color.parseColor("#F3F4F6")
        val textPrimary = if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280")
        val incColor = if (isDark) Color.parseColor("#60A5FA") else Color.parseColor("#2563EB")
        val expColor = if (isDark) Color.parseColor("#F87171") else Color.parseColor("#DC2626")

        rootLayout.setBackgroundColor(bgPage)
        headerLayout.setBackgroundColor(bgHeader)

        val savedEmail = LedgerStorage.getEmailAddress(this)
        emailBtn.text = if (savedEmail.isNotEmpty()) "📧 메일연동됨" else "📧 메일연동"
        emailBtn.setTextColor(if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#065F46"))
        emailBtn.background = roundBg(if (isDark) Color.parseColor("#374151") else Color.parseColor("#D1FAE5"), 16)

        themeBtn.text = if (isDark) "☀️ 라이트" else "🌙 다크"
        themeBtn.setTextColor(if (isDark) Color.parseColor("#F9FAFB") else Color.parseColor("#065F46"))
        themeBtn.background = roundBg(if (isDark) Color.parseColor("#374151") else Color.parseColor("#D1FAE5"), 16)

        if (isNotificationGranted()) {
            statusBtn.text = "✅ 자동 알림 감지 켜짐 (설정)"
            statusBtn.setTextColor(if (isDark) Color.parseColor("#A7F3D0") else Color.parseColor("#065F46"))
            statusBtn.background = roundBg(if (isDark) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5"), 8)
        } else {
            statusBtn.text = "⚠️ 알림 접근 권한 꺼짐 (눌러서 켜기)"
            statusBtn.setTextColor(if (isDark) Color.parseColor("#FECACA") else Color.parseColor("#991B1B"))
            statusBtn.background = roundBg(if (isDark) Color.parseColor("#7F1D1D") else Color.parseColor("#FEE2E2"), 8)
        }

        smsPermBtn.visibility = if (isSmsGranted()) View.GONE else View.VISIBLE

        summaryCard.background = roundBg(bgCard, 12)
        summaryLabel.setTextColor(textSecondary)
        balanceText.setTextColor(textPrimary)
        incText.setTextColor(incColor)
        expText.setTextColor(expColor)

        searchCard.background = roundBg(bgCard, 12)
        searchInput.setTextColor(textPrimary)
        searchInput.setHintTextColor(textSecondary)

        toggleAddBtn.background = roundBg(bgCard, 10)
        toggleAddBtn.setTextColor(textPrimary)
        inputCard.background = roundBg(bgCard, 12)
        styleInputsInGroup(inputCard, textPrimary, textSecondary)

        listHeaderText.setTextColor(textSecondary)

        renderSearchHistory(bgSubCard, textSecondary)
        renderCategoryChips(bgCard, textPrimary)

        val allRecords = LedgerStorage.getRecords(this)
        listContainer.removeAllViews()

        var totalInc = 0L
        var totalExp = 0L
        var visibleCount = 0
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        val queryLower = currentQuery.lowercase(Locale.KOREA)

        for (i in 0 until allRecords.length()) {
            val obj = allRecords.getJSONObject(i)
            val type = obj.optString("type", "exp")
            val category = obj.optString("category", "기타")
            val merchant = obj.optString("merchant", obj.optString("desc", ""))
            val item = obj.optString("item", "")
            val quantity = obj.optString("quantity", "")
            val amount = obj.optLong("amount", 0L)
            val date = obj.optString("date", "")
            val raw = obj.optString("raw", "")
            val imagePath = obj.optString("imagePath", "")
            val isAuto = obj.optBoolean("auto", false)

            if (selectedCategory != "전체" && category != selectedCategory) continue

            if (queryLower.isNotEmpty()) {
                val searchable = "$merchant $item $category $raw $date".lowercase(Locale.KOREA)
                if (!searchable.contains(queryLower)) continue
            }

            visibleCount++
            if (type == "inc") totalInc += amount else totalExp += amount

            val sign = if (type == "inc") "+" else "-"
            val entryColor = if (type == "inc") incColor else expColor

            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundBg(bgCard, 10)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    showEditDialog(obj)
                }
            }

            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val topMeta = buildString {
                append("[$category]")
                if (isAuto) append(" 🤖")
                if (imagePath.isNotEmpty() && File(imagePath).exists()) append(" 📷")
            }
            leftCol.addView(TextView(this).apply {
                text = topMeta
                setTextColor(Color.parseColor("#10B981"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
            })

            // 제목(결제처)에 수입/지출 색상 적용
            leftCol.addView(TextView(this).apply {
                text = merchant
                setTextColor(entryColor)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(2))
            })

            val subInfo = buildString {
                append(date)
                if (item.isNotEmpty()) append(" · $item")
                if (quantity.isNotEmpty()) append(" ($quantity)")
            }
            leftCol.addView(TextView(this).apply {
                text = subInfo
                setTextColor(textSecondary)
                textSize = 12f
            })
            itemCard.addView(leftCol)

            // 금액에 + / - 및 수입/지출 색상 적용
            itemCard.addView(TextView(this).apply {
                text = "$sign${nf.format(amount)}원"
                setTextColor(entryColor)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), 0, 0, 0)
            })

            listContainer.addView(itemCard)
        }

        summaryLabel.text = if (selectedCategory == "전체" && currentQuery.isEmpty()) {
            "전체 잔액"
        } else {
            "필터 합계 ($selectedCategory)"
        }
        balanceText.text = "${nf.format(totalInc - totalExp)}원"
        incText.text = "+${nf.format(totalInc)}원"
        expText.text = "-${nf.format(totalExp)}원"
        listHeaderText.text = "내역 ${visibleCount}건 (항목을 눌러 전체 내용 보기 및 수정)"

        if (visibleCount == 0) {
            listContainer.addView(TextView(this).apply {
                text = "표시할 내역이 없습니다."
                setTextColor(textSecondary)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        }
    }

    private fun styleInputsInGroup(group: ViewGroup, textColor: Int, hintColor: Int) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is EditText) {
                child.setTextColor(textColor)
                child.setHintTextColor(hintColor)
            } else if (child is ViewGroup) {
                styleInputsInGroup(child, textColor, hintColor)
            }
        }
    }

    private fun renderSearchHistory(chipBg: Int, textColor: Int) {
        historyRow.removeAllViews()
        val history = LedgerStorage.getSearchHistory(this)
        if (history.isEmpty()) return

        historyRow.addView(TextView(this).apply {
            text = "최근:"
            setTextColor(textColor)
            textSize = 11f
            setPadding(0, 0, dp(6), 0)
        })

        for (word in history) {
            val chip = TextView(this).apply {
                text = word
                setTextColor(textColor)
                textSize = 12f
                background = roundBg(chipBg, 12)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
                setOnClickListener {
                    searchInput.setText(word)
                    currentQuery = word
                    refreshUI()
                }
            }
            historyRow.addView(chip)
        }

        val clearHist = TextView(this).apply {
            text = "기록삭제"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 11f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener {
                LedgerStorage.clearSearchHistory(this@MainActivity)
                refreshUI()
            }
        }
        historyRow.addView(clearHist)
    }

    private fun renderCategoryChips(cardBg: Int, textColor: Int) {
        categoryRow.removeAllViews()
        val allCats = listOf("전체") + NotificationParser.CATEGORIES

        for (cat in allCats) {
            val isSelected = (cat == selectedCategory)
            val chip = TextView(this).apply {
                text = cat
                textSize = 13f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else textColor)
                background = roundBg(if (isSelected) Color.parseColor("#10B981") else cardBg, 16)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(6) }
                setOnClickListener {
                    selectedCategory = cat
                    refreshUI()
                }
            }
            categoryRow.addView(chip)
        }
    }

    // 이메일 IMAP 직접 연동 설정 다이얼로그
    private fun showEmailConfigDialog() {
        val isDark = LedgerStorage.isDarkMode(this)
        val themeId = if (isDark) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        box.addView(TextView(this).apply {
            text = "이메일 알림이 울릴 때 결제 영수증 본문 전체를 직접 읽어옵니다.\n(구글·네이버 보안 설정에서 발급한 16자리 '앱 비밀번호'를 입력하세요)"
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        val providerSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("Gmail (imap.gmail.com)", "네이버 메일 (imap.naver.com)")
            )
            val savedProv = LedgerStorage.getEmailProvider(this@MainActivity)
            setSelection(if (savedProv == "naver") 1 else 0)
        }
        box.addView(providerSpinner)

        val emailEdit = EditText(this).apply {
            hint = "이메일 주소 (예: example@gmail.com)"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(LedgerStorage.getEmailAddress(this@MainActivity))
            textSize = 14f
        }
        box.addView(emailEdit)

        val passEdit = EditText(this).apply {
            hint = "앱 비밀번호 (16자리, 띄어쓰기 없이 입력)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(LedgerStorage.getEmailPassword(this@MainActivity))
            textSize = 14f
        }
        box.addView(passEdit)

        val testBtn = Button(this).apply {
            text = "🔄 연결 테스트 및 최근 결제메일 1건 가져오기"
            textSize = 12f
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

        AlertDialog.Builder(this, themeId)
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

    // 상세 보기 및 수정 다이얼로그 (줄 수 제한 완전 해제)
    private fun showEditDialog(recordObj: JSONObject) {
        val isDark = LedgerStorage.isDarkMode(this)
        val themeId = if (isDark) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }

        val recordId = recordObj.optString("id", "")
        val oldType = recordObj.optString("type", "exp")
        val oldCat = recordObj.optString("category", "기타")
        val oldMerchant = recordObj.optString("merchant", recordObj.optString("desc", ""))
        val oldItem = recordObj.optString("item", "")
        val oldQty = recordObj.optString("quantity", "")
        val oldAmt = recordObj.optLong("amount", 0L)
        val oldRaw = recordObj.optString("raw", "")
        val oldDate = recordObj.optString("date", "")
        pendingImagePath = recordObj.optString("imagePath", "")

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        container.addView(TextView(this).apply {
            text = "기록 일시: $oldDate"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

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
            textSize = 12f
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                startActivityForResult(Intent.createChooser(intent, "이미지 선택"), 1001)
            }
        }
        container.addView(attachBtn)

        // + / - 선택 스피너
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("-", "+")
            )
            setSelection(if (oldType == "inc") 1 else 0)
        }
        container.addView(typeSpinner)

        val catSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                NotificationParser.CATEGORIES
            )
            val idx = NotificationParser.CATEGORIES.indexOf(oldCat)
            if (idx >= 0) setSelection(idx)
        }
        container.addView(catSpinner)

        fun makeLabeledInput(label: String, initial: String, isNumber: Boolean = false, multiLine: Boolean = false): EditText {
            container.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(2))
            })
            return EditText(this).apply {
                setText(initial)
                textSize = 14f
                if (isNumber) inputType = InputType.TYPE_CLASS_NUMBER
                if (multiLine) {
                    minLines = 3
                    isSingleLine = false
                }
                container.addView(this)
            }
        }

        val merchantEdit = makeLabeledInput("제목 / 결제처", oldMerchant)
        val itemEdit = makeLabeledInput("품목", oldItem)
        val qtyEdit = makeLabeledInput("수량", oldQty)
        val amtEdit = makeLabeledInput("금액 (원)", oldAmt.toString(), isNumber = true)
        val rawEdit = makeLabeledInput("상세 내용 / 원본 전문 (전체 표시)", oldRaw, multiLine = true)

        scroll.addView(container)

        AlertDialog.Builder(this, themeId)
            .setTitle("내역 상세 및 수정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val newType = if (typeSpinner.selectedItemPosition == 1) "inc" else "exp"
                val newCat = catSpinner.selectedItem.toString()
                val newMerchant = merchantEdit.text.toString().trim().ifEmpty { oldMerchant }
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
                    item = newItem,
                    quantity = newQty,
                    amount = newAmt,
                    raw = newRaw,
                    imagePath = pendingImagePath
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

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        // 여러 줄 텍스트 및 카카오톡 묶음 메시지(EXTRA_MESSAGES)까지 모두 추출
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

        // 1. 문자 앱 알림인 경우: 문자함에서 잘리지 않은 원본 문자(LMS 포함) 직접 읽기
        if (isSmsApp) {
            val smsFullBody = readLatestInboxSms(title, combinedBody)
            if (smsFullBody.isNotEmpty()) {
                fullText = "$title\n$smsFullBody"
            }
        }

        val parsed = NotificationParser.parse(title, fullText, fullText)
        if (parsed != null) {
            val sig = "$pkg|${parsed.merchant}|${parsed.amount}"
            val now = System.currentTimeMillis()
            if (sig != lastSignature || now - lastTime >= 5000L) {
                lastSignature = sig
                lastTime = now

                val bmp = extractBitmap(extras)
                val imagePath = if (bmp != null) LedgerStorage.saveBitmapToFile(this, bmp) else ""

                LedgerStorage.addOrMergeRecord(
                    context = this,
                    type = parsed.type,
                    category = parsed.category,
                    merchant = parsed.merchant,
                    item = parsed.item,
                    quantity = parsed.quantity,
                    amount = parsed.amount,
                    raw = fullText,
                    imagePath = imagePath,
                    auto = true
                )
                sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").setPackage(packageName))
            }
        }

        // 2. 이메일 앱 알림인 경우: 연동된 IMAP 메일함에서 잘린 뒷내용까지 전체 본문 직접 가져오기
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

    private fun extractBitmap(extras: Bundle): Bitmap? {
        val pic = extras.get(Notification.EXTRA_PICTURE)
        if (pic is Bitmap) return pic

        val largeBig = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
        if (largeBig is Bitmap) return largeBig
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && largeBig is Icon) {
            return drawableToBitmap(largeBig.loadDrawable(this))
        }

        val large = extras.get(Notification.EXTRA_LARGE_ICON)
        if (large is Bitmap) return large
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && large is Icon) {
            return drawableToBitmap(large.loadDrawable(this))
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

            for (i in messages.indices.reversed()) {
                val msg = messages[i]
                val receivedTime = msg.receivedDate?.time ?: msg.sentDate?.time ?: now
                if (now - receivedTime > lookbackMs) continue

                val subject = msg.subject ?: ""
                val bodyText = extractMessageText(msg)
                val fullText = "$subject\n$bodyText".trim()

                val parsed = NotificationParser.parse(subject, fullText, fullText)
                if (parsed != null) {
                    LedgerStorage.addOrMergeRecord(
                        context = context,
                        type = parsed.type,
                        category = parsed.category,
                        merchant = parsed.merchant,
                        item = parsed.item,
                        quantity = parsed.quantity,
                        amount = parsed.amount,
                        raw = fullText,
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
        // HTML 테이블 구조가 있는 영수증 메일은 구조화된 HTML 변환 결과를 우선 사용
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
        "식비", "카페/간식", "쇼핑/생활", "교통/차량", "구독/통신", "의료/건강", "수입", "기타"
    )

    private val KEYWORDS = listOf("승인", "결제", "출금", "입금", "사용", "취소", "이체", "페이", "카드", "영수증", "KRW", "주문")

    private val PG_OR_GENERIC_NAMES = listOf(
        "메시지", "문자", "신한카드", "삼성카드", "현대카드", "KB국민카드", "롯데카드",
        "우리카드", "하나카드", "NH농협카드", "카카오페이", "네이버페이", "토스", "Gmail",
        "NHN KCP", "KCP", "KG이니시스", "이니시스", "토스페이먼츠", "나이스페이", "다날", "발신전용"
    )

    data class ParsedPayment(
        val type: String,
        val category: String,
        val merchant: String,
        val item: String,
        val quantity: String,
        val amount: Long
    )

    fun containsPaymentKeyword(text: String): Boolean {
        return KEYWORDS.any { text.contains(it) }
    }

    fun parse(title: String, body: String, fullText: String): ParsedPayment? {
        if (!containsPaymentKeyword(fullText)) return null

        val amtRegex = Regex("""([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원|KRW\s*([0-9,]+)""")
        val amtMatch = amtRegex.find(fullText) ?: return null
        val rawNum = (amtMatch.groupValues[1].ifEmpty { amtMatch.groupValues[2] }).replace(",", "")
        val amount = rawNum.toLongOrNull() ?: return null
        if (amount <= 0L) return null

        val isIncome = fullText.contains("입금") || fullText.contains("취소") || fullText.contains("환불")
        val type = if (isIncome) "inc" else "exp"

        // 1. 명시적 라벨에서 결제처(가맹점) 우선 추출 (예: 구매상점명 : 쿠팡(쿠페이))
        val labeledMerchantRegex = Regex("""(?:구매상점명|가맹점명|상점명|사용처|결제처|판매자)\s*[:：]\s*([^\n\r]+)""")
        val labeledMerchant = labeledMerchantRegex.find(fullText)?.groupValues?.get(1)?.trim() ?: ""

        // 2. 명시적 라벨에서 품목 우선 추출 (예: 주문상품명 : 하겐다즈...)
        val labeledItemRegex = Regex("""(?:주문상품명|상품명|품목명|품목)\s*[:：]\s*([^\n\r]+)""")
        var item = labeledItemRegex.find(fullText)?.groupValues?.get(1)?.trim() ?: ""

        // 3. 수량 추출 ('80ml' 제외, '3개입' 등 묶음표기 제외, '총 2건' 또는 독립 수량 우선)
        val totalCountRegex = Regex("""(총\s*[0-9]+\s*(?:건|개|종))""")
        val standaloneQtyRegex = Regex("""([0-9]+\s*(?:개|잔|건|박스|병|팩|매|세트|봉|통|권))(?!입)""")
        val searchTargetForQty = if (item.isNotEmpty()) item else body
        val quantity = totalCountRegex.find(searchTargetForQty)?.groupValues?.get(1)?.trim()
            ?: standaloneQtyRegex.find(searchTargetForQty)?.groupValues?.get(1)?.trim()
            ?: ""

        // 품목 라벨이 없었을 때만 보조 추출
        if (item.isEmpty() && quantity.isNotEmpty()) {
            val fallbackItemRegex = Regex("""([가-힣a-zA-Z0-9\s\-]{2,25})\s*${Regex.escape(quantity)}""")
            val candidateItem = fallbackItemRegex.find(body)?.groupValues?.get(1)?.trim() ?: ""
            if (!candidateItem.matches(Regex("""^[0-9]+(?:ml|g|kg|L)$""", RegexOption.IGNORE_CASE))) {
                item = candidateItem
            }
        }

        // 결제처(제목) 최종 결정 (PG사 이름 배제)
        val cleanTitle = title.replace(Regex("""\[.*?]"""), "").trim()
        val isGenericTitle = cleanTitle.isEmpty() || PG_OR_GENERIC_NAMES.any {
            cleanTitle.contains(it, ignoreCase = true)
        }

        val merchant = when {
            labeledMerchant.isNotEmpty() -> labeledMerchant
            !isGenericTitle -> cleanTitle
            else -> {
                val lines = body.replace(Regex("""\[.*?]"""), "")
                    .split("\n")
                    .map { it.trim() }
                    .filter { line ->
                        line.isNotEmpty() &&
                            !line.contains("잔액") &&
                            !line.contains("누적") &&
                            !line.contains("안전하게 결제") &&
                            PG_OR_GENERIC_NAMES.none { pg -> line.equals(pg, ignoreCase = true) }
                    }
                (lines.firstOrNull { it.contains("페이") || it.contains("마트") || it.contains("스토어") || it.contains("점") }
                    ?: lines.lastOrNull()
                    ?: "결제 내역").take(26)
            }
        }

        val category = if (isIncome) {
            "수입"
        } else {
            classifyCategory("$merchant $item $fullText")
        }

        return ParsedPayment(
            type = type,
            category = category,
            merchant = merchant.ifEmpty { "결제 내역" },
            item = item,
            quantity = quantity,
            amount = amount
        )
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
    private const val KEY_HISTORY = "search_history"
    private const val KEY_EMAIL_PROV = "email_provider"
    private const val KEY_EMAIL_ADDR = "email_address"
    private const val KEY_EMAIL_PASS = "email_password"

    fun isDarkMode(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, false)
    }

    fun setDarkMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, enabled).apply()
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

    // 동일 결제(3분 이내·동일 금액)가 문자와 이메일로 중복 도착할 경우 더 상세한 내용으로 통합
    fun addOrMergeRecord(
        context: Context,
        type: String,
        category: String,
        merchant: String,
        item: String,
        quantity: String,
        amount: Long,
        raw: String,
        imagePath: String,
        auto: Boolean
    ) {
        val oldArr = getRecords(context)
        val now = System.currentTimeMillis()

        if (auto) {
            for (i in 0 until oldArr.length()) {
                val existing = oldArr.getJSONObject(i)
                val exTime = existing.optLong("timestamp", 0L)
                val exAmt = existing.optLong("amount", 0L)
                val exType = existing.optString("type", "exp")
                if (exAmt == amount && exType == type && (now - exTime) in 0..180_000L) {
                    val exRaw = existing.optString("raw", "")
                    if (raw.length > exRaw.length) {
                        existing.put("raw", raw)
                        if (item.isNotEmpty()) existing.put("item", item)
                        if (quantity.isNotEmpty()) existing.put("quantity", quantity)
                        if (merchant != "결제 내역") existing.put("merchant", merchant)
                        if (category != "기타") existing.put("category", category)
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
            put("item", item)
            put("quantity", quantity)
            put("amount", amount)
            put("date", dateStr)
            put("raw", raw)
            put("imagePath", imagePath)
            put("auto", auto)
        }
        newArr.put(record)
        for (i in 0 until oldArr.length()) {
            newArr.put(oldArr.getJSONObject(i))
        }
        save(context, newArr)
    }

    fun updateRecord(
        context: Context,
        id: String,
        type: String,
        category: String,
        merchant: String,
        item: String,
        quantity: String,
        amount: Long,
        raw: String,
        imagePath: String
    ) {
        val arr = getRecords(context)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") == id) {
                obj.put("type", type)
                obj.put("category", category)
                obj.put("merchant", merchant)
                obj.put("item", item)
                obj.put("quantity", quantity)
                obj.put("amount", amount)
                obj.put("raw", raw)
                obj.put("imagePath", imagePath)
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

    private fun save(context: Context, arr: JSONArray) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }
}
