package com.example.capitalexpressapp.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Versión publicada como release de GitHub. Debe coincidir con `versionCode` en
 * app/build.gradle.kts y con el tag de la release (`vN`). Subir en 1 en cada release nueva.
 */
const val VERSION_APP = 2

private const val REPO = "daniellcastro03/MiniFinancieraApp"
private const val AUTORIDAD_FILE_PROVIDER = "com.example.capitalexpressapp.fileprovider"

data class ActualizacionDisponible(
    val version: Int,
    val urlDescarga: String,
    val notas: String
)

/**
 * Chequea si hay una versión más nueva publicada como GitHub Release (el APK se sube a mano
 * con `gh release create`, tag `vN`) y, si el usuario acepta, la descarga e instala.
 * GitHub Releases es público, no hace falta token para leerlo.
 */
object ActualizacionService {

    /**
     * Devuelve null si no hay internet/GitHub no responde, si la release no trae un .apk,
     * o si la versión publicada no es más nueva que la instalada -en todos esos casos no
     * hay que interrumpir el uso normal de la app-.
     */
    suspend fun buscarActualizacion(): ActualizacionDisponible? = withContext(Dispatchers.IO) {
        try {
            val conexion = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            conexion.setRequestProperty("Accept", "application/vnd.github+json")
            conexion.connectTimeout = 8000
            conexion.readTimeout = 8000

            if (conexion.responseCode != 200) return@withContext null

            val json = JSONObject(conexion.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v").toIntOrNull()
                ?: return@withContext null
            if (version <= VERSION_APP) return@withContext null

            val assets = json.getJSONArray("assets")
            var urlApk: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").lowercase().endsWith(".apk")) {
                    urlApk = asset.getString("browser_download_url")
                    break
                }
            }
            urlApk ?: return@withContext null

            ActualizacionDisponible(
                version = version,
                urlDescarga = urlApk,
                notas = json.optString("body", "").trim()
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Descarga el APK a la caché de la app, informando progreso de 0f a 1f. */
    suspend fun descargarApk(
        context: Context,
        actualizacion: ActualizacionDisponible,
        onProgreso: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val destino = File(context.cacheDir, "actualizacion.apk")
        val conexion = URL(actualizacion.urlDescarga).openConnection() as HttpURLConnection
        conexion.connectTimeout = 8000
        conexion.instanceFollowRedirects = true
        conexion.connect()

        val total = conexion.contentLength
        var recibido = 0
        conexion.inputStream.use { entrada ->
            destino.outputStream().use { salida ->
                val buffer = ByteArray(8 * 1024)
                var leidos: Int
                while (entrada.read(buffer).also { leidos = it } != -1) {
                    salida.write(buffer, 0, leidos)
                    recibido += leidos
                    if (total > 0) onProgreso(recibido.toFloat() / total)
                }
            }
        }
        destino
    }

    /**
     * Abre el instalador del sistema con el APK descargado. Android no permite instalar sin
     * que el usuario confirme -no hay vuelta-: esto solo abre la pantalla de instalación,
     * el usuario decide si instala. La app sigue corriendo mientras tanto.
     */
    fun instalarApk(context: Context, archivo: File) {
        val uri = FileProvider.getUriForFile(context, AUTORIDAD_FILE_PROVIDER, archivo)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
