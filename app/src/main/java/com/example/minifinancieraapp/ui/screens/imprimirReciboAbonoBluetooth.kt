package com.example.capitalexpressapp.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import com.example.capitalexpressapp.util.numeroALetras

fun imprimirReciboAbonoBluetooth(
    context: Context,
    cliente: String,
    monto: Double,
    cobrador: String,
    fecha: String,
    cuota: String = "",
    saldoRestante: Double = 0.0,
    lugar: String = "",
    firma: String = ""
) {
    // Verificar permiso BLUETOOTH_CONNECT en Android 12+
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED
    ) {
        Toast.makeText(context, "Permiso de Bluetooth no concedido", Toast.LENGTH_SHORT).show()
        return
    }

    val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    if (adapter == null) {
        Toast.makeText(context, "Bluetooth no disponible", Toast.LENGTH_SHORT).show()
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

        writeLine("       RECIBO DE ABONO")
        writeLine("----------------------------------------")
        writeLine("Fecha: $fechaActual")
        writeLine("Cobrador: $cobrador")
        writeLine("Cliente: $cliente")
        writeLine("Abono: L %.2f".format(monto))
        writeLine("En letras: $letras")
        if (cuota.isNotBlank()) writeLine("Cuota: $cuota")
        if (saldoRestante > 0.0) writeLine("Saldo restante: L %.2f".format(saldoRestante))
        if (lugar.isNotBlank()) writeLine("Lugar: $lugar")
        writeLine("----------------------------------------")
        writeLine("Firma cobrador: $firma")
        writeLine("Firma cliente: __________________")
        writeLine("\n\n")

        output.flush()
        socket.close()

        Toast.makeText(context, "Recibo enviado por Bluetooth", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Error de impresión: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
