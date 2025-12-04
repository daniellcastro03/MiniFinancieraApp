package com.example.capitalexpressapp.util

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Helper para generar números únicos de préstamo
 * Formato: [Iniciales del Cliente]-[Número Secuencial]
 * Ejemplo: SDG-00001
 */
object PrestamoNumberHelper {

    /**
     * Genera un número único de préstamo basado en las iniciales del cliente
     * @param clienteNombre Nombre completo del cliente
     * @param db Instancia de FirebaseFirestore
     * @return String con formato "ABC-00001"
     */
    suspend fun generarNumeroPrestamo(
        clienteNombre: String,
        db: FirebaseFirestore
    ): String {
        // Obtener iniciales del cliente
        val iniciales = obtenerInicialesCliente(clienteNombre)

        // Obtener el siguiente número secuencial
        val numeroSecuencial = obtenerSiguienteNumeroSecuencial(db)

        // Formatear con 5 dígitos
        val numeroFormateado = numeroSecuencial.toString().padStart(5, '0')

        return "$iniciales-$numeroFormateado"
    }

    /**
     * Extrae las iniciales del nombre completo del cliente
     * Toma la primera letra de cada palabra
     * Ejemplo: "Santos Daniel Garcia Reyes" -> "SDGR"
     */
    private fun obtenerInicialesCliente(nombreCompleto: String): String {
        val palabras = nombreCompleto.trim().split(" ").filter { it.isNotBlank() }

        return when {
            palabras.isEmpty() -> "CLI"
            palabras.size == 1 -> palabras[0].take(3).uppercase()
            palabras.size == 2 -> {
                // Nombre y Apellido -> 2 letras del nombre + 1 del apellido
                "${palabras[0].first()}${palabras[1].take(2)}".uppercase()
            }
            palabras.size == 3 -> {
                // Tres palabras -> primera letra de cada una
                "${palabras[0].first()}${palabras[1].first()}${palabras[2].first()}".uppercase()
            }
            else -> {
                // 4 o más palabras -> primeras 3 iniciales
                "${palabras[0].first()}${palabras[1].first()}${palabras[2].first()}".uppercase()
            }
        }
    }

    /**
     * Obtiene el siguiente número secuencial global del sistema
     * Usa un documento contador en Firestore para mantener la secuencia
     */
    private suspend fun obtenerSiguienteNumeroSecuencial(db: FirebaseFirestore): Int {
        val contadorRef = db.collection("configuracion").document("contadorPrestamos")

        return try {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(contadorRef)

                val nuevoContador = if (snapshot.exists()) {
                    val actual = snapshot.getLong("ultimoNumero")?.toInt() ?: 0
                    actual + 1
                } else {
                    1
                }

                transaction.set(contadorRef, mapOf("ultimoNumero" to nuevoContador))
                nuevoContador
            }.await()
        } catch (e: Exception) {
            // Si falla, usar timestamp como fallback
            (System.currentTimeMillis() % 100000).toInt()
        }
    }
}