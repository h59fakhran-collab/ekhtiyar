package com.example.ekhtiyar

// پردازش نام و نماد اختیار معامله
// منبع: norm_fa (خط 58)، parse_strike (خط 62)،
//        parse_expiry (خط 69)، شرط تشخیص آپشن (خط 475)
object OptionParser {

    // معادل norm_fa : یکدست‌سازی حروف عربی به فارسی و حذف نیم‌فاصله
    fun normFa(s: String?): String {
        if (s == null) return ""
        return s.replace("ي", "ی")
            .replace("ك", "ک")
            .replace("\u200c", "")
            .trim()
    }

    // معادل parse_strike : استخراج قیمت اعمال (K)
    fun parseStrike(name: String?): Int {
        val n = normFa(name)

        // اول دنبال عددِ بعد از کلمه‌ی «اعمال» می‌گردیم
        val m = Regex("اعمال\\s*(\\d+)").find(n)
        if (m != null) {
            return m.groupValues[1].toIntOrNull() ?: 0
        }

        // در غیر این صورت همه‌ی اعداد ≥ 3 رقمی را می‌گیریم
        // و اعداد تاریخ شمسی (۴ رقمی که با 14 شروع می‌شوند) را نادیده می‌گیریم
        val nums = Regex("\\d+").findAll(n)
            .map { it.value }
            .filter { it.length >= 3 && !(it.length == 4 && it.startsWith("14")) }
            .mapNotNull { it.toIntOrNull() }
            .toList()

        return if (nums.isNotEmpty()) nums.last() else 0
    }

    // معادل parse_expiry : استخراج تاریخ سررسید شمسی
    fun parseExpiry(name: String?): String {
        val n = normFa(name)

        // حالت اول: 14xx/xx/xx
        val m = Regex("(14\\d{2}/\\d{2}/\\d{2})").find(n)
        if (m != null) return m.groupValues[1]

        // حالت دوم: 14xx-xx-xx  که خط تیره را به اسلش تبدیل می‌کنیم
        val m2 = Regex("(14\\d{2}-\\d{2}-\\d{2})").find(n)
        return if (m2 != null) m2.groupValues[1].replace("-", "/") else ""
    }

    // معادل شرط تشخیص آپشنِ خرید (خط 475)
    fun isOption(symbol: String?, name: String?): Boolean {
        val sym = normFa(symbol)
        val nm = normFa(name)
        val byName = nm.contains("اختیار") && nm.contains("خرید")
        val bySymbol = sym.startsWith("ض") && sym.any { it.isDigit() }
        return byName || bySymbol
    }
}
