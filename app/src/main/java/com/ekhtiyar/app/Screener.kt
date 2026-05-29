package com.ekhtiyar.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * موتور اصلی اسکنر اختیار معامله.
 * شامل دو حالت اسکن: Jump Scan و Dual Scan
 * منطق دقیقاً برگرفته از نسخه‌ی پایتون IranOptionsScreenerGUI است.
 */
object Screener {

    /** آیا این ردیف یک اختیار خرید است؟ */
    private fun isCallOption(name: String, sym: String): Boolean {
        val n = OptionParser.normFa(name)
        val s = OptionParser.normFa(sym)
        val byName = n.contains("اختیار") && n.contains("خرید")
        val bySym = s.startsWith("ض") && s.any { it.isDigit() }
        return byName || bySym
    }

    /** پیدا کردن نماد پایه برای یک اختیار */
    private fun findBaseMatch(name: String, sym: String, targets: List<String>): String? {
        val n = OptionParser.normFa(name)
        for (t in targets) {
            if (n.contains(t)) return t
        }
        // فال‌بک: حذف حرف اول و رقم‌های انتهایی نماد
        if (sym.length > 1) {
            val cleanSym = sym.substring(1).replace(Regex("\\d+$"), "")
            if (targets.contains(cleanSym)) return cleanSym
        }
        return null
    }

    /** محاسبه‌ی روز مانده تا سررسید */
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
     * Jump Scan (فیلتر عمومی / سبک PMCC)
     * @param market نقشه‌ی داده‌های بازار: کلید = insCode
     * @param targets لیست نمادهای پایه‌ای که می‌خواهیم اسکن کنیم
     * @param minP حداقل قیمت خریدار (پیش‌فرض 1.0)
     * @param minMonthlyPct حداقل سود ماهانه درصدی (پیش‌فرض 4.0)
     * @param prevApproved مجموعه‌ی نمادهای تاییدشده‌ی اسکن قبلی (برای علامت is_new)
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

        for (data in market.values) {
            val name = data.name
            val sym = data.code.let { OptionParser.normFa(data.symbol) }

            if (!isCallOption(name, sym)) continue

            val baseMatch = findBaseMatch(name, sym, targets) ?: continue

            // قیمت دارایی پایه: اول بهترین فروشنده، اگر صفر بود آخرین قیمت
            val baseData = market.values.firstOrNull {
                OptionParser.normFa(it.symbol) == baseMatch || OptionParser.normFa(it.name).contains(baseMatch)
            }
            var s = baseData?.bestAsk ?: 0.0
            if (s == 0.0) s = baseData?.lastPrice ?: 0.0

            val p = data.bestBid
            val v = data.bestVol
            val k = OptionParser.parseStrike(name).toDouble()
            val expiry = OptionParser.parseExpiry(name)
            val daysLeft = computeDaysLeft(expiry, today)

            val kp = k + p
            val absPct = if (s > 0) ((kp / s - 1.0) * 100.0) else 0.0
            val safeDays = maxOf(1, daysLeft)
            val monthlyPct = if (daysLeft >= 0) (absPct / safeDays) * 30.0 else 0.0

            var ok = true
            var status = "✔ تایید"
            when {
                k <= 0 || s <= 0 -> { ok = false; status = "دیتا ناقص" }
                daysLeft < 0 -> { ok = false; status = "منقضی" }
                p <= 0 -> { ok = false; status = "بدون خریدار (P=0)" }
                p < minP -> { ok = false; status = "P کم" }
                k >= s -> { ok = false; status = "OTM/ATM نیست" }
                monthlyPct < minMonthlyPct -> { ok = false; status = "سود کم (%.1f%%)".format(monthlyPct) }
            }

            val isNew = ok && (sym !in prevApproved) && prevApproved.isNotEmpty()

            rows.add(
                JumpRow(
                    base = baseMatch,
                    optionSym = sym,
                    expiry = expiry,
                    daysLeft = daysLeft,
                    k = k,
                    s = s,
                    p = p,
                    volP = v.toInt(),
                    kp = kp,
                    absPct = Math.round(absPct * 100.0) / 100.0,
                    monthlyPct = Math.round(monthlyPct * 100.0) / 100.0,
                    status = status,
                    ok = ok,
                    isNew = isNew
                )
            )
        }
        return rows
    }

    /**
     * Dual Scan
     * اختیارها را بر اساس (پایه + سررسید) گروه می‌کند و جفت‌های strike پایین/بالا را مقایسه می‌کند.
     */
    fun dualScan(
        market: Map<String, MarketInstrument>,
        targets: List<String>
    ): List<DualRow> {

        data class Opt(
            val base: String,
            val sym: String,
            val expiry: String,
            val strike: Double,
            val bid: Double,
            val ask: Double
        )

        val options = ArrayList<Opt>()

        for (data in market.values) {
            val name = data.name
            val sym = OptionParser.normFa(data.symbol)
            if (!isCallOption(name, sym)) continue
            val baseMatch = findBaseMatch(name, sym, targets) ?: continue
            val strike = OptionParser.parseStrike(name).toDouble()
            val expiry = OptionParser.parseExpiry(name)
            if (expiry.isBlank() || strike <= 0) continue

            options.add(
                Opt(
                    base = baseMatch,
                    sym = sym,
                    expiry = expiry,
                    strike = strike,
                    bid = data.bestBid,
                    ask = data.bestAsk
                )
            )
        }

        // گروه‌بندی بر اساس (پایه، سررسید)
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

                    val highlight = when {
                        f1 >= 0 -> "green"
                        f2 < 0 -> "yellow"
                        else -> "none"
                    }
                    if (highlight == "none") continue

                    results.add(
                        DualRow(
                            base = base,
                            expiry = expiry,
                            lowSym = low.sym,
                            highSym = high.sym,
                            lowStrike = low.strike,
                            highStrike = high.strike,
                            f1 = Math.round(f1 * 100.0) / 100.0,
                            f2 = Math.round(f2 * 100.0) / 100.0,
                            highlight = highlight
                        )
                    )
                }
            }
        }
        return results
    }
}
