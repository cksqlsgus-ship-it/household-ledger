package com.myledger.app

import android.content.Context
import org.jsoup.Jsoup
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Session

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
            .replace(Regex("(?i)</(tr|p|div|li|h[1-6])>|<br\\s*/?>"), "___NL___")
            .replace(Regex("(?i)</(td|th)>"), " : ")
        val plain = Jsoup.parse(withMarkers).text()
        return plain.split("___NL___")
            .map { line ->
                line.replace(Regex("\\s+"), " ")
                    .replace(Regex("^[:\\s]+|[:\\s]+$"), "")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
