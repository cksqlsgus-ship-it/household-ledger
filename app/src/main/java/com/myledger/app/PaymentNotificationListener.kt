package com.myledger.app

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class PaymentNotificationListener : NotificationListenerService() {

    private var lastRawKey: String = ""
    private var lastPostTimeMs: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

        val body = listOf(bigText, normalText, subText)
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .trim()

        if (title.isEmpty() && body.isEmpty()) return

        val combinedForCheck = "$title\n$body"
        val nowMs = System.currentTimeMillis()
        val dedupKey = "$pkg|$combinedForCheck"

        if (dedupKey == lastRawKey && (nowMs - lastPostTimeMs) < 3000L) {
            return
        }
        lastRawKey = dedupKey
        lastPostTimeMs = nowMs

        if (!looksLikeFinancialNotification(combinedForCheck)) {
            return
        }

        val parsed = NotificationParser.parse(this, title, body)
        if (!parsed.shouldSave || parsed.amount <= 0L) {
            return
        }

        val eventTs = if (sbn.postTime > 0L) sbn.postTime else nowMs

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
            imagePath = "",
            auto = true,
            customTimestamp = eventTs
        )

        sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").apply {
            setPackage(packageName)
        })
    }

    private fun looksLikeFinancialNotification(text: String): Boolean {
        val hasNumber = Regex("[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+").containsMatchIn(text)
        if (!hasNumber) return false

        val hasCurrencyOrAction = Regex("(원|₩|결제|승인|입금|출금|이체|환불|취소|구독|청구)").containsMatchIn(text)
        if (hasCurrencyOrAction) return true

        val templates = LedgerStorage.getParserTemplates(this)
        if (templates.any { tpl ->
                val tokens = tpl.includeWords.split(",", "\n", "|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                tokens.isNotEmpty() && tokens.all { text.contains(it, ignoreCase = true) }
            }) {
            return true
        }

        val rules = LedgerStorage.getAutoRules(this)
        return rules.any { r ->
            r.keyword.isNotBlank() && text.contains(r.keyword, ignoreCase = true)
        }
    }
}
