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
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

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

        val eventTs = if (sbn.postTime > 0L) sbn.postTime else nowMs
        val decision = NotificationParser.evaluateFilterDecision(this, combinedForCheck)

        when (decision) {
            FilterDecision.IGNORE -> {
                return
            }
            FilterDecision.UNMATCHED -> {
                LedgerStorage.addUnmatchedAlert(
                    context = this,
                    packageName = pkg,
                    title = title,
                    body = body,
                    timestamp = eventTs
                )
                sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").apply {
                    setPackage(packageName)
                })
                return
            }
            FilterDecision.PASS -> {
                val parsed = NotificationParser.parse(this, title, body)
                if (!parsed.shouldSave || parsed.amount <= 0L) {
                    LedgerStorage.addUnmatchedAlert(
                        context = this,
                        packageName = pkg,
                        title = title,
                        body = body,
                        timestamp = eventTs
                    )
                    sendBroadcast(Intent("com.myledger.app.UPDATE_LEDGER").apply {
                        setPackage(packageName)
                    })
                    return
                }

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
        }
    }
}
