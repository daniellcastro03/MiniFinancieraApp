package com.example.minifinancieraapp.ui.models

data class PagoItem(
    val docId: String = "",
    val cliente: String = "",
    val prestamoId: String = "",
    val fecha: String = "",
    val monto: Double = 0.0,
    val mora: Double = 0.0,
    val interesTotal: Double = 0.0,
    val cuota: String = "",
    val cobrador: String = "",
    val lugar: String = "",
    val firma: String = "",
    val tipoPago: String = "",
    val saldoRestante: Double = 0.0,
    val numeroPrestamo: Int = 0 // ✅ INT por defecto
)
