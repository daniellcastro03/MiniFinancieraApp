package com.example.minifinancieraapp.ui.models

data class SolicitudModel(
    val id: String = "",
    val cliente: String = "",
    val clienteId: String = "",
    val monto: Double = 0.0,
    val interes: Double = 0.0, // Este lo usaremos como interesManual
    val interesMensual: Double = 0.0,
    val interesTotal: Double = 0.0,
    val totalPagar: Double = 0.0,
    val cuota: Double = 0.0,
    val cuotas: Int = 0,
    val plazo: String = "",
    val fecha: String = "",
    val lugar: String = "",
    val firma: String? = null,
    val estado: String = "",
    val observaciones: String = "",
    val fotos: List<String> = emptyList(),

    // 🔽 Estos son los que debes AGREGAR:
    val mora: Double = 0.0,
    val garantia: String = "",
    val interesManual: Double = 0.0,
    val cobrador: String = "", // nombre del cobrador (opcional si lo necesitas)
    val cobradorUid: String? = null,
    val cobradorSolicitante: String = "",
    val diasEfectivos: Int = 0
)
