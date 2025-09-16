package com.example.capitalexpressapp.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.widget.Toast
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.DeviceConnection
import java.text.SimpleDateFormat
import java.util.*

fun imprimirReciboAbonoBluetooth(
    context: Context,
    cliente: String,
    monto: Double,
    cobrador: String,
    fecha: String
) {
    try {
        val bluetoothConnection: DeviceConnection? = BluetoothPrintersConnections.selectFirstPaired()
        if (bluetoothConnection == null) {
            Toast.makeText(context, "No hay impresora Bluetooth emparejada", Toast.LENGTH_LONG).show()
            return
        }

        val printer = EscPosPrinter(bluetoothConnection, 203, 48f, 32)

        val recibo = """
            [C]<b>CAPITAL EXPRESS</b>
            [L]
            [C]RECIBO DE ABONO
            [L]
            [L]Fecha: $fecha
            [L]Cliente: $cliente
            [L]Monto abonado: L. %.2f
            [L]Registrado por: $cobrador
            [L]
            [C]------------------------
            [C]¡Gracias por su pago!
            [C]<barcode type='EAN13' height='10'>123456789012</barcode>
        """.trimIndent().format(monto)

        printer.printFormattedText(recibo)

        Toast.makeText(context, "Recibo impreso", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "Error al imprimir: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
