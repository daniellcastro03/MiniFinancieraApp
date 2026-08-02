package com.example.minifinancieraapp.ui.models

import com.google.firebase.Timestamp

data class PagoItem(
    val docId: String = "",
    val cliente: String = "",
    val prestamoId: String = "",
    val fecha: String = "",
    val monto: Double = 0.0,
    val mora: Double = 0.0,
    val interesTotal: Double = 0.0,
    val cobrador: String = "",
    val cobradorId: String = "", // 👈 AGREGAR ESTA LÍNEA
    val lugar: String = "",
    val firma: String = "",
    val tipoPago: String = "Efectivo",
    val metodoPago: String = "Efectivo",
    val saldoRestante: Double? = null,
    val numeroPrestamo: String = "",
    val numeroCuota: Int? = null,
    val cuota: String = "",
    val notas: String? = null,
    val fechaPago: String? = null,
    val timestamp: Timestamp? = null,
    val cuotasTotales: Int = 0
)