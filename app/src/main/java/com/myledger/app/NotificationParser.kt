package com.myledger.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

object NotificationParser {

    val CATEGORIES = listOf(
        "식비", "카페/간식", "쇼핑/생활", "교통/차량", "구독/통신", "의료/건강", "고정 지출", "이체/계좌이동", "수입", "기타"
    )

    private val KEYWORDS = listOf(
        "승인", "결제", "출금", "입금", "사용", "취소", "이체", "페이", "카드",
        "영수증", "KRW", "₩", "주문", "송금", "월회비", "구독", "갱신"
    )

    private val GENERIC_TAGS = listOf(
        "web발신", "국외발신", "국제발신", "광고", "알림", "발신전용",
        "신용카드", "체크카드", "계좌이체", "휴대폰결제", "간편결제", "카드", "현금영수증"
    )

    private val BANK_NAMES = listOf(
        "NH농협", "농협", "KB국민", "국민", "신한", "우리", "하나", "IBK기업", "기업",
        "카카오뱅크", "토스뱅크", "케이뱅크", "새마을금고", "새마을", "우체국", "수협", "신협",
        "대구", "부산", "경남", "광주", "전북", "제주", "SC제일", "씨티"
    )

    private val PG_OR_GENERIC_NAMES = listOf(
        "메시지", "문자", "신용카드", "체크카드", "신한카드", "삼성카드", "현대카드", "KB국민카드", "롯데카드",
        "우리카드", "하나카드", "NH농협카드", "카카오페이", "네이버페이", "토스", "Gmail",
        "NHN KCP", "KCP", "KG이니시스", "이니시스", "토스페이먼츠", "나이스페이", "다날", "발신전용", "이지페이"
    )

    private val FOOD_KEYWORDS = listOf(
        "볶음밥", "찌개", "국밥", "덮밥", "초밥", "라면", "우동", "파스타", "피자",
        "치킨", "버거", "족발", "보쌈", "탕수육", "공깃밥", "도시락", "배달", "쿠팡이츠"
    )

    private val CATEGORY_RULES: List<Pair<String, List<String>>> = listOf(
        "구독/통신" to listOf("월회비", "로켓와우", "와우 멤버십", "멤버십", "정기결제", "구독", "SKT", "KT", "LGU+", "통신요금", "넷플릭스", "유튜브", "티빙", "웨이브", "디즈니", "구글", "Google", "애플", "라프텔", "듀오링고"),
        "카페/간식" to listOf("스타벅스", "투썸", "메가커피", "컴포즈", "빽다방", "이디야", "할리스", "폴바셋", "카페", "커피", "베이커리", "파리바게뜨", "뚜레쥬르", "베스킨", "하겐다즈", "아이스크림", "디저트"),
        "식비" to listOf("배달의민족", "배민", "쿠팡이츠", "요기요", "맥도날드", "버거킹", "롯데리아", "식당", "김밥", "국밥", "치킨", "피자", "족발", "보쌈", "횟집", "고기", "한식", "중식", "일식", "분식", "편의점", "GS25", "CU", "세븐일레븐", "바나나"),
        "교통/차량" to listOf("택시", "카카오T", "티머니", "버스", "지하철", "코레일", "SRT", "KTX", "대한항공", "아시아나", "주유", "GS칼텍스", "SK에너지", "S-OIL", "하이패스", "주차"),
        "쇼핑/생활" to listOf("쿠팡", "네이버쇼핑", "11번가", "G마켓", "옥션", "SSG", "이마트", "홈플러스", "롯데마트", "다이소", "올리브영", "무신사", "마트", "마켓컬리"),
        "의료/건강" to listOf("병원", "의원", "치과", "한의원", "약국", "안과", "피부과", "내과", "정형외과", "건강검진")
    )

    data class LineItem(
        val name: String,
        val qty: Int,
        val price: Long
    )

