package com.example.capitalexpressapp.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import java.util.ArrayList

object NetworkUtils {
    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun guardarAbonoPendiente(context: Context, abono: Map<String, Any>) {
        val sharedPrefs = context.getSharedPreferences("offline_data", Context.MODE_PRIVATE)
        val gson = Gson()
        val listaActual = try {
            val json = sharedPrefs.getString("abonos_pendientes", "[]")
            gson.fromJson(json, ArrayList::class.java) as ArrayList<Map<String, Any>>
        } catch (e: Exception) {
            arrayListOf()
        }
        listaActual.add(abono)
        sharedPrefs.edit().putString("abonos_pendientes", gson.toJson(listaActual)).apply()
    }

    fun checkInternetAbonos(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }


}
