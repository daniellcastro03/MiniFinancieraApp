package com.example.minifinancieraapp.ui.screens

import android.content.Context
import android.widget.Toast
import com.example.capitalexpressapp.util.ReciboHelper
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

suspend fun obtenerNuevoNumeroPrestamo(db: FirebaseFirestore): Int {
    val prestamos = db.collection("prestamos").get().await()
    val numeros = prestamos.documents.mapNotNull { it.getLong("numeroPrestamo")?.toInt() }
    return if (numeros.isEmpty()) 1 else (numeros.maxOrNull() ?: 0) + 1
}


suspend fun obtenerNombresCobradores(uids: List<String>): String {
    val db = FirebaseFirestore.getInstance()
    return try {
        val nombres = mutableListOf<String>()
        for (uid in uids.filter { it.isNotBlank() }) {
            val doc = db.collection("usuarios").document(uid).get().await()
            val nombre = doc.getString("nombre") ?: "UID: $uid"
            nombres.add(nombre)
        }
        if (nombres.isEmpty()) "No asignado" else nombres.joinToString(", ")
    } catch (e: Exception) {
        "No disponibles"
    }
}

fun calcularProximoPagoDesde(fechaBase: Date, plazo: String): String {
    val calendar = Calendar.getInstance().apply { time = fechaBase }
    val diaOriginal = calendar.get(Calendar.DAY_OF_MONTH)

    when {
        plazo.contains("Mensual", ignoreCase = true) -> {
            calendar.add(Calendar.MONTH, 1)
            val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            calendar.set(Calendar.DAY_OF_MONTH, diaOriginal.coerceAtMost(maxDay))
        }
        plazo.contains("Bimestral", ignoreCase = true) -> {
            calendar.add(Calendar.MONTH, 2)
            val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            calendar.set(Calendar.DAY_OF_MONTH, diaOriginal.coerceAtMost(maxDay))
        }
        plazo.contains("Quincenal", ignoreCase = true) -> calendar.add(Calendar.DATE, 15)
        plazo.contains("Semanal", ignoreCase = true) -> calendar.add(Calendar.DATE, 7)
        plazo.contains("Diario", ignoreCase = true) -> calendar.add(Calendar.DATE, 1)
    }

    return SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(calendar.time)
}

fun contarDiasSinDomingos(diasNecesarios: Int, fechaInicio: Calendar = Calendar.getInstance()): Int {
    var diasEfectivos = 0
    var diasTotales = 0
    val fecha = fechaInicio.clone() as Calendar

    while (diasEfectivos < diasNecesarios) {
        val diaSemana = fecha.get(Calendar.DAY_OF_WEEK)
        if (diaSemana != Calendar.SUNDAY) {
            diasEfectivos++
        }
        diasTotales++
        fecha.add(Calendar.DAY_OF_YEAR, 1)
    }
    return diasTotales
}


