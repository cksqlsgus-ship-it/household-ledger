package com.myledger.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var statusBtn: Button
    private lateinit var balanceText: TextView
    private lateinit var incText: TextView
    private lateinit var expText: TextView
    private lateinit var listContainer: LinearLayout

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }

        // 상단 헤더
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }

        val titleView = TextView(this).apply {
            text = "💰 자동 가계부"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        header.addView(titleView)

        statusBtn = Button(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            layoutParams = lp
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        header.addView(statusBtn)
        root.addView(header)

        // 스크롤 영역
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // 요약 카드
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.WHITE, 12)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER
        }
        summaryCard.addView(TextView(this).apply {
            text = "현재 잔액"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        balanceText = TextView(this).apply {
            text = "0원"
            setTextColor(Color.parseColor("#111827"))
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
        }
        summaryCard.addView(balanceText)

        val incExpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        incText = TextView(this).apply {
            text = "수입 +0원"
            setTextColor(Color.parseColor("#2563EB"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
        }
        expText = TextView(this).apply {
            text = "지출 -0원"
            setTextColor(Color.parseColor("#DC2626"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
        }
        incExpRow.addView(incText)
        incExpRow.addView(expText)
        summaryCard.addView(incExpRow)
        content.addView(summaryCard)

        // 수동 입력 카드
        val inputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Color.WHITE, 12)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            layoutParams = lp
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("지출 (-)", "수입 (+)")
            )
        }
        val descInput = EditText(this).apply {
            hint = "내역 (예: 점심식사)"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row1.addView(typeSpinner)
        row1.addView(descInput)
        inputCard.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            layoutParams = lp
        }
        val amountInput = EditText(this).apply {
            hint = "금액 (숫자만)"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = Button(this).apply {
            text = "추가"
            setTextColor(Color.WHITE)
            background = roundBg(Color.parseColor("#10B981"), 8)
            setOnClickListener {
                val desc = descInput.text.toString().trim()
                val amt = amountInput.text.toString().trim().toLongOrNull() ?: 0L
                if (desc.isNotEmpty() && amt > 0) {
                    val type = if (typeSpinner.selectedItemPosition == 1) "inc" else "exp"
                    LedgerStorage.addRecord(this@MainActivity, type, desc, amt, "직접 입력", false)
                    descInput.setText("")
                    amountInput.setText("")
                    refreshUI()
                }
            }
        }
        row2.addView(amountInput)
        row2.addView(addBtn)
        inputCard.addView(row2)
        content.addView(inputCard)

        // 내역 리스트 컨테이너
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            layoutParams = lp
        }
        content.addView(listContainer)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
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

    private fun isNotificationGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun refreshUI() {
        if (isNotificationGranted()) {
            statusBtn.text = "✅ 자동 알림 감지 켜짐 (설정 변경)"
            statusBtn.setTextColor(Color.parseColor("#065F46"))
            statusBtn.background = roundBg(Color.parseColor("#D1FAE5"), 8)
        } else {
            statusBtn.text = "⚠️ 알림 접근 권한이 꺼져 있습니다 (눌러서 켜기)"
            statusBtn.setTextColor(Color.parseColor("#991B1B"))
            statusBtn.background = roundBg(Color.parseColor("#FEE2E2"), 8)
        }

        val records = LedgerStorage.getRecords(this)
        listContainer.removeAllViews()

        var totalInc = 0L
        var totalExp = 0L
        val nf = NumberFormat.getNumberInstance(Locale.KOREA)

        for (i in 0 until records.length()) {
            val obj = records.getJSONObject(i)
            val type = obj.optString("type", "exp")
            val desc = obj.optString("desc", "")
            val amount = obj.optLong("amount", 0L)
            val date = obj.optString("date", "")
            val raw = obj.optString("raw", "")
            val isAuto = obj.optBoolean("auto", false)

            if (type == "inc") totalInc += amount else totalExp += amount

            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = roundBg(Color.WHITE, 10)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                gravity = Gravity.CENTER_VERTICAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                layoutParams = lp
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(desc)
                        .setMessage("시간: $date\n금액: ${nf.format(amount)}원\n\n[원본 알림 내용]\n$raw")
                        .setPositiveButton("닫기", null)
                        .show()
                }
            }

            val leftCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            leftCol.addView(TextView(this).apply {
                text = if (isAuto) "🤖 $desc" else desc
                setTextColor(Color.parseColor("#111827"))
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
            })
            leftCol.addView(TextView(this).apply {
                text = date
                setTextColor(Color.parseColor("#6B7280"))
                textSize = 12f
            })
            itemCard.addView(leftCol)

            val sign = if (type == "inc") "+" else "-"
            val color = if (type == "inc") Color.parseColor("#2563EB") else Color.parseColor("#DC2626")
            itemCard.addView(TextView(this).apply {
                text = "$sign${nf.format(amount)}원"
                setTextColor(color)
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(8), 0, dp(8), 0)
            })

            val delBtn = Button(this).apply {
                text = "삭제"
                textSize = 12f
                setTextColor(Color.parseColor("#4B5563"))
                background = roundBg(Color.parseColor("#F3F4F6"), 6)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(36))
                setOnClickListener {
                    LedgerStorage.removeRecord(this@MainActivity, i)
                    refreshUI()
                }
            }
            itemCard.addView(delBtn)

            listContainer.addView(itemCard)
        }

        balanceText.text = "${nf.format(totalInc - totalExp)}원"
        incText.text = "수입 +${nf.format(totalInc)}원"
        expText.text = "지출 -${nf.format(totalExp)}원"
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

        val body = if (bigText.length > text.length) bigText else text
        val fullText = "$title $body".trim()
        if (fullText.isEmpty()) return

        // 결제/입출금 관련 키워드 확인
        val keywords = listOf("승인", "결제", "출금", "입금", "사용", "취소", "이체", "페이", "카드", "영수증", "KRW")
        if (keywords.none { fullText.contains(it) }) return

        // 금액 추출 (예: 15,000원 / 15000원 / KRW 15,000)
        val regex = Regex("""([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\s*원|KRW\s*([0-9,]+)""")
        val match = regex.find(fullText) ?: return
        val rawNum = (match.groupValues[1].ifEmpty { match.groupValues[2] }).replace(",", "")
        val amount = rawNum.toLongOrNull() ?: return
        if (amount <= 0L) return

        // 5초 이내 동일 알림 중복 방지
        val sig = "$pkg|$title|$amount"
        val now = System.currentTimeMillis()
        if (sig == lastSignature && now - lastTime < 5000L) return
        lastSignature = sig
        lastTime = now

        val isIncome = fullText.contains("입금") || fullText.contains("취소") || fullText.contains("환불")
        val type = if (isIncome) "inc" else "exp"

        val cleanTitle = title.replace(Regex("""\[.*?]"""), "").trim()
        val summaryDesc = if (cleanTitle.isNotEmpty()) {
            "$cleanTitle (${body.take(18)})"
        } else {
            body.take(24)
        }

        LedgerStorage.addRecord(this, type, summaryDesc, amount, fullText, true)
        sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").setPackage(packageName))
    }
}

object LedgerStorage {
    private const val PREF_NAME = "AutoLedgerPrefs"
    private const val KEY_RECORDS = "records"

    fun getRecords(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            JSONArray(str)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun addRecord(context: Context, type: String, desc: String, amount: Long, raw: String, auto: Boolean) {
        val oldArr = getRecords(context)
        val newArr = JSONArray()
        val dateStr = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date())

        val item = JSONObject().apply {
            put("type", type)
            put("desc", desc)
            put("amount", amount)
            put("date", dateStr)
            put("raw", raw)
            put("auto", auto)
        }
        newArr.put(item)
        for (i in 0 until oldArr.length()) {
            newArr.put(oldArr.getJSONObject(i))
        }
        save(context, newArr)
    }

    fun removeRecord(context: Context, index: Int) {
        val oldArr = getRecords(context)
        val newArr = JSONArray()
        for (i in 0 until oldArr.length()) {
            if (i != index) newArr.put(oldArr.getJSONObject(i))
        }
        save(context, newArr)
    }

    private fun save(context: Context, arr: JSONArray) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }
}
