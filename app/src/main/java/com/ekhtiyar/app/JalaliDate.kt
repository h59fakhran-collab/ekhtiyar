package com.ekhtiyar.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * تبدیل تاریخ شمسی (جلالی) به میلادی و محاسبه‌ی روزهای مانده تا سررسید.
 *
 * این پیاده‌سازی دقیقاً معادل تابع پایتونی jalali_to_gregorian است
 * (IranOptionsScreenerGUI.txt، خطوط ۳۲ تا ۶۰).
 *
 * از Math.floorDiv / Math.floorMod استفاده شده تا رفتار تقسیم و باقیمانده
 * با عملگرهای // و % پایتون کاملاً یکسان باشد.
 */
object JalaliDate {

    /**
     * معادل مستقیم:
     *   def jalali_to_gregorian(jy, jm, jd) -> date
     */
    fun jalaliToGregorian(jYear: Int, jMonth: Int, jDay: Int): LocalDate {
        var jy = jYear + 1595
        var days = -355668 +
                (365 * jy) +
                (Math.floorDiv(jy, 33) * 8) +
                Math.floorDiv((Math.floorMod(jy, 33) + 3), 4) +
                jDay

        days += if (jMonth < 7) {
            (jMonth - 1) * 31
        } else {
            ((jMonth - 7) * 30) + 186
        }

        var gy = 400 * Math.floorDiv(days, 146097)
        days = Math.floorMod(days, 146097)

        if (days > 36524) {
            days -= 1
            gy += 100 * Math.floorDiv(days, 36524)
            days = Math.floorMod(days, 36524)
            if (days >= 365) days += 1
        }

        gy += 4 * Math.floorDiv(days, 1461)
        days = Math.floorMod(days, 1461)

        if (days > 365) {
            gy += Math.floorDiv((days - 1), 365)
            days = Math.floorMod((days - 1), 365)
        }

        var gd = days + 1

        // sal_a = [0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        val salA = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if ((gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)) {
            salA[2] = 29
        }

        var gm = 1
        while (gm < 13 && gd > salA[gm]) {
            gd -= salA[gm]
            gm += 1
        }

        return LocalDate.of(gy, gm, gd)
    }

    /**
     * معادل منطق اسکنر:
     *   jy, jm, jd = map(int, expiry.split('/'))
     *   exp_greg_date = jalali_to_gregorian(jy, jm, jd)
     *   days_left = (exp_greg_date - today_date).days
     *
     * @param expiry رشته‌ی تاریخ شمسی به شکل "1404/03/20"
     * @param today  تاریخ مبنا (پیش‌فرض: امروز)
     * @return تعداد روزهای مانده، یا null اگر فرمت تاریخ نامعتبر باشد.
     */
    fun daysLeft(expiry: String, today: LocalDate = LocalDate.now()): Int? {
        val parts = expiry.trim().split("/")
        if (parts.size != 3) return null

        val jy = parts[0].toIntOrNull() ?: return null
        val jm = parts[1].toIntOrNull() ?: return null
        val jd = parts[2].toIntOrNull() ?: return null

        val expDate = jalaliToGregorian(jy, jm, jd)
        return ChronoUnit.DAYS.between(today, expDate).toInt()
    }
}
