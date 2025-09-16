package com.example.capitalexpressapp.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

fun imprimirReciboPrestamoBluetooth(
    context: Context,
    cliente: String,
    monto: Double,
    fecha: String,
    plazo: String,
    interes: Double,
    mora: Double,
    estado: String,
    cobrador: String,
    lugar: String,
    firmaPrestamista: String = ""
) {
    val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    if (adapter == null || !adapter.isEnabled) {
        Toast.makeText(context, "Bluetooth no disponible", Toast.LENGTH_SHORT).show()
        return
    }

    // ✅ Verificar permisos en Android 12+
    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        Toast.makeText(context, "Permiso BLUETOOTH_CONNECT no otorgado", Toast.LENGTH_SHORT).show()
        return
    }

    val dispositivo = adapter.bondedDevices.firstOrNull()
    if (dispositivo == null) {
        Toast.makeText(context, "No hay dispositivo Bluetooth vinculado", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val socket: BluetoothSocket = dispositivo.createRfcommSocketToServiceRecord(uuid)
        socket.connect()

        val output: OutputStream = socket.outputStream
        val fechaActual = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val letras = numeroALetras(monto)

        fun writeLine(text: String) {
            output.write((text + "\n").toByteArray())
        }

        writeLine("         RECIBO DE PRÉSTAMO")
        writeLine("----------------------------------------")
        writeLine("Fecha: $fechaActual")
        writeLine("Prestamista: $cobrador")
        writeLine("Prestatario: $cliente")
        writeLine("Monto: L %.2f".format(monto))
        writeLine("En letras: $letras")
        writeLine("Interés: %.1f %%".format(interes))
        writeLine("Mora: L %.2f".format(mora))
        writeLine("Plazo: $plazo")
        writeLine("Fecha de pago: $fecha")
        writeLine("Lugar: $lugar")
        writeLine("----------------------------------------")
        writeLine("Firma prestamista: $firmaPrestamista")
        writeLine("Firma prestatario: __________________")
        writeLine("\n\n")

        output.flush()
        socket.close()

        Toast.makeText(context, "Recibo enviado por Bluetooth", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Error de impresión: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
