package com.example.capitalexpressapp.core

import java.text.Normalizer

/** Minúsculas, sin acentos, espacios colapsados. Base para comparar texto tolerando variaciones. */
fun normalizarTexto(texto: String): String {
    val sinAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    return sinAcentos.lowercase().trim().replace(Regex("\\s+"), " ")
}

/**
 * Distancia de edición con transposición (Damerau-Levenshtein, variante OSA):
 * cuenta sustituir/agregar/quitar UNA letra, o intercambiar dos letras
 * adyacentes, como un solo error. Deliberadamente más estricta que Levenshtein
 * puro -que permitía 2 sustituciones independientes y hacía que nombres
 * totalmente distintos (ej. "Majin" y "Jazmin") coincidieran por error-.
 */
private fun distanciaEdicion(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val costo = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(
                d[i - 1][j] + 1,
                d[i][j - 1] + 1,
                d[i - 1][j - 1] + costo
            )
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
    }
    return d[a.length][b.length]
}

private fun tokenCoincide(tokenConsulta: String, tokenObjetivo: String): Boolean {
    if (tokenObjetivo.startsWith(tokenConsulta) || tokenConsulta.startsWith(tokenObjetivo)) return true
    // Tolerancia fija de 1 solo error (un typo, una letra de más/menos, o dos
    // letras cambiadas de lugar) -no más, para no hacer coincidir nombres
    // distintos que casualmente se parecen-.
    return distanciaEdicion(tokenConsulta, tokenObjetivo) <= 1
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
