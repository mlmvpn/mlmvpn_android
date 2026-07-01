package com.mlmvpn.scanner.utils

import kotlin.random.Random

object NamingHelper {
    private val mythNames = listOf(
        "رستم", "سهراب", "کاوه", "آرش", "سیاوش", "فریدون", "اسفندیار", "جمشید",
        "کیخسرو", "گرشاسپ", "بهرام", "زال", "تهمینه", "گردآفرید", "رودابه", "گردیه",
        "آناهیتا", "پاسارگاد", "یوتاب", "زربانو", "آرتمیس", "آتوسا", "آرتادخت",
        "آذرآناهید", "کاساندان"
    )

    fun generateMythName(): String {
        return mythNames.random()
    }
}
