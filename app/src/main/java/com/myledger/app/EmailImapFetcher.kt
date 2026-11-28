package com.myledger.app

import android.content.Context
import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

object EmailImapFetcher {

    fun fetchEmailsInPeriod(context: Context, startMs: Long, endMs: Long): String {
        val provider = LedgerStorage.getEmailProvider(context)
        val address = LedgerStorage.getEmailAddress(context)
        val password = LedgerStorage.getEmailPassword(context).replace(" ", "").trim()

        if (address.isEmpty() || password.isEmpty()) {
            return "연동된 이메일 주소 또는 앱 비밀번호가 없습니다."
        }

        val host = if (provider == "naver") "imap.naver.com" else "imap.gmail.com"
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.timeout", "15000")
            put("mail.imaps.connectiontimeout", "15000")
        }

        return try {
            val session = Session.getInstance(props)
            val store = session.getStore("imaps")
            store.connect(host, address, password)

            val inbox: Folder = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)

            val queryStartDate = Date(startMs - 24L * 3600L * 1000L)
            val messages: Array<Message> = try {
                inbox.search(ReceivedDateTerm(ComparisonTerm.GE, queryStartDate))
            } catch (_: Exception) {
                inbox.messages
            }

            var importedCount = 0
            val nowFallback = System.currentTimeMillis()

            for (msg in messages) {
                val msgTs = msg.sentDate?.time ?: msg.receivedDate?.time ?: nowFallback
                if (msgTs < startMs || msgTs > endMs) continue

                val subject = msg.subject ?: ""
                val body = extractMessageText(msg)
                if (subject.isBlank() && body.isBlank()) continue

                val parsed = NotificationParser.parse(context, subject, body)
                if (!parsed.shouldSave || parsed.amount <= 0L) continue

                val recordTs = if (msgTs > 0L) msgTs else nowFallback
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
                    auto = true,
                    customTimestamp = recordTs
                )
                importedCount++
            }

            try {
                inbox.close(false)
            } catch (_: Exception) {
            }
            try {
                store.close()
            } catch (_: Exception) {
            }

            if (importedCount > 0) {
                "연동 완료! 설정 기간 내 결제 메일 ${importedCount}건을 처리했습니다."
            } else {
                "연동 완료! 설정 기간 내 새로 기록할 결제 메일이 없습니다."
            }
        } catch (e: Exception) {
            "연동 실패: ${e.message ?: "이메일 또는 앱 비밀번호를 확인하세요"}"
        }
    }

    private fun extractMessageText(message: Message): String {
        return try {
            when (val content = message.content) {
                is String -> stripHtml(content)
                is Multipart -> extractMultipartText(content)
                else -> message.description ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractMultipartText(multipart: Multipart): String {
        val sb = StringBuilder()
        for (i in 0 until multipart.count) {
            val bodyPart = multipart.getBodyPart(i)
            when (val partContent = bodyPart.content) {
                is String -> {
                    val mimeType = bodyPart.contentType?.lowercase() ?: ""
                    val plain = if (mimeType.contains("html")) stripHtml(partContent) else partContent
                    if (plain.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(plain.trim())
                    }
                }
                is Multipart -> {
                    val nested = extractMultipartText(partContent)
                    if (nested.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(nested.trim())
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("(?is)<style.*?>.*?</style>"), " ")
            .replace(Regex("(?is)<script.*?>.*?</script>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|tr|li|h[1-6])>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()
    }
}
