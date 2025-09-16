package com.example.capitalexpressapp.util

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

// Esto debe ir fuera de cualquier clase u objeto
val Context.dataStore by preferencesDataStore(name = "notificaciones_prefs")
