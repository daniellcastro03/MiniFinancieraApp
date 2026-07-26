package com.example.capitalexpressapp.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val simbolosLempira = DecimalFormatSymbols(Locale.US).apply {
    groupingSeparator = ','
    decimalSeparator = '.'
}
private val formatoLempira = DecimalFormat("#,##0.00", simbolosLempira)

/** Formato único de moneda para toda la app: "L.1,200.00". */
fun formatearLempiras(monto: Double?): String = "L." + formatoLempira.format(monto ?: 0.0)

fun Double?.aLempiras(): String = formatearLempiras(this)
