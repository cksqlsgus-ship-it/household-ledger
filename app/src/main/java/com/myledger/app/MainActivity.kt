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
    private var selectedAccountId: String? = null
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
        if (currentTab == "accounts" && selectedAccountId != null) {
            selectedAccountId = null
            refreshUI()
        } else if (currentTab != "all") {
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
            "accounts" -> {
                val scroll = ScrollView(this)
                val container = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(24))
                }
                renderAccountsTab(isDark, container)
                scroll.addView(container)
                contentHost.addView(scroll)
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
            Triple("accounts", "🏦", "계좌"),
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
                    if (tabKey == "accounts") {
                        selectedAccountId = null
                    }
                    if (currentTab != tabKey || (tabKey == "accounts" && selectedAccountId != null)) {
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

    private fun renderAccountsTab(isDark: Boolean, container: LinearLayout) {
        val bgCard = if (isDark) Color.parseColor("#1C1C1E") else Color.WHITE
        val bgPill = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        val textPrimary = if (isDark) Color.parseColor("#F5F5F7") else Color.parseColor("#111827")
        val textSecondary = if (isDark) Color.parseColor("#98989D") else Color.parseColor("#6B7280")
        val incColor = if (isDark) Color.parseColor("#6495ED") else Color.parseColor("#2563EB")
        val expColor = if (isDark) Color.parseColor("#E56B6F") else Color.parseColor("#C83E4D")
        val trnColor = if (isDark) Color.parseColor("#A78BFA") else Color.parseColor("#7C3AED")
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)

        val accounts: List<AccountItem> = LedgerStorage.getAccounts(this)
        val activeAccount: AccountItem? = selectedAccountId?.let { id -> accounts.firstOrNull { it.id == id } }

        if (activeAccount != null) {
            renderAccountDetailScreen(
                container = container,
                account = activeAccount,
                bgCard = bgCard,
                bgPill = bgPill,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                incColor = incColor,
                expColor = expColor,
                trnColor = trnColor,
                nf = nf
            )
            return
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        headerRow.addView(TextView(this).apply {
            text = "계좌 목록 (${accounts.size}개)"
            setTextColor(textPrimary)
            textSize = sp(17f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        headerRow.addView(makePillButton(isDark, "+ 계좌 추가", isActive = true) {
            showAddAccountDialog()
        })
        container.addView(headerRow)

        if (accounts.isEmpty()) {
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundBg(bgCard, 16)
                setPadding(dp(20), dp(28), dp(20), dp(28))
                gravity = Gravity.CENTER
                addView(TextView(this@MainActivity).apply {
                    text = "등록된 통장/계좌가 없습니다."
                    setTextColor(textPrimary)
                    textSize = sp(15f)
                    setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "월급 통장, 생활비 통장, 여가 통장, 고정지출 통장 등을 추가하고\n시작 잔액을 입력해 통장별 흐름을 관리해 보세요."
                    setTextColor(textSecondary)
                    textSize = sp(12f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
            })
            return
        }

        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val columns = if (widthDp >= 600) 2 else 1

        var idx = 0
        while (idx < accounts.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            for (col in 0 until columns) {
                val accIndex = idx + col
                if (accIndex < accounts.size) {
                    val acc: AccountItem = accounts[accIndex]
                    val summary: AccountFlowSummary = LedgerStorage.calculateAccountSummary(this, acc)
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = roundBg(bgCard, 16)
                        setPadding(dp(16), dp(15), dp(16), dp(15))
                        minimumHeight = dp(122)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (columns > 1 && col == 0) rightMargin = dp(8)
                        }
                        setOnClickListener {
                            selectedAccountId = acc.id
                            refreshUI()
                        }
                    }

                    val topMeta = if (acc.institution.isNotBlank()) "${acc.name} · ${acc.institution}" else acc.name
                    card.addView(TextView(this).apply {
                        text = topMeta
                        setTextColor(textPrimary)
                        textSize = sp(14f)
                        setTypeface(null, Typeface.BOLD)
                        isSingleLine = true
                    })

                    card.addView(TextView(this).apply {
                        text = "${nf.format(summary.balance.toLong())}원"
                        setTextColor(textPrimary)
                        textSize = sp(21f)
                        setTypeface(null, Typeface.BOLD)
                        isSingleLine = true
                        setPadding(0, dp(6), 0, dp(8))
                    })

                    val flowRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    flowRow.addView(TextView(this).apply {
                        text = "유입 +${nf.format(summary.inflow.toLong())}원"
                        setTextColor(incColor)
                        textSize = sp(11.5f)
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    flowRow.addView(TextView(this).apply {
                        text = "유출 -${nf.format(summary.outflow.toLong())}원"
                        setTextColor(expColor)
                        textSize = sp(11.5f)
                        setTypeface(null, Typeface.BOLD)
                    })
                    card.addView(flowRow)

                    row.addView(card)
                } else {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(122), 1f).apply {
                            leftMargin = dp(8)
                        }
                    })
                }
            }

            container.addView(row)
            idx += columns
        }
    }

    private fun renderAccountDetailScreen(
        container: LinearLayout,
        account: AccountItem,
        bgCard: Int,
        bgPill: Int,
        textPrimary: Int,
        textSecondary: Int,
        incColor: Int,
        expColor: Int,
        trnColor: Int,
        nf: NumberFormat
    ) {
        val isDark = LedgerStorage.isDarkMode(this)
        val summary: AccountFlowSummary = LedgerStorage.calculateAccountSummary(this, account)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        topBar.addView(makePillButton(isDark, "← 목록") {
            selectedAccountId = null
            refreshUI()
        })
        topBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })
        topBar.addView(makePillButton(isDark, "자동이체 설정") {
            showAccountAutoTransferDialog(account)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) }
        })
        topBar.addView(makePillButton(isDark, "키워드 수정") {
            showAccountKeywordsDialog(account)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) }
        })
        topBar.addView(makePillButton(isDark, "정보 수정") {
            showEditAccountDialog(account)
        })
        container.addView(topBar)

        val detailSummaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(bgCard, 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val titleStr = if (account.institution.isNotBlank()) "${account.name} · ${account.institution}" else account.name
        detailSummaryCard.addView(TextView(this).apply {
            text = titleStr
            setTextColor(textSecondary)
            textSize = sp(13f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        detailSummaryCard.addView(TextView(this).apply {
            text = "${nf.format(summary.balance.toLong())}원"
            setTextColor(textPrimary)
            textSize = sp(25f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })

        val ioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        ioRow.addView(TextView(this).apply {
            text = "유입 +${nf.format(summary.inflow.toLong())}원"
            setTextColor(incColor)
            textSize = sp(13f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
        })
        ioRow.addView(TextView(this).apply {
            text = "유출 -${nf.format(summary.outflow.toLong())}원"
            setTextColor(expColor)
            textSize = sp(13f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
        })
        detailSummaryCard.addView(ioRow)

        detailSummaryCard.addView(TextView(this).apply {
            text = "시작 잔액 ${nf.format(account.initialBalance.toLong())}원 · 키워드: ${account.keywords}"
            setTextColor(textSecondary)
            textSize = sp(11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        if (account.autoTransferNote.isNotBlank()) {
            detailSummaryCard.addView(TextView(this).apply {
                text = "자동이체 메모: ${account.autoTransferNote}"
                setTextColor(trnColor)
                textSize = sp(11.5f)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
        container.addView(detailSummaryCard)

        container.addView(TextView(this).apply {
            text = "계좌 결제·이체 내역 ${summary.matchedRecords.size}건 (눌러서 상세 · 수정)"
            setTextColor(textSecondary)
            textSize = sp(12f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        })

        renderRecordCards(
            targetContainer = container,
            records = summary.matchedRecords,
            bgCard = bgCard,
            textSecondary = textSecondary,
            incColor = incColor,
            expColor = expColor,
            trnColor = trnColor,
            nf = nf,
            compact = false
        )
    }

    private fun showAddAccountDialog() {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        fun addField(label: String, hintStr: String, isSignedNumber: Boolean = false): EditText {
            box.addView(TextView(this).apply {
                text = label
                textSize = sp(12f)
                setTextColor(labelColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(3))
            })
            val edit = EditText(this).apply {
                hint = hintStr
                textSize = sp(14f)
                if (isSignedNumber) {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                styleDialogEditText(this, isDark)
            }
            box.addView(edit)
            return edit
        }

        val nameEdit = addField("통장 이름", "예: 월급 통장, 생활비 통장, 여가 통장")
        val instEdit = addField("은행 / 증권사", "예: 토스뱅크, 국민은행, 한국투자증권")
        val initBalEdit = addField("시작 잔액 (원)", "숫자만 입력 (예: 1000000)", isSignedNumber = true)
        val kwEdit = addField("자동 매칭 키워드 (쉼표로 구분)", "예: 생활비, 토뱅, 이정*")
        val autoNoteEdit = addField("자동이체 설정 메모 (선택)", "예: 매월 25일 월급통장에서 80만원 입금")

        scroll.addView(box)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("계좌 추가")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val name = nameEdit.text.toString().trim()
                val inst = instEdit.text.toString().trim()
                val initBal = initBalEdit.text.toString().trim().toLongOrNull() ?: 0L
                val kw = kwEdit.text.toString().trim().ifEmpty { name }
                val note = autoNoteEdit.text.toString().trim()
                if (name.isNotEmpty()) {
                    LedgerStorage.addAccount(this, name, inst, initBal, kw, note)
                    refreshUI()
                } else {
                    Toast.makeText(this, "통장 이름을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditAccountDialog(account: AccountItem) {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }

        fun addField(label: String, initial: String, isSignedNumber: Boolean = false): EditText {
            box.addView(TextView(this).apply {
                text = label
                textSize = sp(12f)
                setTextColor(labelColor)
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(3))
            })
            val edit = EditText(this).apply {
                setText(initial)
                textSize = sp(14f)
                if (isSignedNumber) {
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                styleDialogEditText(this, isDark)
            }
            box.addView(edit)
            return edit
        }

        val nameEdit = addField("통장 이름", account.name)
        val instEdit = addField("은행 / 증권사", account.institution)
        val initBalEdit = addField("시작 잔액 (원)", account.initialBalance.toString(), isSignedNumber = true)
        val kwEdit = addField("자동 매칭 키워드", account.keywords)
        val autoNoteEdit = addField("자동이체 설정 메모", account.autoTransferNote)

        var dialogRef: AlertDialog? = null
        val deleteBtn = makePillButton(isDark, "계좌 삭제", isActive = false) {
            AlertDialog.Builder(this@MainActivity, getDialogTheme(isDark))
                .setTitle("계좌 삭제")
                .setMessage("\"${account.name}\" 계좌를 삭제할까요?")
                .setPositiveButton("삭제") { _, _ ->
                    LedgerStorage.removeAccount(this@MainActivity, account.id)
                    selectedAccountId = null
                    dialogRef?.dismiss()
                    refreshUI()
                }
                .setNegativeButton("취소", null)
                .show()
        }.apply {
            setTextColor(Color.parseColor("#E56B6F"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        box.addView(deleteBtn)

        scroll.addView(box)

        dialogRef = AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("계좌 정보 수정")
            .setView(scroll)
            .setPositiveButton("저장") { _, _ ->
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "통장 이름을 입력해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val inst = instEdit.text.toString().trim()
                val initBal = initBalEdit.text.toString().trim().toLongOrNull() ?: account.initialBalance
                val kw = kwEdit.text.toString().trim().ifEmpty { name }
                val note = autoNoteEdit.text.toString().trim()
                LedgerStorage.updateAccount(this, account.id, name, inst, initBal, kw, note)
                refreshUI()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAccountAutoTransferDialog(account: AccountItem) {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "${account.name} 자동이체 설정 / 메모"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val noteEdit = EditText(this).apply {
            setText(account.autoTransferNote)
            hint = "예: 매월 25일 월급통장에서 800,000원 이체"
            minLines = 2
            styleDialogEditText(this, isDark)
        }
        box.addView(noteEdit)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("자동이체 설정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                LedgerStorage.updateAccount(
                    this,
                    account.id,
                    account.name,
                    account.institution,
                    account.initialBalance,
                    account.keywords,
                    noteEdit.text.toString().trim()
                )
                refreshUI()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAccountKeywordsDialog(account: AccountItem) {
        val isDark = LedgerStorage.isDarkMode(this)
        val labelColor = if (isDark) Color.parseColor("#E5E7EB") else Color.parseColor("#374151")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        box.addView(TextView(this).apply {
            text = "알림/내역과 이 계좌를 연결할 키워드 (쉼표로 구분)"
            textSize = sp(12f)
            setTextColor(labelColor)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        val kwEdit = EditText(this).apply {
            setText(account.keywords)
            hint = "예: 생활비, 토뱅, 이정*"
            styleDialogEditText(this, isDark)
        }
        box.addView(kwEdit)

        AlertDialog.Builder(this, getDialogTheme(isDark))
            .setTitle("키워드 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val kw = kwEdit.text.toString().trim().ifEmpty { account.name }
                LedgerStorage.updateAccount(
                    this,
                    account.id,
                    account.name,
                    account.institution,
                    account.initialBalance,
                    kw,
                    account.autoTransferNote
                )
                refreshUI()
            }
            .setNegativeButton("취소", null)
            .show()
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
            setPadding(dp(12), dp(7), dp(12), dp(7))
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
        val incColor =
