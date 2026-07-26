package com.example.capitalexpressapp.core

import java.text.Normalizer

/** Minúsculas, sin acentos, espacios colapsados. Base para comparar texto tolerando variaciones. */
fun normalizarTexto(texto: String): String {
    val sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    return sinAcentos.lowercase().trim().replace(Regex("\\s+"), " ")
}

private fun distanciaLevenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val filaAnterior = IntArray(b.length + 1) { it }
    val filaActual = IntArray(b.length + 1)
    for (i in 1..a.length) {
        filaActual[0] = i
        for (j in 1..b.length) {
            val costo = if (a[i - 1] == b[j - 1]) 0 else 1
            filaActual[j] = minOf(
                filaActual[j - 1] + 1,
                filaAnterior[j] + 1,
                filaAnterior[j - 1] + costo
            )
        }
        for (j in 0..b.length) filaAnterior[j] = filaActual[j]
    }
    return filaAnterior[b.length]
}

private fun tokenCoincide(tokenConsulta: String, tokenObjetivo: String): Boolean {
    if (tokenObjetivo.startsWith(tokenConsulta) || tokenConsulta.startsWith(tokenObjetivo)) return true
    val tolerancia = if (tokenConsulta.length <= 4) 1 else 2
    return distanciaLevenshtein(tokenConsulta, tokenObjetivo) <= tolerancia
}

/**
 * Búsqueda "inteligente" tolerante a errores de tipeo, orden de palabras distinto
 * (nombre/apellido invertido) y palabras salteadas (ej. segundo nombre omitido).
 * Cada palabra de [consulta] debe encontrar al menos una palabra parecida en
 * [textoObjetivo], sin importar el orden ni la posición.
 */
fun coincideAproximado(consulta: String, textoObjetivo: String): Boolean {
    val consultaNorm = normalizarTexto(consulta)
    if (consultaNorm.isBlank()) return true
    val objetivoNorm = normalizarTexto(textoObjetivo)
    if (objetivoNorm.contains(consultaNorm)) return true

    val tokensConsulta = consultaNorm.split(" ").filter { it.isNotBlank() }
    val tokensObjetivo = objetivoNorm.split(" ").filter { it.isNotBlank() }
    if (tokensConsulta.isEmpty() || tokensObjetivo.isEmpty()) return false

    return tokensConsulta.all { tc -> tokensObjetivo.any { to -> tokenCoincide(tc, to) } }
}
