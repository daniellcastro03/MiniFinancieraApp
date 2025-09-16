package com.example.capitalexpressapp.util

import android.content.Context
import com.example.capitalexpressapp.ui.screens.NotificacionCobro
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object OfflineCache {

    private const val PREFS_NAME = "notificaciones"
    private const val KEY_CACHE = "cache_notifs"

    fun guardarNotificacionesOffline(context: Context, notifs: List<NotificacionCobro>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val json = Gson().toJson(notifs)
        editor.putString(KEY_CACHE, json)
        editor.apply()
    }

    fun cargarNotificacionesOffline(context: Context): List<NotificacionCobro> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHE, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<NotificacionCobro>>() {}.type
                Gson().fromJson(json, type)
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
    }
}
