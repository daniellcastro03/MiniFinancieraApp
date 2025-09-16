package com.example.capitalexpressapp.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object FiltroPreferences {
    private val FILTRO_TIPO = stringPreferencesKey("filtro_tipo")
    private val FILTRO_ESTADO = stringPreferencesKey("filtro_estado")

    suspend fun guardarFiltros(context: Context, tipo: String, estado: String) {
        context.dataStore.edit { prefs ->
            prefs[FILTRO_TIPO] = tipo
            prefs[FILTRO_ESTADO] = estado
        }
    }

    suspend fun obtenerFiltros(context: Context): Pair<String, String> {
        val prefs = context.dataStore.data.map { prefs ->
            val tipo = prefs[FILTRO_TIPO] ?: "Todos"
            val estado = prefs[FILTRO_ESTADO] ?: "Activos"
            tipo to estado
        }.first()

        return prefs
    }
}
