package com.example.minifinancieraapp.ui.models

import com.google.firebase.Timestamp

data class Prestamo(
    val monto: Double = 0.0,
    val interes: Double = 0.0,
    val saldo: Double = 0.0,
    val totalPagar: Double = 0.0,
    val montoPagado: Double = 0.0,
    val estado: String = "",
    // Campos opcionales:
    val interesMensual: Double? = null,
    val interesTotal: Double? = null,
    val cuota: Double? = null,
    val lugar: String? = null,
    val numeroPrestamo: Int? = null,
    val garantia: String? = null,
    val eliminado: Boolean? = null,
    val saldoAnterior: Double? = null,
    val cobradorAsignado: String? = null,
    val cuotas: Int? = null,
    val prestamoId: String? = null,
    val mora: Double? = null,
    val interesManual: Double? = null,
    val pagos: Double? = null,
    val firma: String? = null,
    val observaciones: String? = null,
    val fechaCreacion: com.google.firebase.Timestamp? = null,
    val fechaUltimaActualizacion: com.google.firebase.Timestamp? = null,
    val fotos: List<String>? = emptyList(),
    val proximoPago: Any? = null // Aquí clave por Timestamp/String mixto
)

