package com.myledger.app

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class PaymentNotificationListener : NotificationListenerService() {

    private var lastSignature = ""
    private var lastTime = 0L

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
