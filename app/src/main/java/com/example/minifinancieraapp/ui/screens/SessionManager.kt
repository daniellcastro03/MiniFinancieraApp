package com.example.minifinancieraapp.util

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)

    fun saveUserData(uid: String, rol: String, nombre: String, fotoUrl: String) {
        prefs.edit()
            .putString("uid", uid)
            .putString("rol", rol)
            .putString("nombre", nombre)
            .putString("fotoUrl", fotoUrl)
            .apply()
    }

    fun getSession(): Triple<String, String, String>? {
        val uid = getUid()
        val nombre = getNombre()
        val rol = getRol()

        return if (uid.isNotEmpty() && rol.isNotEmpty()) {
            Triple(uid, nombre, rol)
        } else {
            null
        }
    }

    fun saveSession(uid: String, nombre: String, rol: String) {
        prefs.edit()
            .putString("uid", uid)
            .putString("nombre", nombre)
            .putString("rol", rol)
            .apply()
    }

    fun getUid(): String = prefs.getString("uid", "") ?: ""

    fun getRol(): String = prefs.getString("rol", "") ?: ""

    fun getNombre(): String = prefs.getString("nombre", "") ?: ""

    fun getFotoUrl(): String = prefs.getString("fotoUrl", "") ?: ""

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