    data class ParsedPayment(
        val type: String,
        val category: String,
        val merchant: String,
        val fromAccount: String,
        val toAccount: String,
        val balanceInfo: String,
        val itemSummary: String,
        val totalQuantity: String,
        val amount: Long,
        val cleanRawText: String,
        val itemsJson: String,
        val parsedTimestamp: Long?
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
            "본 메일은 발신 전용",
            "발신 전용으로 회신되지",
            "상점정보 : 상점 바로가기",
            "상점정보: 상점 바로가기",
            "상점 바로가기",
            "사업자등록번호",
            "통신판매업신고",
            "Play 포인트 적립",
            "포인트 내역에서 사용 가능한",
            "문의사항이 있는 경우",
            "Google Play 고객센터",
            "구독 취소 및 환불 정책",
            "이용약관",
            "개인정보처리방침",
            "고객센터 이용안내",
            "전자금융거래"
        )
        var cutIndex = raw.length
        for (marker in cutMarkers) {
            val idx = raw.indexOf(marker)
            if (idx in 15 until cutIndex) {
                cutIndex = idx
            }
        }
        var trimmed = raw.substring(0, cutIndex).trim()

        val promoStart = trimmed.indexOf("다양한 와우 혜택을 이용해보세요")
        val promoEnd = trimmed.indexOf("월회비 결제 정보")
        if (promoStart > 0 && promoEnd > promoStart) {
            trimmed = (trimmed.substring(0, promoStart).trim() + "\n" + trimmed.substring(promoEnd).trim()).trim()
        }

