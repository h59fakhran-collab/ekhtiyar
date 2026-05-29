package com.example.ekhtiyar

import java.time.LocalDate

object Screener {

    // ---------- JUMP SCAN ----------
    // مطابق خطوط 463–523 پایتون
    fun jumpScan(
        instruments: List<MarketInstrument>,
        marketData: Map<String, MarketInstrument>,
        targets: List<String>,
        minMonthlyPct: Double = 4.0,   // پیش‌فرض پایتون
        minP: Double = 1.0,            // پیش‌فرض پایتون
        today: LocalDate = LocalDate.now(),
        prevApproved: Set<String> = emptySet()
    ): List<JumpRow> {

        val rows = ArrayList<JumpRow>()

        for (inst in instruments) {
            val sym = inst.symbol
            val name = inst.name

            // base_match (خطوط 476–481)
            val base = matchBase(sym, name, targets) ?: continue

            // S: best_ask پایه، اگر 0 بود last_price
            val baseData = marketData[base]
            var s = baseData?.bestAsk ?: 0.0
            if (s == 0.0) s = baseData?.lastPrice ?: 0.0

            val p = inst.bestBid
            val v = inst.bestVol
            val k = OptionParser.parseStrike(name)
            val expiry = OptionParser.parseExpiry(name)

            // days_left
            var daysLeft = -1
            if (!expiry.isNullOrBlank()) {
                val parts = expiry.split("/").map { it.toInt() }
                daysLeft = JalaliDate.daysLeft(parts[0], parts[1], parts[2], today)
            }

            // فرمول‌ها (خطوط دقیق پایتون)
            val kp = k + p
            val absPct = if (s > 0) ((kp / s - 1.0) * 100.0) else 0.0
            val safeDays = maxOf(1, daysLeft)
            val monthlyPct = if (daysLeft >= 0) (absPct / safeDays) * 30.0 else 0.0

            // منطق پذیرش (ترتیب elif مهمه)
            var ok = true
            var status = "✔ تایید"
            when {
                k <= 0 || s <= 0 -> { ok = false; status = "دیتا ناقص" }
                daysLeft < 0     -> { ok = false; status = "منقضی" }
                p <= 0           -> { ok = false; status = "بدون خریدار (P=0)" }
                p < minP         -> { ok = false; status = "P کم" }
                k >= s           -> { ok = false; status = "OTM/ATM نیست" }
                monthlyPct < minMonthlyPct -> {
                    ok = false
                    status = "سود کم (${"%.1f".format(monthlyPct)}%)"
                }
            }

            val isNew = ok && (sym !in prevApproved)

            rows.add(
                JumpRow(
                    base = base,
                    sym = sym,
                    expiry = expiry ?: "",
                    daysLeft = daysLeft,
                    k = k,
                    s = s,
                    p = p,
                    volP = v.toInt(),
                    kp = kp,
                    absPct = round2(absPct),
                    monthlyPct = round2(monthlyPct),
                    status = status,
                    ok = ok,
                    isNew = isNew
                )
            )
        }
        return rows
    }

    // ---------- DUAL SCAN ----------
    // مطابق خطوط 829–935 پایتون
    fun dualScan(
        instruments: List<MarketInstrument>,
        targets: List<String>
    ): List<DualRow> {

        // ساخت لیست آپشن‌ها
        data class Op(
            val base: String, val sym: String, val expiry: String,
            val strike: Double, val bid: Double, val ask: Double
        )

        val options = ArrayList<Op>()
        for (inst in instruments) {
            val sym = inst.symbol
            val name = inst.name
            val base = matchBase(sym, name, targets) ?: continue
            val expiry = OptionParser.parseExpiry(name) ?: continue
            val strike = OptionParser.parseStrike(name)
            if (strike <= 0) continue
            options.add(
                Op(base, sym, expiry, strike, inst.bestBid, inst.bestAsk)
            )
        }

        // گروه‌بندی بر اساس (base, expiry)
        val grouped = options.groupBy { it.base to it.expiry }

        val green = ArrayList<DualRow>()
        val yellow = ArrayList<DualRow>()

        for ((key, listRaw) in grouped) {
            val lst = listRaw.sortedBy { it.strike }
            for (i in lst.indices) {
                val low = lst[i]
                for (j in i + 1 until lst.size) {
                    val high = lst[j]
                    if (low.strike >= high.strike) continue

                    val f1 = (low.strike + low.bid) - (high.strike + high.ask)
                    val f2 = (low.strike + low.ask) - (high.strike + high.bid)

                    // سبز و زرد مستقل از هم (مطابق پایتون)
                    if (f1 >= 0) {
                        green.add(
                            DualRow(
                                base = key.first,
                                expiry = key.second,
                                lowSym = low.sym, lowStrike = low.strike,
                                highSym = high.sym, highStrike = high.strike,
                                f1 = round2(f1), f2 = round2(f2),
                                tag = "tick_green"
                            )
                        )
                    }
                    if (f2 < 0) {
                        yellow.add(
                            DualRow(
                                base = key.first,
                                expiry = key.second,
                                lowSym = low.sym, lowStrike = low.strike,
                                highSym = high.sym, highStrike = high.strike,
                                f1 = round2(f1), f2 = round2(f2),
                                tag = "tick_yellow"
                            )
                        )
                    }
                }
            }
        }

        // مرتب‌سازی: سبز بر اساس f1 نزولی، زرد بر اساس f2 صعودی
        green.sortByDescending { it.f1 }
        yellow.sortBy { it.f2 }
        return green + yellow
    }

    // ---------- HELPERS ----------

    // base_match (خطوط 476–481)
    private fun matchBase(sym: String, name: String, targets: List<String>): String? {
        for (target in targets) {
            if (name.contains(target)) return target
        }
        // clean_sym = re.sub(r"\d+$", "", sym[1:])
        if (sym.length > 1) {
            val cleanSym = sym.substring(1).replace(Regex("\\d+$"), "")
            if (cleanSym in targets) return cleanSym
        }
        return null
    }

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
}
