package com.example.capitalexpressapp.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object SolicitudesCacheHelper {
    private const val FILE_NAME = "solicitudes_cache.json"

    fun guardarSolicitudes(context: Context, solicitudes: List<Map<String, Any?>>) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val json = Gson().toJson(solicitudes)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun obtenerSolicitudes(context: Context): List<Map<String, Any?>> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()

            val json = file.readText()
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}