        return trimmed
    }

    private fun deduplicateLines(text: String): String {
        val seen = LinkedHashSet<String>()
        val noiseExact = setOf(
            "결제정보 : English Receipt 영수증 출력",
            "English Receipt 영수증 출력",
            "상품보기",
            "바로가기",
            "."
        )
        for (rawLine in text.split("\n")) {
            var cleaned = rawLine.replace(Regex("^[0-9\\-]{8,14}\\s*[:：]\\s*"), "").trim()
            if (cleaned.isEmpty() || noiseExact.contains(cleaned)) continue

            val halfMatch = Regex("^(.{4,30})\\s+\\1$").find(cleaned)
            if (halfMatch != null) {
                cleaned = halfMatch.groupValues[1].trim()
            }
            seen.add(cleaned)
        }
        return seen.joinToString("\n")
    }

    private fun extractActualPaymentTimestamp(text: String): Long? {
        val dtRegex = Regex("(?:결제일시|승인일시|거래일시)\\s*[:：]?\\s*(20[0-9]{2})[./-]([0-9]{1,2})[./-]([0-9]{1,2})\\s+([0-9]{1,2}):([0-9]{2})")
        val inlineDtRegex = Regex("(20[0-9]{2})[./-]([0-9]{1,2})[./-]([0-9]{1,2})\\s+([0-9]{1,2}):([0-9]{2})에\\s*(?:결제|승인)")
        val m = dtRegex.find(text) ?: inlineDtRegex.find(text)
        if (m != null) {
            return try {
                val y = m.groupValues[1].toInt()
                val mon = m.groupValues[2].toInt() - 1
                val d = m.groupValues[3].toInt()
                val h = m.groupValues[4].toInt()
                val min = m.groupValues[5].toInt()
                Calendar.getInstance().apply {
                    set(y, mon, d, h, min, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } catch (_: Exception) {
                null
            }
        }

        val gpDtRegex = Regex(
            "주문\\s*날짜\\s*[:：]?\\s*(20[0-9]{2})[./-]\\s*([0-9]{1,2})[./-]\\s*([0-9]{1,2})[.\\s]+(오전|오후)?\\s*([0-9]{1,2})\\s*(?:시|:)\\s*([0-9]{1,2})\\s*분?"
        )
        val gm = gpDtRegex.find(text)
        if (gm != null) {
            return try {
                val y = gm.groupValues[1].toInt()
                val mon = gm.groupValues[2].toInt() - 1
                val d = gm.groupValues[3].toInt()
                val ampm = gm.groupValues[4]
                val rawH = gm.groupValues[5].toInt()
                val min = gm.groupValues[6].toInt()
                val h = when {
                    ampm == "오후" && rawH < 12 -> rawH + 12
                    ampm == "오전" && rawH == 12 -> 0
                    else -> rawH
                }
                Calendar.getInstance().apply {
                    set(y, mon, d, h, min, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } catch (_: Exception) {
                null
            }
        }

        return null
    }

    fun parse(title: String, body: String, fullText: String, rules: List<AutoRule> = emptyList()): ParsedPayment? {
        val deduped = deduplicateLines(fullText)
        val cleanBody = stripBoilerplate(deduped)

        val deleteRule = rules.firstOrNull {
            it.action == "delete" && it.keyword.isNotBlank() && cleanBody.contains(it.keyword, ignoreCase = true)
        }
        if (deleteRule != null) return null

        if (!containsPaymentKeyword(cleanBody)) return null

        val actualTimestamp = extractActualPaymentTimestamp(cleanBody)

        val withoutBalance = cleanBody.replace(Regex("잔액\\s*[:：]?\\s*[0-9,]+\\s*원?"), "")
        val totalAmtRegex = Regex(
            "(?:총\\s*결제\\s*금액|실\\s*결제\\s*금액|최종\\s*결제\\s*금액|총\\s*주문\\s*금액|결제\\s*금액|승인\\s*금액|출금\\s*금액|입금\\s*금액|이체\\s*금액|합계\\s*금액|상품\\s*금액|총액|합계)\\s*[:：]?\\s*(?:(?:₩|KRW)\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})|([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\\s*원)"
        )
        val bankTxnAmtRegex = Regex(
            "(?:출금|입금|이체|송금)\\s*[:：]?\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\\s*원"
        )
        val cardAmtRegex = Regex(
            "(?:카드|일시불|할부|페이)\\s*(?:\\([^)]*\\))?\\s*[:：/]\\s*(?:₩|KRW)?\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\\s*원?"
        )
        val fallbackAmtRegex = Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})\\s*원|(?:₩|KRW)\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{2,})")

        val rawNum = totalAmtRegex.find(withoutBalance)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?: bankTxnAmtRegex.find(withoutBalance)?.groupValues?.get(1)
            ?: cardAmtRegex.find(withoutBalance)?.groupValues?.get(1)
            ?: fallbackAmtRegex.find(withoutBalance)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?: return null

        val amount = rawNum.replace(",", "").toLongOrNull() ?: return null
        if (amount <= 0L) return null

        val balanceMatch = Regex("잔액\\s*[:：]?\\s*([0-9,]+)\\s*원?").find(cleanBody)
        val balanceInfo = if (balanceMatch != null) "잔액 ${balanceMatch.groupValues[1]}원" else ""

        var fromAccount = ""
        var toAccount = ""
        val detectedBank = BANK_NAMES.firstOrNull { cleanBody.contains(it) } ?: ""
        val isCardApproval = cleanBody.contains("승인") || cleanBody.contains("카드") || cleanBody.contains("결제") || cleanBody.contains("Google Play")

        if (detectedBank.isNotEmpty() && !isCardApproval) {
            val afterAcctRegex = Regex("[0-9*]{2,6}-[0-9*]{2,6}-[0-9*]{2,6}(?:-[0-9*]{1,4})?\\s+([^\\n\\r]+?)(?=\\s*잔액|\\s*$|[\n\r])")
            val afterTimeRegex = Regex("[0-9]{1,2}:[0-9]{2}\\s+([^\\n\\r]+?)(?=\\s*잔액|\\s*$|[\n\r])")
            val counterparty = (afterAcctRegex.find(cleanBody)?.groupValues?.get(1)
                ?: afterTimeRegex.find(cleanBody)?.groupValues?.get(1)
                ?: "").replace(Regex("^[0-9*\\-\\s]+"), "").trim()

            if (cleanBody.contains("출금") || cleanBody.contains("송금")) {
                fromAccount = detectedBank
                toAccount = counterparty
            } else if (cleanBody.contains("입금")) {
                toAccount = detectedBank
                fromAccount = counterparty
            }
        } else {
            val payOrg = Regex("결제\\s*기관[ \\t]*[:：][ \\t]*([^\\n\\r]+)").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val cardKind = Regex("카드\\s*종류[ \\t]*[:：][ \\t]*([^\\n\\r]+)").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val payMethodSameLine = Regex("결제\\s*(?:수단|방법)[ \\t]*[:：][ \\t]*([^\\n\\r]+)").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val payMethodNextLine = if (payMethodSameLine.isEmpty()) {
                Regex("결제\\s*(?:수단|방법)[ \\t]*[:：][ \\t]*\n+[ \\t]*([^\\n\\r]+)").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            } else ""
            val payMethod = payMethodSameLine.ifEmpty { payMethodNextLine }

            fromAccount = when {
                payOrg.isNotEmpty() && payMethod.isNotEmpty() -> "$payOrg $payMethod"
                cardKind.isNotEmpty() -> cardKind
                payMethod.contains("/") -> payMethod.substringAfter("/").trim()
                payMethod.isNotEmpty() -> payMethod
                payOrg.isNotEmpty() -> payOrg
                else -> ""
            }
        }

        val matchedTransferRule = rules.firstOrNull {
            it.action == "transfer" && it.keyword.isNotBlank() && cleanBody.contains(it.keyword, ignoreCase = true)
        }
        val matchedCategoryRule = rules.firstOrNull {
            it.action == "category" && it.keyword.isNotBlank() && cleanBody.contains(it.keyword, ignoreCase = true)
        }

        val sanitizedForType = cleanBody
            .replace("부분취소카드", "")
            .replace(Regex("할인금액\\s*[:：]\\s*-[0-9,]+원"), "")

        val isInternalTransfer = (matchedTransferRule != null && !isCardApproval)

        val isIncome = !isInternalTransfer && (
            matchedCategoryRule?.category == "수입" ||
            sanitizedForType.contains("결제취소") ||
            sanitizedForType.contains("승인취소") ||
            sanitizedForType.contains("주문취소") ||
            sanitizedForType.contains("취소완료") ||
            sanitizedForType.contains("환불완료") ||
            sanitizedForType.contains("환불금액") ||
            Regex("(?:입금|급여|용돈|이체입금|계좌입금)\\s*(?:완료|금액|[:：]|[0-9,]+원)").containsMatchIn(sanitizedForType)
        )

        val type = when {
            isInternalTransfer || matchedCategoryRule?.category == "이체/계좌이동" -> "trn"
            isIncome -> "inc"
            else -> "exp"
        }

        val labeledMerchantRegex = Regex("(?:상호|구매상점명|가맹점명|상점명|사용처|결제처)\\s*[:：]\\s*([^\\n\\r]+)")
        val labeledMerchant = labeledMerchantRegex.find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val bracketBrand = Regex("\\[([^\\]]+)]").findAll(title)
            .map { it.groupValues[1].trim() }
            .firstOrNull { tag -> GENERIC_TAGS.none { tag.equals(it, ignoreCase = true) || tag.contains(it, ignoreCase = true) } } ?: ""

        val orderedAtMerchant = Regex("([가-힣a-zA-Z0-9()]{2,20})에서\\s*(?:\\[[^\\]]+\\]\\s*)?(?:주문하신|결제하신)")
            .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val cleanTitle = title.replace(Regex("\\[.*?\\]"), "").trim()
        val isPhoneNumberTitle = cleanTitle.matches(Regex("^[0-9\\-]{7,15}$"))
        val isGenericTitle = cleanTitle.isEmpty() ||
            isPhoneNumberTitle ||
            cleanTitle.contains("주문하신 내역") ||
            cleanTitle.contains("결제하신 내역") ||
            cleanTitle.contains("결제 내역입니다") ||
            PG_OR_GENERIC_NAMES.any { cleanTitle.contains(it, ignoreCase = true) }

        val transferRouteTitle = if (type == "trn" && (fromAccount.isNotEmpty() || toAccount.isNotEmpty())) {
            when {
                fromAccount.isNotEmpty() && toAccount.isNotEmpty() -> "$fromAccount → $toAccount"
                fromAccount.isNotEmpty() -> "$fromAccount 출금"
                else -> "$toAccount 입금"
            }
        } else ""

        val rawMerchant = when {
            transferRouteTitle.isNotEmpty() -> transferRouteTitle
            labeledMerchant.isNotEmpty() -> labeledMerchant
            title.contains("Google Play", ignoreCase = true) || cleanBody.contains("Google Play 주문 영수증") -> "Google Play"
            bracketBrand.isNotEmpty() -> bracketBrand
            orderedAtMerchant.isNotEmpty() -> orderedAtMerchant
            !isGenericTitle -> cleanTitle
            else -> {
                val lines = cleanBody.replace(Regex("\\[.*?\\]"), "")
                    .split("\n")
                    .map { it.trim() }
                    .filter { line ->
                        line.isNotEmpty() &&
                            !line.matches(Regex("^[0-9\\-]{7,15}$")) &&
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
            val labeledItem = Regex("(?:주문상품명|상품명|품목명|품목)\\s*[:：]\\s*([^\\n\\r]+)")
                .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val membershipItem = Regex("([가-힣a-zA-Z0-9\\s]+(?:월회비|멤버십))(?:\\s*결제내역)?")
                .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
            val validPeriod = Regex("적용기간\\s*[:：]\\s*([^\\n\\r]+)")
                .find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

            val resolvedItemName = labeledItem.ifEmpty { membershipItem }

            if (resolvedItemName.isNotEmpty()) {
                val periodSuffix = if (validPeriod.isNotEmpty()) " ($validPeriod)" else " (1개)"
                itemSummary = "• $resolvedItemName$periodSuffix : ${nf.format(amount)}원"
                totalQuantity = if (validPeriod.isNotEmpty()) "1개월" else "1개"
            } else {
                val safeLines = cleanBody.split("\n").filter { line ->
                    !line.contains("사업자") && !line.contains("통신판매") &&
                        !line.contains("승인번호") && !line.contains("연락처") && !line.contains("고객센터")
                }
                val safeBody = safeLines.joinToString("\n")
                val totalCountMatch = Regex("(총[ \\t]*[0-9]{1,3}[ \\t]*(?:건|개|종))").find(safeBody)?.groupValues?.get(1)?.trim() ?: ""
                val singleQtyMatch = Regex("(?<![0-9\\-])([1-9][0-9]{0,2}[ \\t]*(?:개|잔|건|박스|병|팩|매|세트|봉|통|권))(?!입)")
                    .find(safeBody)?.groupValues?.get(1)?.trim() ?: ""

                itemSummary = ""
                totalQuantity = totalCountMatch.ifEmpty { singleQtyMatch }
            }
        }

        val category = when {
            type == "trn" -> "이체/계좌이동"
            matchedCategoryRule != null -> matchedCategoryRule.category
            type == "inc" -> "수입"
            else -> classifyCategory("$rawMerchant $itemSummary $cleanBody")
        }

        val isCoupangEatsOrder = rawMerchant.replace(" ", "").contains("쿠팡", ignoreCase = true) &&
            !rawMerchant.contains("쿠팡이츠") &&
            (category == "식비" || FOOD_KEYWORDS.any { "$itemSummary $cleanBody".contains(it, ignoreCase = true) })
        val merchant = if (isCoupangEatsOrder) "쿠팡이츠" else rawMerchant.ifEmpty { "거래 내역" }

        val discountText = Regex("할인금액\\s*[:：]\\s*([^\\n\\r]+)").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""
        val shippingText = Regex("배송비\\s*[:：]\\s*([^\\n\\r]+)").find(cleanBody)?.groupValues?.get(1)?.trim() ?: ""

        val formattedRaw = buildString {
            append("[거래 요약]\n")
            if (type == "trn" && (fromAccount.isNotEmpty() || toAccount.isNotEmpty())) {
                append("• 이동 경로: ${fromAccount.ifEmpty { "-" }} → ${toAccount.ifEmpty { "-" }}\n")
            } else {
                append("• 결제처: $merchant\n")
                if (fromAccount.isNotEmpty()) {
                    append("• 결제수단: $fromAccount\n")
                }
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

        return ParsedPayment(
            type = type,
            category = category,
            merchant = merchant,
            fromAccount = fromAccount,
            toAccount = toAccount,
            balanceInfo = balanceInfo,
            itemSummary = itemSummary,
            totalQuantity = totalQuantity,
            amount = amount,
            cleanRawText = formattedRaw,
            itemsJson = itemsJsonArr.toString(),
            parsedTimestamp = actualTimestamp
        )
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val result = mutableListOf<LineItem>()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val tableRowRegex = Regex(
            "^(.+?)\\s*:\\s*([0-9,]+)원\\s*:\\s*([0-9]+)\\s*:\\s*([0-9,]+)원(?:\\s*:.*)?$"
        )
        val simpleRowRegex = Regex(
            "^(.+?)\\s*:\\s*(?:(?:₩|KRW)\\s*([0-9,]+)|([0-9,]+)\\s*원)(?:\\s*/\\s*(?:월|년|주|일))?$"
        )
        val inlineCurrencyRowRegex = Regex(
            "^(.+?)\\s+(?:₩|KRW)\\s*([0-9,]+)(?:\\s*/\\s*(?:월|년|주|일))?$"
        )

        val skipLabels = listOf(
            "상품 금액", "상품금액", "할인금액", "할인 금액", "배송비", "총 결제금액",
            "결제금액", "실결제금액", "일시불", "할부", "카드", "승인금액", "포인트",
            "쿠폰", "잔액", "배달비", "총액", "합계", "부가세", "세금", "VAT"
        )

        for (line in lines) {
            if (line.startsWith("(") && line.endsWith(")")) continue

            val mA = tableRowRegex.find(line)
            if (mA != null) {
                var rawName = mA.groupValues[1].trim()
                val qty = mA.groupValues[3].toIntOrNull() ?: 1
                val itemTotalPrice = mA.groupValues[4].replace(",", "").toLongOrNull() ?: 0L

                rawName = rawName.replace(Regex("[,\\s]+${qty}개$"), "").trim()
                if (rawName.isNotEmpty() && itemTotalPrice > 0L) {
                    result.add(LineItem(rawName, qty, itemTotalPrice))
                }
                continue
            }

            val mB = simpleRowRegex.find(line)
            if (mB != null) {
                val labelPart = mB.groupValues[1].trim()
                val rawPriceStr = mB.groupValues[2].ifEmpty { mB.groupValues[3] }
                val pricePart = rawPriceStr.replace(",", "").toLongOrNull() ?: 0L
                if (pricePart > 0L &&
                    !labelPart.contains("/") &&
                    skipLabels.none { labelPart.contains(it, ignoreCase = true) }
                ) {
                    val qtyInName = Regex("[,\\s]+([0-9]+)개$").find(labelPart)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val cleanName = labelPart.replace(Regex("[,\\s]+[0-9]+개$"), "").trim()
                    if (cleanName.length >= 2) {
                        result.add(LineItem(cleanName, qtyInName, pricePart))
                    }
                }
                continue
            }

            val mC = inlineCurrencyRowRegex.find(line)
            if (mC != null) {
                val labelPart = mC.groupValues[1].trim()
                val pricePart = mC.groupValues[2].replace(",", "").toLongOrNull() ?: 0L
                if (pricePart > 0L &&
                    skipLabels.none { labelPart.contains(it, ignoreCase = true) }
                ) {
                    val qtyInName = Regex("[,\\s]+([0-9]+)개$").find(labelPart)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val cleanName = labelPart.replace(Regex("[,\\s]+[0-9]+개$"), "").trim()
                    if (cleanName.length >= 2) {
                        result.add(LineItem(cleanName, qtyInName, pricePart))
                    }
                }
            }
        }
        return result
    }

    private fun classifyCategory(text: String): String {
        for ((cat, words) in CATEGORY_RULES) {
            if (words.any { text.contains(it, ignoreCase = true) }) {
                return cat
            }
        }
        return "기타"
    }
}
