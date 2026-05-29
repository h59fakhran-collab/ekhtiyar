package com.example.ekhtiyar

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * موتور اصلی اسکنر اختیار معامله.
 * منطق دقیقاً برگرفته از IranOptionsScreenerGUI.py
 * نکته: نقشه‌ی بازار با کلید «نماد» ساخته می‌شود (مطابق market_map پایتون).
 */
object Screener {

    /** تشخیص اختیار خرید — مطابق خط ۴۷۵ پایتون */
    private fun isOption(name: String, sym: String): Boolean {
        val byName = name.contains("اختیار") && name.contains("خرید")
        val bySym = sym.startsWith("ض") && sym.any { it.isDigit() }
        return byName || bySym
    }

    /** پیدا کردن نماد پایه — مطابق خطوط ۴۷۶ تا ۴۸۱ پایتون */
    private fun findBaseMatch(name: String, sym: String, targets: List<String>): String? {
        for (t in targets) {
            if (name.contains(t)) return t
        }
        if (sym.length > 1) {
            val cleanSym = sym.substring(1).replace(Regex("\\d+$"), "")
            if (cleanSym in targets) return cleanSym
        }
        return null
    }

    /** محاسبه‌ی روز مانده تا سررسید — مطابق خطوط ۴۹۲ تا ۴۹۸ */
    private fun computeDaysLeft(expiry: String, today: LocalDate): Int {
        if (expiry.isBlank()) return -1
        return try {
            val parts = expiry.split("/").map { it.toInt() }
            val greg = JalaliDate.jalaliToGregorian(parts[0], parts[1], parts[2])
            ChronoUnit.DAYS.between(today, greg).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Jump Scan (فیلتر عمومی / PMCC) — مطابق _process_general_filter
     * @param market نقشه‌ی بازار، کلید = نماد
     * @param targets نمادهای پایه
     * @param minP پیش‌فرض 1.0
     * @param minMonthlyPct پیش‌فرض 4.0
     * @param prevApproved نمادهای تاییدشده‌ی اسکن قبلی (برای is_new)
     */
    fun jumpScan(
        market: Map<String, MarketInstrument>,
        targets: List<String>,
        minP: Double = 1.0,
        minMonthlyPct: Double = 4.0,
        prevApproved: Set<String> = emptySet(),
        today: LocalDate = LocalDate.now()
    ): List<JumpRow> {

        val rows = ArrayList<JumpRow>()

        for ((sym, data) in market) {
            val name = data.name
            if (!isOption(name, sym)) continue

            val baseMatch = findBaseMatch(name, sym, targets) ?: continue

            // S = best_ask پایه، اگر صفر بود last_price
            val baseData = market[baseMatch]
            var s = baseData?.bestAsk ?: 0.0
            if (s == 0.0) s = baseData?.lastPrice ?: 0.0

            val p = data.bestBid
            val v = data.bestVol
            val kNum = OptionParser.parseStrike(name)
            val k = kNum.toInt()
            val kD = kNum.toDouble()
            val expiry = OptionParser.parseExpiry(name)
            val daysLeft = computeDaysLeft(expiry, today)

            val kp = kD + p
            val absPct = if (s > 0) ((kp / s - 1.0) * 100.0) else 0.0
            val safeDays = maxOf(1, daysLeft)
            val monthlyPct = if (daysLeft >= 0) (absPct / safeDays) * 30.0 else 0.0

            var ok = true
            var status = "✔ تایید"
            when {
                kD <= 0 || s <= 0 -> { ok = false; status = "دیتا ناقص" }
                daysLeft < 0 -> { ok = false; status = "منقضی" }
                p <= 0 -> { ok = false; status = "بدون خریدار (P=0)" }
                p < minP -> { ok = false; status = "P کم" }
                kD >= s -> { ok = false; status = "OTM/ATM نیست" }
                monthlyPct < minMonthlyPct -> { ok = false; status = "سود کم (%.1f%%)".format(monthlyPct) }
            }

            val isNew = ok && (sym !in prevApproved) && prevApproved.isNotEmpty()

            rows.add(
                JumpRow(
                    base = baseMatch,
                    optionSymbol = sym,
                    expiry = expiry,
                    daysLeft = daysLeft,
                    strike = k,
                    underlyingPrice = s,
                    bid = p,
                    bidVolume = v.toInt(),
                    kPlusP = kp,
                    absoluteProfitPct = Math.round(absPct * 100.0) / 100.0,
                    monthlyProfitPct = Math.round(monthlyPct * 100.0) / 100.0,
                    status = status,
                    ok = ok,
                    isNew = isNew
                )
            )
        }
        return rows
    }

    /**
     * Dual Scan — گروه‌بندی بر اساس (پایه + سررسید) و مقایسه‌ی جفت strike پایین/بالا.
     * توجه: فرمول دقیق تیک سبز/زرد باید با _process_tick_profit پایتون نهایی شود.
     */
    fun dualScan(
        market: Map<String, MarketInstrument>,
        targets: List<String>
    ): List<DualRow> {

        val options = ArrayList<DualOption>()

        for ((sym, data) in market) {
            val name = data.name
            if (!isOption(name, sym)) continue
            val baseMatch = findBaseMatch(name, sym, targets) ?: continue
            val strike = OptionParser.parseStrike(name).toDouble()
            val expiry = OptionParser.parseExpiry(name)
            if (expiry.isBlank() || strike <= 0) continue

            options.add(
                DualOption(
                    base = baseMatch,
                    symbol = sym,
                    expiry = expiry,
                    strike = strike,
                    bid = data.bestBid,
                    ask = data.bestAsk
                )
            )
        }

        val grouped = options.groupBy { it.base to it.expiry }
        val results = ArrayList<DualRow>()

        for ((key, group) in grouped) {
            val (base, expiry) = key
            val sorted = group.sortedBy { it.strike }
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    val low = sorted[i]
                    val high = sorted[j]
                    if (low.strike == high.strike) continue

                    val f1 = (low.strike + low.bid) - (high.strike + high.ask)
                    val f2 = (low.strike + low.ask) - (high.strike + high.bid)

                    val tag = when {
                        f1 >= 0 -> "tick_green"
                        f2 < 0 -> "tick_yellow"
                        else -> continue
                    }

                    results.add(
                        DualRow(
                            base = base,
                            expiry = expiry,
                            lowSymbol = low.symbol,
                            highSymbol = high.symbol,
                            lowStrike = low.strike,
                            highStrike = high.strike,
                            lowBid = low.bid,
                            lowAsk = low.ask,
                            highBid = high.bid,
                            highAsk = high.ask,
                            f1 = Math.round(f1 * 100.0) / 100.0,
                            f2 = Math.round(f2 * 100.0) / 100.0,
                            tag = tag
                        )
                    )
                }
            }
        }
        return results
    }
}
