package com.example.capitalexpressapp.core

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

private const val PREFS_NOMBRE = "capital_express_migraciones"
private const val CLAVE_MIGRACION_MAYUSCULAS = "clientes_mayusculas_v1"

/**
 * Corrige, una sola vez por instalación, los nombres de clientes que quedaron
 * guardados en minúsculas antes de que el guardado en mayúsculas fuera obligatorio.
 * Se controla con un flag en SharedPreferences para no releer toda la colección
 * "clientes" cada vez que se abre la app.
 */
suspend fun migrarNombresClientesAMayusculas(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NOMBRE, Context.MODE_PRIVATE)
    if (prefs.getBoolean(CLAVE_MIGRACION_MAYUSCULAS, false)) return

    try {
        val db = FirebaseFirestore.getInstance()
        val snapshot = db.collection("clientes").get().await()

        var batch = db.batch()
        var opsEnBatch = 0

        for (doc in snapshot.documents) {
            val cambios = mutableMapOf<String, Any>()

            fun revisarCampoPlano(campo: String) {
                val valor = doc.getString(campo)
                if (!valor.isNullOrBlank() && valor != valor.uppercase()) {
                    cambios[campo] = valor.uppercase().trim()
                }
            }
            fun revisarCampoAnidado(mapa: String, campo: String) {
                val valor = (doc.get(mapa) as? Map<*, *>)?.get(campo) as? String
                if (!valor.isNullOrBlank() && valor != valor.uppercase()) {
                    cambios["$mapa.$campo"] = valor.uppercase().trim()
                }
            }

            revisarCampoPlano("nombre")
            revisarCampoPlano("nombreEmpresa")
            revisarCampoAnidado("conyuge", "nombre")
            revisarCampoAnidado("ref1", "nombre")
            revisarCampoAnidado("ref2", "nombre")

            if (cambios.isNotEmpty()) {
                batch.update(doc.reference, cambios)
                opsEnBatch++
                if (opsEnBatch >= 450) {
                    batch.commit().await()
                    batch = db.batch()
                    opsEnBatch = 0
                }
            }
        }

        if (opsEnBatch > 0) batch.commit().await()

        prefs.edit().putBoolean(CLAVE_MIGRACION_MAYUSCULAS, true).apply()
    } catch (e: Exception) {
        // Sin internet u otro error: no marcamos el flag, se reintenta la próxima vez.
    }
}
