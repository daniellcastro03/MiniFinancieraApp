package com.example.capitalexpressapp.util

fun numeroALetras(numero: Double): String {
    val unidades = arrayOf("", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve")
    val especiales = arrayOf(
        "diez", "once", "doce", "trece", "catorce", "quince",
        "dieciséis", "diecisiete", "dieciocho", "diecinueve"
    )
    val decenas = arrayOf("", "", "veinte", "treinta", "cuarenta", "cincuenta",
        "sesenta", "setenta", "ochenta", "noventa")

    val parteEntera = numero.toInt()
    val parteDecimal = ((numero - parteEntera) * 100).toInt()

    val letras = when {
        parteEntera < 10 -> unidades[parteEntera]
        parteEntera in 10..19 -> especiales[parteEntera - 10]
        parteEntera in 20..99 -> {
            val d = parteEntera / 10
            val u = parteEntera % 10
            if (u == 0) decenas[d] else "${decenas[d]} y ${unidades[u]}"
        }
        else -> "cien o más"
    }

    val centavos = if (parteDecimal > 0) " con $parteDecimal/100" else ""
    return letras.replaceFirstChar { it.uppercase() } + " lempiras$centavos"
}
