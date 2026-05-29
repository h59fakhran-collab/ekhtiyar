package com.example.ekhtiyar

// دیتای خام هر نماد که از API بازار میاد
// منبع: market_map در فایل پایتون (سطرهای 112-121)
data class MarketInstrument(
    val code: String,
       val symbol: String,
    val name: String,
    val lastPrice: Double,
    val bestBid: Double,
    val bestAsk: Double,
    val bestVol: Double
)

// یک ردیف نتیجه‌ی اسکن جامپ (PMCC)
// منبع: _process_general_filter (سطرهای 463-523)
data class JumpRow(
    val base: String,          // پایه
    val optionSymbol: String,  // نماد اختیار
    val expiry: String,        // سررسید
    val daysLeft: Int,         // روز مانده
    val strike: Int,           // K
    val underlyingPrice: Double, // S
    val bid: Double,           // P
    val bidVolume: Int,        // حجم P
    val kPlusP: Double,        // K+P
    val absoluteProfitPct: Double, // سود مطلق %
    val monthlyProfitPct: Double,  // سود ماهانه %
    val status: String,        // وضعیت
    val ok: Boolean,
    val isNew: Boolean
)

// یک قرارداد آپشن قبل از جفت‌سازی در اسکن دوگانه
// منبع: _process_tick_profit
data class DualOption(
    val base: String,
    val symbol: String,
    val expiry: String,
    val strike: Double,
    val bid: Double,
    val ask: Double
)

// نتیجه‌ی جفت‌سازی اسکن دوگانه (تیک سبز / زرد)
data class DualRow(
    val base: String,
    val expiry: String,
    val lowSymbol: String,
    val highSymbol: String,
    val lowStrike: Double,
    val highStrike: Double,
    val lowBid: Double,
    val lowAsk: Double,
    val highBid: Double,
    val highAsk: Double,
    val f1: Double,
    val f2: Double,
    val tag: String   // "tick_green" یا "tick_yellow"
)
