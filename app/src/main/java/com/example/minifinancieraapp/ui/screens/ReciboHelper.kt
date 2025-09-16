package com.example.capitalexpressapp.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.itextpdf.text.Document
import com.itextpdf.text.Font
import com.itextpdf.text.FontFactory
import com.itextpdf.text.Paragraph
import com.itextpdf.text.pdf.PdfWriter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import com.example.capitalexpressapp.R
import com.itextpdf.text.Element
import com.itextpdf.text.Image
import com.itextpdf.text.Rectangle
import java.io.ByteArrayOutputStream
import android.graphics.drawable.BitmapDrawable
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.example.minifinancieraapp.ui.models.PagoItem
import com.example.minifinancieraapp.ui.screens.CuotaAmortizacion
import com.google.firebase.firestore.Query
import com.itextpdf.text.Phrase
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat


object ReciboHelper {

    fun generarResumenDashboardPDF(
        context: Context,
        clientes: Int,
        cobros: Int,
        prestado: Double,
        pagado: Double,
        pendiente: Double,
        interes: Double,
        moras: Double,
        cantidadMoras: Int,
        filtro: String
    ): File? {
        return try {
            val pdf = PdfDocument()

            // Colores profesionales
            val colorPrimario = Color.parseColor("#1E3A8A") // Azul corporativo
            val colorSecundario = Color.parseColor("#3B82F6") // Azul claro
            val colorTexto = Color.parseColor("#1F2937") // Gris oscuro
            val colorExito = Color.parseColor("#10B981") // Verde
            val colorPendiente = Color.parseColor("#EF4444") // Rojo
            val colorAdvertencia = Color.parseColor("#F59E0B") // Amarillo
            val colorFondo = Color.parseColor("#F8FAFC") // Gris muy claro
            val colorLinea = Color.parseColor("#E5E7EB") // Gris claro

            // Estilos de texto
            val paintLogo = Paint().apply {
                color = colorPrimario
                textSize = 20f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintTitle = Paint().apply {
                color = colorPrimario
                textSize = 18f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintSubtitle = Paint().apply {
                color = colorTexto
                textSize = 14f
                isFakeBoldText = true
            }

            val paintLabel = Paint().apply {
                color = colorTexto
                textSize = 12f
                isFakeBoldText = true
            }

            val paintValue = Paint().apply {
                color = colorTexto
                textSize = 12f
                textAlign = Paint.Align.RIGHT
            }

            val paintText = Paint().apply {
                color = colorTexto
                textSize = 11f
            }

            val paintSmall = Paint().apply {
                color = Color.GRAY
                textSize = 9f
                textAlign = Paint.Align.CENTER
            }

            val ancho = 595 // A4 width
            val alto = 842 // A4 height
            val margen = 40f
            val espacioLinea = 20f

            var y = 60f
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(ancho, alto, 1).create())
            val canvas = page.canvas

            fun cargarLogo(): Bitmap? {
                return try {
                    val inputStream = context.assets.open("logo_capital.png")
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    null
                }
            }

            // === ENCABEZADO CON LOGO ===
            val logo = cargarLogo()
            if (logo != null) {
                val logoWidth = 80f
                val logoHeight = 60f
                val logoX = (ancho - logoWidth) / 2f
                val destRect = RectF(logoX, y, logoX + logoWidth, y + logoHeight)
                canvas.drawBitmap(logo, null, destRect, null)
                y += logoHeight + 20f
            } else {
                canvas.drawText("CAPITAL EXPRESS", ancho / 2f, y, paintLogo)
                y += 35f
            }

            // Línea decorativa
            val paintLineDecorative = Paint().apply {
                color = colorSecundario
                strokeWidth = 4f
            }
            canvas.drawLine(ancho / 2f - 80f, y, ancho / 2f + 80f, y, paintLineDecorative)
            y += 30f

            // Título principal
            canvas.drawText("RESUMEN DASHBOARD FINANCIERO", ancho / 2f, y, paintTitle)
            y += 40f

            // === INFORMACIÓN DEL REPORTE ===
            val infoRect = RectF(margen, y - 10f, ancho - margen, y + 50f)
            canvas.drawRoundRect(infoRect, 10f, 10f, Paint().apply { color = colorFondo })
            canvas.drawRoundRect(infoRect, 10f, 10f, Paint().apply {
                color = colorLinea
                style = Paint.Style.STROKE
                strokeWidth = 2f
            })

            y += 10f
            canvas.drawText("FILTRO APLICADO: $filtro", margen + 15f, y, paintText)
            y += espacioLinea
            val fechaGeneracion = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText("FECHA DE GENERACIÓN: $fechaGeneracion", margen + 15f, y, paintText)
            y += 40f

            // === MÉTRICAS PRINCIPALES ===
            canvas.drawText("MÉTRICAS PRINCIPALES", margen, y, paintSubtitle)
            y += 30f

            val dec = DecimalFormat("#,##0.00")
            val decInt = DecimalFormat("#,##0")

            // Función para dibujar una métrica con color
            fun dibujarMetrica(label: String, value: String, metricaColor: Int, yPos: Float): Float {
                // Icono de color
                val iconRect = RectF(margen, yPos - 8f, margen + 16f, yPos + 8f)
                canvas.drawRoundRect(iconRect, 4f, 4f, Paint().apply { color = metricaColor })

                // Label
                canvas.drawText(label, margen + 25f, yPos, paintLabel)

                // Value
                canvas.drawText(value, ancho - margen, yPos, paintValue)

                // Línea separadora sutil
                canvas.drawLine(margen + 25f, yPos + 10f, ancho - margen, yPos + 10f, Paint().apply {
                    color = colorLinea
                    strokeWidth = 1f
                })

                return yPos + espacioLinea + 5f
            }

            // Métricas generales
            y = dibujarMetrica("Total de Clientes:", decInt.format(clientes), colorSecundario, y)
            y = dibujarMetrica("Total de Cobros Realizados:", decInt.format(cobros), colorSecundario, y)

            y += 10f
            canvas.drawText("ANÁLISIS FINANCIERO", margen, y, paintSubtitle)
            y += 25f

            // Métricas financieras con colores temáticos
            y = dibujarMetrica("Monto Total Prestado:", "L. ${dec.format(prestado)}", Color.parseColor("#8B5CF6"), y)
            y = dibujarMetrica("Total Pagado:", "L. ${dec.format(pagado)}", colorExito, y)
            y = dibujarMetrica("Saldo Pendiente:", "L. ${dec.format(pendiente)}", colorPendiente, y)
            y = dibujarMetrica("Total de Intereses:", "L. ${dec.format(interes)}", colorAdvertencia, y)

            y += 10f
            canvas.drawText("CONTROL DE MORAS", margen, y, paintSubtitle)
            y += 25f

            y = dibujarMetrica("Total Moras Cobradas:", "L. ${dec.format(moras)}", Color.parseColor("#DC2626"), y)
            y = dibujarMetrica("Cantidad de Moras Aplicadas:", decInt.format(cantidadMoras), Color.parseColor("#B91C1C"), y)

            // === RESUMEN CONSOLIDADO ===
            y += 20f
            val resumenRect = RectF(margen, y - 10f, ancho - margen, y + 100f)
            canvas.drawRoundRect(resumenRect, 12f, 12f, Paint().apply {
                color = colorPrimario
                alpha = 20
            })
            canvas.drawRoundRect(resumenRect, 12f, 12f, Paint().apply {
                color = colorPrimario
                style = Paint.Style.STROKE
                strokeWidth = 3f
            })

            y += 15f
            canvas.drawText("RESUMEN CONSOLIDADO", margen + 15f, y, Paint().apply {
                color = colorPrimario
                textSize = 14f
                isFakeBoldText = true
            })
            y += 25f

            val totalGeneral = prestado + interes
            val porcentajePagado = if (totalGeneral > 0) (pagado / totalGeneral) * 100 else 0.0
            val porcentajePendiente = if (totalGeneral > 0) (pendiente / totalGeneral) * 100 else 0.0

            canvas.drawText("Total General (Capital + Interés):", margen + 15f, y, paintText)
            canvas.drawText("L. ${dec.format(totalGeneral)}", ancho - margen - 15f, y, Paint().apply {
                color = colorPrimario
                textSize = 12f
                isFakeBoldText = true
                textAlign = Paint.Align.RIGHT
            })
            y += espacioLinea

            canvas.drawText("Porcentaje Pagado:", margen + 15f, y, paintText)
            canvas.drawText("%.1f%%".format(porcentajePagado), ancho - margen - 15f, y, Paint().apply {
                color = colorExito
                textSize = 12f
                isFakeBoldText = true
                textAlign = Paint.Align.RIGHT
            })
            y += espacioLinea

            canvas.drawText("Porcentaje Pendiente:", margen + 15f, y, paintText)
            canvas.drawText("%.1f%%".format(porcentajePendiente), ancho - margen - 15f, y, Paint().apply {
                color = colorPendiente
                textSize = 12f
                isFakeBoldText = true
                textAlign = Paint.Align.RIGHT
            })

            // === PIE DE PÁGINA ===
            y = alto - 120f

            // Línea separadora
            canvas.drawLine(margen, y, ancho - margen, y, Paint().apply {
                color = colorSecundario
                strokeWidth = 2f
            })
            y += 20f

            canvas.drawText("Gracias por confiar en Capital Express", ancho / 2f, y, Paint().apply {
                color = colorPrimario
                textSize = 12f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            })
            y += 20f

            canvas.drawText("Danlí, El Paraíso - Tu socio financiero de confianza", ancho / 2f, y, paintSmall)
            y += 15f

            canvas.drawText("www.capitalexpress.hn | Tel: +504 0000-0000", ancho / 2f, y, paintSmall)

            // Información de generación
            canvas.drawText("Reporte generado automáticamente el $fechaGeneracion", ancho / 2f, alto - 20f, paintSmall)

            pdf.finishPage(page)

            val fileName = "ResumenDashboard_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.pdf"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            pdf.writeTo(FileOutputStream(file))
            pdf.close()

            file
        } catch (e: Exception) {
            Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            null
        }
    }


    fun generarReciboAbonoPDF(
        context: Context,
        cliente: String,
        prestamoId: String,
        saldoAnterior: Double,
        montoAbonado: Double,
        nuevoSaldo: Double,
        fecha: String,
        cuota: String,
        cobrador: String
    ): File? {
        return try {
            val document = Document(Rectangle(226.77f, 700f)) // ancho 80mm
            val fileName = "ReciboAbono_${cliente}_${System.currentTimeMillis()}.pdf"
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(outputDir, fileName)

            PdfWriter.getInstance(document, FileOutputStream(file))
            document.open()

            // Cargar logo
            val inputStream = context.resources.openRawResource(R.raw.logo_capital)
            val logo = Image.getInstance(ByteArrayOutputStream().apply {
                inputStream.copyTo(this)
            }.toByteArray())
            logo.scaleToFit(120f, 60f)
            logo.alignment = Image.ALIGN_CENTER
            document.add(logo)

            val titleFont = Font(Font.FontFamily.HELVETICA, 16f, Font.BOLD)
            val labelFont = Font(Font.FontFamily.HELVETICA, 10f, Font.BOLD)
            val textFont = Font(Font.FontFamily.HELVETICA, 10f)

            document.add(Paragraph("CAPITAL EXPRESS", titleFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph("RECIBO DE ABONO", titleFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph(" "))

            document.add(Paragraph("Fecha: $fecha", labelFont))
            document.add(Paragraph("Cliente: $cliente", textFont))
            document.add(Paragraph("Préstamo ID: $prestamoId", textFont))
            document.add(Paragraph("Cuota Nº: $cuota", textFont))
            document.add(Paragraph("Registrado por: $cobrador", textFont))
            document.add(Paragraph(" "))

            // Saldos
            document.add(Paragraph("Saldo anterior: L. %.2f".format(saldoAnterior), textFont))
            document.add(Paragraph("Saldo abonado: L. %.2f".format(montoAbonado), textFont))
            document.add(Paragraph("Nuevo saldo: L. %.2f".format(nuevoSaldo), labelFont))
            document.add(Paragraph(" "))

            // Firma
            document.add(Paragraph("Firma del cobrador:", textFont))
            document.add(Paragraph("__________________________", textFont))
            document.add(Paragraph(" "))

            // Footer
            document.add(Paragraph("Gracias por su pago.", textFont))
            document.add(Paragraph("Capital Express - Danlí, El Paraíso", textFont))
            document.add(Paragraph("Tu socio financiero de confianza", textFont))

            document.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    fun imprimirResumenPagosBluetooth(context: Context, pagos: List<PagoItem>) {
        try {
            val printerConnection = BluetoothPrintersConnections.selectFirstPaired()
            if (printerConnection == null) {
                Toast.makeText(context, "No hay impresora conectada", Toast.LENGTH_SHORT).show()
                return
            }

            val printer = EscPosPrinter(printerConnection, 203, 48f, 32)
            val builder = StringBuilder()

            builder.append("[C]===============================\n")
            builder.append("[C]<b>CAPITAL EXPRESS</b>\n")
            builder.append("[C]<b>RESUMEN DE PAGOS</b>\n")
            builder.append("[C]===============================\n\n")

            var total = 0.0

            pagos.forEach { pago ->
                builder.append("[L]<b>Cliente:</b> ${pago.cliente}\n")
                builder.append("[L]<b>Préstamo ID:</b> ${pago.prestamoId}\n")
                builder.append("[L]<b>Fecha:</b> ${pago.fecha}\n")
                builder.append("[L]<b>Monto:</b> L. %.2f\n".format(pago.monto))
                if (pago.interesTotal > 0.0)
                    builder.append("[L]<b>Interés:</b> L. %.2f\n".format(pago.interesTotal))
                if (pago.mora > 0.0)
                    builder.append("[L]<b>Mora:</b> L. %.2f\n".format(pago.mora))
                if (pago.cuota.isNotBlank())
                    builder.append("[L]<b>Cuota:</b> ${pago.cuota}\n")
                if (pago.cobrador.isNotBlank())
                    builder.append("[L]<b>Cobrador:</b> ${pago.cobrador}\n")

                builder.append("[L]------------------------------\n")
                total += pago.monto
            }

            builder.append("\n[C]===============================\n")
            builder.append("[C]<b>TOTAL PAGADO:</b> L. %.2f\n".format(total))
            builder.append("[C]===============================\n")

            builder.append("\n\n[L]Firma:\n")
            builder.append("[L]______________________________\n\n")

            builder.append("[C]Gracias por su pago\n")
            builder.append("[C]Capital Express\n")
            builder.append("[C]Danlí, El Paraíso\n")
            builder.append("[C]<i>Tu socio financiero de confianza</i>\n")
            builder.append("[C]===============================\n")

            printer.printFormattedText(builder.toString())

        } catch (e: Exception) {
            Toast.makeText(context, "Error al imprimir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun imprimirRecibo(
        context: Context,
        cliente: String,
        prestamoId: String,
        fecha: String,
        montoPagado: String,
        saldoAnterior: Double,
        proximoPago: String,
        cuota: String,
        cobrador: String,
        lugar: String,
        tipoPago: String,
        mora: Double = 0.0
    ) {
        try {
            val printerConnection = BluetoothPrintersConnections.selectFirstPaired()
            if (printerConnection == null) {
                Toast.makeText(context, "No hay impresora conectada", Toast.LENGTH_SHORT).show()
                return
            }

            val printer = EscPosPrinter(printerConnection, 203, 48f, 32)
            val builder = StringBuilder()

            // Logo más grande (80mm impresora térmica)
            builder.append("[C]<img>${context.getExternalFilesDir(null)?.absolutePath}/logo_capital.png</img>\n")

            builder.append("[C]<font size='big'><b>CAPITAL EXPRESS</b></font>\n")
            builder.append("[C]Inversiones Victoria\n")
            builder.append("[C]Danlí, El Paraíso\n")
            builder.append("[C]<i>Tu socio financiero de confianza</i>\n")
            builder.append("[C]================================\n")

            builder.append("[L]<b>FECHA:</b> $fecha\n")
            builder.append("[L]<b>LUGAR:</b> $lugar\n")
            builder.append("[L]--------------------------------\n")

            builder.append("[L]<b>CLIENTE:</b> $cliente\n")
            builder.append("[L]<b>ID PRÉSTAMO:</b> $prestamoId\n")
            builder.append("[L]<b>CUOTA Nº:</b> $cuota\n")
            builder.append("[L]--------------------------------\n")

            builder.append("[L]<b>TIPO DE PAGO:</b> $tipoPago\n")
            builder.append("[L]<b>COBRADOR:</b> $cobrador\n")
            builder.append("[L]<b>SALDO ANTERIOR:</b> L. %.2f\n".format(saldoAnterior))
            if (mora > 0.0) {
                builder.append("[L]<b>MORA APLICADA:</b> L. %.2f\n".format(mora))
            }

            val pagado = montoPagado.toDoubleOrNull() ?: 0.0
            val saldoRestante = saldoAnterior + mora - pagado

            builder.append("[L]<b>TOTAL PAGADO:</b> L. %.2f\n".format(pagado))
            builder.append("[L]<b>SALDO RESTANTE:</b> L. %.2f\n".format(saldoRestante))
            builder.append("[L]<b>PRÓXIMO PAGO:</b> $proximoPago\n")
            builder.append("[L]--------------------------------\n")

            builder.append("[L]\n")
            builder.append("[L]Firma del cobrador:\n")
            builder.append("[L]______________________________\n")

            builder.append("[C]================================\n")
            builder.append("[C]Gracias por su pago\n")
            builder.append("[C]<b>Capital Express</b>\n")
            builder.append("[C]<i>Tu aliado en finanzas</i>\n")

            printer.printFormattedTextAndCut(builder.toString())

        } catch (e: Exception) {
            Toast.makeText(context, "Error al imprimir: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    fun generarReciboPrestamoPDF(
        context: Context,
        cliente: ClienteModel,
        monto: Double,
        interesTotal: Double,
        mora: Double,
        cuotas: Int,
        fecha: String,
        lugar: String,
        numeroCobrador: String,
        numeroPrestamo: String,
        nombreCobrador: String = "Cobrador",
        fechaProximoPago: String = ""
    ): File? {
        val clienteNombre = cliente.nombre
        return try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(384, 850, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // Colores personalizados
            val colorPrimario = Color.parseColor("#1565C0") // Azul profesional
            val colorSecundario = Color.parseColor("#2E7D32") // Verde para montos
            val colorTexto = Color.parseColor("#212121") // Gris oscuro
            val colorFondo = Color.parseColor("#F5F5F5") // Gris claro para fondos

            // Estilos de texto mejorados
            val paintTitle = Paint().apply {
                color = colorPrimario
                textSize = 20f
                isFakeBoldText = true
                typeface = Typeface.DEFAULT_BOLD
            }

            val paintSubtitle = Paint().apply {
                color = colorPrimario
                textSize = 16f
                isFakeBoldText = true
            }

            val paintLabel = Paint().apply {
                color = colorTexto
                textSize = 14f
                typeface = Typeface.DEFAULT
            }

            val paintValue = Paint().apply {
                color = colorTexto
                textSize = 14f
                isFakeBoldText = true
            }

            val paintMoney = Paint().apply {
                color = colorSecundario
                textSize = 15f
                isFakeBoldText = true
            }

            val paintTotal = Paint().apply {
                color = colorSecundario
                textSize = 18f
                isFakeBoldText = true
                typeface = Typeface.DEFAULT_BOLD
            }

            val paintLine = Paint().apply {
                color = colorPrimario
                strokeWidth = 2f
            }

            val paintBackground = Paint().apply {
                color = colorFondo
            }

            var y = 25

            // Encabezado con fondo
            canvas.drawRect(10f, y.toFloat(), 374f, (y + 120).toFloat(), paintBackground)

            // Logo centrado
            val logoBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.logo_capital)
            val logoSize = 80
            canvas.drawBitmap(
                Bitmap.createScaledBitmap(logoBitmap, logoSize, logoSize, true),
                (384 - logoSize) / 2f,
                (y + 10).toFloat(),
                null
            )
            y += 95

            // Título principal centrado
            val tituloTexto = "CAPITAL EXPRESS"
            val tituloAncho = paintTitle.measureText(tituloTexto)
            canvas.drawText(tituloTexto, (384 - tituloAncho) / 2f, y.toFloat(), paintTitle)
            y += 35

            // Información de ubicación y fecha
            canvas.drawText("📍 $lugar", 20f, y.toFloat(), paintLabel)
            canvas.drawText("📅 $fecha", 200f, y.toFloat(), paintLabel)
            y += 25

            if (fechaProximoPago.isNotBlank()) {
                val proximoPagoTexto = "📆 Próximo pago: $fechaProximoPago"
                val proximoPagoAncho = paintLabel.measureText(proximoPagoTexto)
                canvas.drawText(proximoPagoTexto, (384 - proximoPagoAncho) / 2f, y.toFloat(), paintLabel)
                y += 25
            }

            // Línea separadora principal
            canvas.drawLine(10f, y.toFloat(), 374f, y.toFloat(), paintLine)
            y += 30

            // Título del recibo centrado
            val reciboTexto = "RECIBO DE PRÉSTAMO"
            val reciboAncho = paintSubtitle.measureText(reciboTexto)
            canvas.drawText(reciboTexto, (384 - reciboAncho) / 2f, y.toFloat(), paintSubtitle)
            y += 35

            // Sección de datos del cliente con fondo
            canvas.drawRect(15f, y.toFloat(), 369f, (y + 85).toFloat(), paintBackground)
            y += 15

            canvas.drawText("DATOS DEL CLIENTE", 25f, y.toFloat(), paintSubtitle)
            y += 25

            canvas.drawText("👤 Nombre:", 25f, y.toFloat(), paintLabel)
            canvas.drawText(cliente.nombre, 110f, y.toFloat(), paintValue)
            y += 20

            if (cliente.telefono.isNotBlank()) {
                canvas.drawText("📱 Teléfono:", 25f, y.toFloat(), paintLabel)
                canvas.drawText(cliente.telefono, 110f, y.toFloat(), paintValue)
                y += 20
            }

            if (cliente.direccionCasa.isNotBlank()) {
                canvas.drawText("🏠 Dirección:", 25f, y.toFloat(), paintLabel)
                // Dividir dirección larga si es necesario
                val direccion = cliente.direccionCasa
                if (direccion.length > 25) {
                    canvas.drawText(direccion.substring(0, 25), 120f, y.toFloat(), paintValue)
                    y += 15
                    canvas.drawText(direccion.substring(25), 120f, y.toFloat(), paintValue)
                } else {
                    canvas.drawText(direccion, 120f, y.toFloat(), paintValue)
                }
                y += 20
            }

            y += 15

            // Sección de datos del préstamo
            canvas.drawText("DETALLES DEL PRÉSTAMO", 25f, y.toFloat(), paintSubtitle)
            y += 30

            // Tabla de montos con mejor alineación
            canvas.drawText("💲 Monto prestado:", 25f, y.toFloat(), paintLabel)
            canvas.drawText("L. ${"%.2f".format(monto)}", 280f, y.toFloat(), paintMoney)
            y += 22

            canvas.drawText("📈 Interés total:", 25f, y.toFloat(), paintLabel)
            canvas.drawText("L. ${"%.2f".format(interesTotal)}", 280f, y.toFloat(), paintMoney)
            y += 22

            canvas.drawText("⏳ Número de cuotas:", 25f, y.toFloat(), paintLabel)
            canvas.drawText("$cuotas", 280f, y.toFloat(), paintValue)
            y += 22

            canvas.drawText("⚠️ Mora diaria:", 25f, y.toFloat(), paintLabel)
            canvas.drawText("L. ${"%.2f".format(mora)}", 280f, y.toFloat(), paintMoney)
            y += 30

            // Total destacado con fondo
            val total = monto + interesTotal
            canvas.drawRect(15f, y.toFloat(), 369f, (y + 35).toFloat(), paintBackground)
            y += 25
            canvas.drawText("💰 TOTAL A PAGAR:", 25f, y.toFloat(), paintTotal)
            canvas.drawText("L. ${"%.2f".format(total)}", 250f, y.toFloat(), paintTotal)
            y += 40

            // Línea separadora
            canvas.drawLine(10f, y.toFloat(), 374f, y.toFloat(), paintLine)
            y += 30

            // Información del cobrador
            canvas.drawText("INFORMACIÓN DE CONTACTO", 25f, y.toFloat(), paintSubtitle)
            y += 25

            canvas.drawText("👨‍💼 Atendido por:", 25f, y.toFloat(), paintLabel)
            canvas.drawText(nombreCobrador, 140f, y.toFloat(), paintValue)
            y += 20

            canvas.drawText("📱 Teléfono:", 25f, y.toFloat(), paintLabel)
            canvas.drawText(numeroCobrador, 100f, y.toFloat(), paintValue)
            y += 40

            // Área de firma mejorada
            canvas.drawText("FIRMA DE AUTORIZACIÓN", 25f, y.toFloat(), paintSubtitle)
            y += 30
            canvas.drawLine(80f, y.toFloat(), 304f, y.toFloat(), paintLabel)
            y += 20
            val firmaTexto = "Firma del cobrador"
            val firmaAncho = paintLabel.measureText(firmaTexto)
            canvas.drawText(firmaTexto, (384 - firmaAncho) / 2f, y.toFloat(), paintLabel)
            y += 40

            // Mensaje final centrado
            val mensajeFinal1 = "¡Gracias por confiar en nosotros!"
            val mensajeFinal2 = "Tu socio financiero en Danlí"

            val mensaje1Ancho = paintLabel.measureText(mensajeFinal1)
            val mensaje2Ancho = paintLabel.measureText(mensajeFinal2)

            canvas.drawText(mensajeFinal1, (384 - mensaje1Ancho) / 2f, y.toFloat(), paintLabel)
            y += 18
            canvas.drawText(mensajeFinal2, (384 - mensaje2Ancho) / 2f, y.toFloat(), paintLabel)

            pdfDocument.finishPage(page)

            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "recibo_prestamo_$numeroPrestamo.pdf"
            )
            pdfDocument.writeTo(FileOutputStream(file))
            pdfDocument.close()

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generarInformeClientePDF(
        context: Context,
        clienteNombre: String,
        direccion: String,
        prestamos: List<Triple<String, String, Double>>,
        pagosPorPrestamo: Map<String, List<PagoItem>>,
        incluirInfoCliente: Boolean = true
    ): File? {
        val pdfDocument = PdfDocument()
        val paint = Paint().apply {
            textSize = 14f
            color = Color.BLACK
        }
        val boldPaint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 16f
            color = Color.BLACK
        }

        val date = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())
        val fileName = "informe_cliente_${System.currentTimeMillis()}.pdf"
        val filePath = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        var y = 40

        if (incluirInfoCliente) {
            canvas.drawText("Informe de Cliente", 40f, y.toFloat(), boldPaint)
            y += 25
            canvas.drawText("Nombre: $clienteNombre", 40f, y.toFloat(), paint)
            y += 20
            canvas.drawText("Dirección: $direccion", 40f, y.toFloat(), paint)
            y += 20
            canvas.drawText("Fecha de generación: $date", 40f, y.toFloat(), paint)
            y += 30
        }

        prestamos.forEachIndexed { index, (fecha, producto, monto) ->
            if (y >= 750) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                y = 40
            }

            canvas.drawText("Préstamo ${index + 1}: $producto", 40f, y.toFloat(), boldPaint)
            y += 20
            canvas.drawText("Fecha de compra: $fecha", 40f, y.toFloat(), paint)
            y += 18
            canvas.drawText("Precio: L. %.2f".format(monto), 40f, y.toFloat(), paint)

            val pagos = pagosPorPrestamo[producto] ?: emptyList()
            val totalPagado = pagos.sumOf { it.monto }
            val restante = monto - totalPagado

            y += 18
            canvas.drawText("Pagado: L. %.2f".format(totalPagado), 40f, y.toFloat(), paint)
            y += 18
            canvas.drawText("Resta: L. %.2f".format(restante), 40f, y.toFloat(), paint)
            y += 25

            if (pagos.isNotEmpty()) {
                canvas.drawText("Pagos:", 40f, y.toFloat(), boldPaint)
                y += 20

                // Cabecera de tabla
                canvas.drawText("Fecha", 60f, y.toFloat(), boldPaint)
                canvas.drawText("Comentario", 140f, y.toFloat(), boldPaint)
                canvas.drawText("Pago", 360f, y.toFloat(), boldPaint)
                canvas.drawText("Resta", 440f, y.toFloat(), boldPaint)
                y += 20

                var acumulado = 0.0
                pagos.forEach { pago ->
                    acumulado += pago.monto
                    val resta = monto - acumulado

                    if (y >= 800) {
                        pdfDocument.finishPage(page)
                        pageNumber++
                        pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = 40
                    }

                    canvas.drawText(pago.fecha, 60f, y.toFloat(), paint)
                    canvas.drawText(pago.cuota, 140f, y.toFloat(), paint)
                    canvas.drawText("L. %.2f".format(pago.monto), 360f, y.toFloat(), paint)
                    canvas.drawText("L. %.2f".format(resta), 440f, y.toFloat(), paint)
                    y += 18
                }

                y += 30
            } else {
                canvas.drawText("Sin pagos registrados.", 40f, y.toFloat(), paint)
                y += 25
            }
        }

        return try {
            pdfDocument.finishPage(page)
            pdfDocument.writeTo(FileOutputStream(filePath))
            pdfDocument.close()
            filePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun imprimirPDF(context: Context, file: File): Boolean {
        return try {
            if (!file.exists()) {
                Log.e("ReciboPDF", "❌ Archivo PDF no existe: ${file.absolutePath}")
                Toast.makeText(context, "El archivo PDF no existe", Toast.LENGTH_SHORT).show()
                return false
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            // Primero intentar abrir con apps de impresión
            val printIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                // Preferir apps de impresión
                addCategory(Intent.CATEGORY_DEFAULT)
            }

            val packageManager = context.packageManager
            val printActivities = packageManager.queryIntentActivities(printIntent, 0)

            // Buscar apps de impresión específicamente
            val printApp = printActivities.find { resolveInfo ->
                val packageName = resolveInfo.activityInfo.packageName.lowercase()
                packageName.contains("print") ||
                        packageName.contains("hp") ||
                        packageName.contains("canon") ||
                        packageName.contains("epson") ||
                        packageName.contains("brother")
            }

            if (printApp != null) {
                // Usar app de impresión específica
                printIntent.setPackage(printApp.activityInfo.packageName)
                context.startActivity(printIntent)
                Log.d("ReciboPDF", "✅ PDF abierto con app de impresión: ${printApp.activityInfo.packageName}")
                return true
            }

            // Si no hay app de impresión, usar cualquier visor de PDF
            if (printActivities.isNotEmpty()) {
                context.startActivity(printIntent)
                Log.d("ReciboPDF", "✅ PDF abierto con visor disponible: ${file.name}")
                return true
            }

            // No hay apps disponibles
            Log.w("ReciboPDF", "⚠️ No hay aplicación para abrir PDFs instalada")
            Toast.makeText(
                context,
                "No hay aplicación para abrir PDFs. Por favor instale un visor de PDF o app de impresión.",
                Toast.LENGTH_LONG
            ).show()

            return false

        } catch (e: Exception) {
            Log.e("ReciboPDF", "❌ Error al abrir PDF", e)
            Toast.makeText(context, "Error al abrir PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            return false
        }
    }

    fun generarReciboTextoPDF(context: Context, titulo: String, contenido: String): File? {
        return try {
            val pdfFile = File(context.cacheDir, "ReciboTexto_${System.currentTimeMillis()}.pdf")
            val document = Document()
            val writer = PdfWriter.getInstance(document, FileOutputStream(pdfFile))
            document.open()
            document.add(Paragraph(titulo))
            document.add(Paragraph("\n$contenido"))
            document.close()
            writer.close()
            pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generarTextoRecibo(
        cliente: String,
        prestamoId: String,
        fecha: String,
        montoPagado: String,
        saldoAnterior: Double,
        proximoPago: String,
        cuota: String,
        cobrador: String
    ): String {
        return """
            ********** RECIBO DE PAGO **********
            Cliente: $cliente
            Préstamo ID: $prestamoId
            Fecha: $fecha
            Monto Pagado: L. $montoPagado
            Saldo Anterior: L. %.2f
            Próximo Pago: $proximoPago
            Cuota: $cuota
            Registrado por: $cobrador
            *************************************
            Gracias por su pago.
        """.trimIndent().format(saldoAnterior)
    }

    suspend fun generarResumenPagosPDF(
        context: Context,
        pagos: List<PagoItem>, // ✅ Recibe directamente los pagos filtrados
        fechaInicio: Date,
        fechaFin: Date,
        periodo: String
    ): File? {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val sdfHora = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        // ✅ Si no hay pagos, retornar null
        if (pagos.isEmpty()) return null

        // ✅ Convertir PagoItem a Row directamente (sin consultas a Firestore)
        data class Row(
            val cliente: String,
            val fecha: Date,
            val fechaStr: String,
            val monto: Double,
            val mora: Double,
            val cuota: String,
            val cobrador: String
        )

        fun parseFechaDePago(pago: PagoItem): Pair<Date, String> {
            return try {
                // Extraer solo la parte de fecha (sin hora si existe)
                val fechaStr = pago.fecha.split(" ")[0] // "dd/MM/yyyy"
                val fechaDate = sdf.parse(fechaStr) ?: Date()
                fechaDate to fechaStr
            } catch (e: Exception) {
                Date() to sdf.format(Date())
            }
        }

        // ✅ Convertir pagos filtrados a rows y ordenar por fecha
        val rows = pagos.map { pago ->
            val (fechaReal, fechaFmt) = parseFechaDePago(pago)
            Row(
                cliente = pago.cliente,
                fecha = fechaReal,
                fechaStr = fechaFmt,
                monto = pago.monto,
                mora = pago.mora,
                cuota = pago.cuota,
                cobrador = pago.cobrador
            )
        }.sortedBy { it.fecha }

        // ✅ Resto del código igual (construcción del PDF)
        val pageWidth = 595 // A4 (72dpi)
        val pageHeight = 842
        val margin = 32f
        val headerY = 120f
        val rowH = 18f

        val colorPrimario = Color.parseColor("#1E3A8A")
        val colorTexto = Color.parseColor("#1F2937")
        val colorLinea = Color.parseColor("#E5E7EB")

        val titlePaint = Paint().apply {
            isAntiAlias = true; textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
        }
        val subPaint = Paint().apply { isAntiAlias = true; textSize = 12f; color = Color.DKGRAY }
        val headerPaint = Paint().apply {
            isAntiAlias = true; textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
        }
        val cellPaint = Paint().apply { isAntiAlias = true; textSize = 10f; color = colorTexto }
        val linePaint = Paint().apply { color = colorLinea; strokeWidth = 1f }
        val headerBg = Paint().apply { color = colorPrimario }

        // columnas
        val colXs = floatArrayOf(
            margin,           // Cliente
            margin + 210f,    // Fecha
            margin + 280f,    // Monto
            margin + 350f,    // Mora
            margin + 410f,    // Cuota
            margin + 470f     // Cobrador
        )

        val pdf = PdfDocument()
        var pageNum = 1
        var y = 0f

        fun newPage(): PdfDocument.Page {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            val page = pdf.startPage(pageInfo)
            val c = page.canvas

            // Encabezado
            c.drawText("Resumen de Pagos", margin, 40f, titlePaint)
            c.drawText("Periodo: $periodo", margin, 58f, subPaint)
            c.drawText(
                "Rango: ${sdf.format(fechaInicio)} a ${sdf.format(fechaFin)}   |   Generado: ${sdfHora.format(Date())}",
                margin, 74f, subPaint
            )

            // Header de tabla
            c.drawRect(margin, headerY - 14f, pageWidth - margin, headerY + 6f, headerBg)
            val headers = arrayOf("Cliente", "Fecha", "Monto", "Mora", "Cuota", "Cobrador")
            for (i in headers.indices) {
                c.drawText(headers[i], colXs[i] + 2f, headerY, headerPaint)
            }
            c.drawLine(margin, headerY + 8f, pageWidth - margin, headerY + 8f, linePaint)

            y = headerY + 24f
            return page
        }

        fun footer(canvas: Canvas) {
            val p = Paint().apply { isAntiAlias = true; textSize = 10f; color = Color.GRAY }
            canvas.drawText("Página $pageNum", pageWidth / 2f - 20f, pageHeight - 24f, p)
        }

        var page = newPage()
        var canvas = page.canvas

        var totalMonto = 0.0
        var totalMora = 0.0

        rows.forEach { r ->
            if (y + rowH > pageHeight - 60f) {
                footer(canvas)
                pdf.finishPage(page)
                pageNum++
                page = newPage()
                canvas = page.canvas
            }

            val cols = arrayOf(
                r.cliente,
                r.fechaStr,
                "L. %,.2f".format(Locale.getDefault(), r.monto),
                if (r.mora > 0) "L. %,.2f".format(Locale.getDefault(), r.mora) else "-",
                r.cuota,
                r.cobrador
            )
            for (i in cols.indices) canvas.drawText(cols[i], colXs[i] + 2f, y, cellPaint)
            canvas.drawLine(margin, y + 4f, pageWidth - margin, y + 4f, linePaint)
            y += rowH

            totalMonto += r.monto
            totalMora += r.mora
        }

        // Totales
        if (y + 2 * rowH > pageHeight - 60f) {
            footer(canvas); pdf.finishPage(page); pageNum++; page = newPage(); canvas = page.canvas
        }
        val totalsY = y + 10f
        canvas.drawText("Registros: ${rows.size}", margin, totalsY, subPaint)
        canvas.drawText(
            "Total Mora: L. %,.2f   •   Total Pagado: L. %,.2f".format(Locale.getDefault(), totalMora, totalMonto),
            margin + 200f, totalsY, subPaint
        )

        footer(canvas)
        pdf.finishPage(page)

        // Guardar
        return try {
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "reporte_pagos_${System.currentTimeMillis()}.pdf"
            )
            FileOutputStream(file).use { pdf.writeTo(it) }
            pdf.close()
            file
        } catch (e: Exception) {
            pdf.close()
            Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

// ✅ MODIFICACIÓN EN EL BOTÓN DE EXPORTAR PDF (en HistorialPagosScreen)
// Reemplaza el botón "Exportar PDF" con este código:

    suspend fun generarResumenPrestamosPDF(
        context: Context,
        fechaInicio: Date,
        fechaFin: Date,
        periodo: String
    ): File? {
        val db = FirebaseFirestore.getInstance()
        val prestamos = db.collection("prestamos")
            .whereGreaterThanOrEqualTo("fecha", SimpleDateFormat("dd/MM/yyyy").format(fechaInicio))
            .whereLessThanOrEqualTo("fecha", SimpleDateFormat("dd/MM/yyyy").format(fechaFin))
            .orderBy("fecha", Query.Direction.ASCENDING)
            .get()
            .await()

        val lista = prestamos.documents.mapIndexed { index, doc ->
            val nombreCliente = doc.getString("cliente") ?: "N/A"
            val monto = doc.getDouble("monto") ?: 0.0
            val fecha = doc.getString("fecha") ?: "Sin fecha"
            Triple(index + 1, nombreCliente, "L. %.2f  |  $fecha".format(monto))
        }

        val titulo = "Resumen de Préstamos - $periodo"
        val subtitulo = "Desde ${SimpleDateFormat("dd/MM/yyyy").format(fechaInicio)} hasta ${
            SimpleDateFormat("dd/MM/yyyy").format(fechaFin)
        }"

        return generarPDFConLista(context, titulo, subtitulo, lista)
    }

    fun generarPDFConLista(
        context: Context,
        titulo: String,
        subtitulo: String,
        datos: List<Triple<Int, String, String>>
    ): File? {
        try {
            val pdfDocument = android.graphics.pdf.PdfDocument()
            val paint = android.graphics.Paint()
            val titlePaint = android.graphics.Paint().apply {
                textSize = 18f
                isFakeBoldText = true
                color = AndroidColor.BLACK
            }

            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            var y = 40
            canvas.drawText(titulo, 40f, y.toFloat(), titlePaint)
            y += 25
            canvas.drawText(subtitulo, 40f, y.toFloat(), paint)
            y += 30

            datos.forEach {
                if (y > 800) return@forEach // Corte simple por página
                canvas.drawText("${it.first}. ${it.second} - ${it.third}", 40f, y.toFloat(), paint)
                y += 22
            }

            pdfDocument.finishPage(page)

            val file = File(context.cacheDir, "Resumen_${titulo.replace(" ", "_")}.pdf")
            pdfDocument.writeTo(file.outputStream())
            pdfDocument.close()
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun abrirPDF(context: Context, archivo: File) {
        try {
            // CORREGIDO: Cambié de .fileprovider a .fileprovider (ya estaba bien en este caso)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                archivo
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            // Verificar si hay app para abrir PDF
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "No hay aplicación para abrir PDF", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error al abrir PDF: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("abrirPDF", "Error: ${e.message}")
        }
    }

    fun generarReciboPDF(
        context: Context,
        cliente: String,
        prestamoId: String,
        fecha: String,
        montoPagado: String,
        saldoAnterior: Double,
        proximoPago: String,
        cuota: String,
        cobrador: String,
        lugar: String,
        firma: String,
        tipoPago: String,
        mora: Double = 0.0,
        // Si se reimprime y ya conoces el saldo final, pásalo aquí para evitar recálculos
        saldoNuevoFijo: Double? = null
    ): File? {
        return try {
            // ---- Normalización y salvaguardas ----
            val pagoIngresado = montoPagado.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val saldoPrevio   = saldoAnterior.coerceAtLeast(0.0)

            // No permitir que el pago aplicado supere el saldo previo
            val pagoAplicado  = minOf(pagoIngresado, saldoPrevio)

            // Si se envía saldoNuevoFijo lo respetamos; si no, lo calculamos
            val nuevoSaldoCalc = (saldoPrevio - pagoAplicado).coerceAtLeast(0.0)
            val nuevoSaldo     = saldoNuevoFijo?.coerceAtLeast(0.0) ?: nuevoSaldoCalc

            fun fmt(n: Double) = "L. %,.2f".format(Locale.getDefault(), n)

            Log.d("ReciboPDF", buildString {
                appendLine("Generando PDF:")
                appendLine("Cliente: $cliente")
                appendLine("Préstamo: $prestamoId")
                appendLine("Pago ingresado: ${fmt(pagoIngresado)}")
                appendLine("Pago aplicado:  ${fmt(pagoAplicado)}")
                appendLine("Saldo anterior: ${fmt(saldoPrevio)}")
                appendLine("Saldo nuevo:    ${fmt(nuevoSaldo)}")
                appendLine("¿Es reimpresión? ${saldoNuevoFijo != null}")
            })

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(320, 650, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // ---- Colores ----
            val colorPrimario = Color.parseColor("#1565C0")
            val colorSecundario = Color.parseColor("#2E7D32")
            val colorMora = Color.parseColor("#D32F2F")
            val colorTexto = Color.parseColor("#212121")
            val colorFondo = Color.parseColor("#F8F9FA")

            // ---- Estilos ----
            val paintTitle = Paint().apply {
                isAntiAlias = true
                color = colorPrimario
                textAlign = Paint.Align.CENTER
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val paintSubtitle = Paint().apply {
                isAntiAlias = true
                color = colorPrimario
                textAlign = Paint.Align.CENTER
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val paintLabel = Paint().apply {
                isAntiAlias = true
                color = colorTexto
                textAlign = Paint.Align.LEFT
                textSize = 11f
                typeface = Typeface.DEFAULT
            }
            val paintValue = Paint().apply {
                isAntiAlias = true
                color = colorTexto
                textAlign = Paint.Align.LEFT
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val paintMoney = Paint().apply {
                isAntiAlias = true
                color = colorSecundario
                textAlign = Paint.Align.RIGHT
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val paintMoraPaint = Paint().apply {
                isAntiAlias = true
                color = colorMora
                textAlign = Paint.Align.RIGHT
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val paintLine = Paint().apply {
                color = colorPrimario
                strokeWidth = 1.5f
            }
            val paintBackground = Paint().apply { color = colorFondo }

            // ---- Layout ----
            var y = 15f
            val margenIzq = 15f
            val margenDer = 305f
            val espacioLinea = 16f
            val espacioSeccion = 20f

            // Encabezado con fondo
            canvas.drawRect(5f, y, 315f, y + 80f, paintBackground)

            // Logo (opcional)
            val logoBitmap = try {
                BitmapFactory.decodeResource(context.resources, R.drawable.logo_capital)
            } catch (_: Exception) { null }

            logoBitmap?.let {
                try {
                    val scaledLogo = Bitmap.createScaledBitmap(it, 50, 50, false)
                    canvas.drawBitmap(scaledLogo, 135f, y + 5f, null)
                } catch (_: Exception) {}
            }

            y += 60f

            // Título
            canvas.drawText("CAPITAL EXPRESS", 160f, y, paintTitle); y += 25f

            // Lugar / Fecha
            paintLabel.textAlign = Paint.Align.LEFT
            canvas.drawText("📍 $lugar", margenIzq, y, paintLabel)
            paintLabel.textAlign = Paint.Align.RIGHT
            canvas.drawText("📅 $fecha", margenDer, y, paintLabel)
            paintLabel.textAlign = Paint.Align.LEFT
            y += espacioSeccion

            // Separador
            canvas.drawLine(10f, y, 310f, y, paintLine); y += espacioSeccion

            // Subtítulo
            canvas.drawText("RECIBO DE PAGO", 160f, y, paintSubtitle); y += espacioSeccion

            // Caja datos cliente
            canvas.drawRect(10f, y, 310f, y + 65f, paintBackground); y += 12f

            fun dibujarCampo(etiqueta: String, valor: String, yPos: Float) {
                canvas.drawText(etiqueta, margenIzq, yPos, paintLabel)
                canvas.drawText(valor, margenIzq + 80f, yPos, paintValue)
            }

            dibujarCampo("👤 Cliente:", cliente, y); y += espacioLinea
            dibujarCampo("👨‍💼 Cobrador:", cobrador, y); y += espacioLinea
            dibujarCampo("🔢 Cuota No.:", cuota, y); y += espacioSeccion

            // Detalles del pago
            paintSubtitle.textAlign = Paint.Align.LEFT
            canvas.drawText("DETALLES DEL PAGO", margenIzq, y, paintSubtitle)
            paintSubtitle.textAlign = Paint.Align.CENTER
            y += espacioSeccion

            fun dibujarMonto(etiqueta: String, monto: Double, p: Paint = paintMoney) {
                canvas.drawText(etiqueta, margenIzq, y, paintLabel)
                canvas.drawText(fmt(monto), margenDer, y, p)
                y += espacioLinea
            }

            // Monto abonado = pagoAplicado (lo que realmente baja el saldo)
            dibujarMonto("💰 Monto abonado:", pagoAplicado)

            // Mora informativa (no afecta saldo salvo que así lo decidas en tu lógica de negocio)
            if (mora > 0.0) {
                dibujarMonto("⚠️ Incluye mora:", mora, paintMoraPaint)
            }

            y += 5f; canvas.drawLine(margenIzq, y, margenDer, y, paintLine); y += 10f

            dibujarMonto("💵 Saldo anterior:", saldoPrevio)
            dibujarMonto("💳 Saldo nuevo:", nuevoSaldo)

            y += espacioLinea
            if (proximoPago.isNotBlank() && proximoPago.lowercase() != "saldado") {
                dibujarCampo("📆 Próximo pago:", proximoPago, y); y += espacioLinea
            }
            y += espacioLinea
            dibujarCampo("💳 Método pago:", tipoPago, y); y += espacioSeccion

            // Firma
            canvas.drawLine(10f, y, 310f, y, paintLine); y += espacioSeccion
            canvas.drawRect(10f, y, 310f, y + 55f, paintBackground); y += 15f
            paintSubtitle.textAlign = Paint.Align.LEFT
            canvas.drawText("AUTORIZACIÓN", margenIzq, y, paintSubtitle); y += espacioSeccion
            canvas.drawText("✍️ Firma del cliente:", margenIzq, y, paintLabel); y += espacioLinea
            paintLine.strokeWidth = 1f
            canvas.drawLine(margenIzq, y, margenDer - 50f, y, paintLine)
            paintLine.strokeWidth = 1.5f
            y += 10f
            if (firma.isNotBlank()) {
                paintValue.textSize = 10f
                canvas.drawText(firma, margenIzq, y, paintValue)
                paintValue.textSize = 11f
            }
            y += espacioSeccion

            // Footer
            canvas.drawLine(10f, y, 310f, y, paintLine); y += 15f
            paintTitle.textSize = 12f
            paintTitle.color = colorSecundario
            canvas.drawText("🌟 ¡Gracias por su pago! 🌟", 160f, y, paintTitle); y += 15f
            paintLabel.textAlign = Paint.Align.CENTER
            paintLabel.textSize = 10f
            canvas.drawText("Su confianza es nuestro compromiso", 160f, y, paintLabel)

            pdfDocument.finishPage(page)

            // Archivo
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "recibo_${prestamoId}_$timestamp.pdf"
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            if (!outputDir.exists()) outputDir.mkdirs()
            val file = File(outputDir, fileName)
            FileOutputStream(file).use { pdfDocument.writeTo(it) }
            pdfDocument.close()

            Log.d("ReciboPDF", "✅ PDF generado: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e("ReciboPDF", "❌ Error al generar PDF", e)
            Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    fun generarCuotasPDF(
        context: Context,
        cliente: String,
        prestamoId: String,
        cuotas: List<CuotaAmortizacion>,
        totalCapital: Double,
        totalInteres: Double,
        mora: Double,
        fechaExportacion: String
    ): File? {
        return try {
            val pdf = PdfDocument()

            // Colores profesionales para empresa de préstamos
            val colorPrimario = Color.parseColor("#1E3A8A") // Azul corporativo
            val colorSecundario = Color.parseColor("#3B82F6") // Azul claro
            val colorTexto = Color.parseColor("#1F2937") // Gris oscuro
            val colorExito = Color.parseColor("#059669") // Verde para pagado
            val colorPendiente = Color.parseColor("#DC2626") // Rojo para pendiente
            val colorFondo = Color.parseColor("#F8FAFC") // Gris muy claro
            val colorLinea = Color.parseColor("#E5E7EB") // Gris claro

            // Estilos de texto mejorados
            val paintLogo = Paint().apply {
                color = colorPrimario
                textSize = 18f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintTitle = Paint().apply {
                color = colorPrimario
                textSize = 16f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintSubtitle = Paint().apply {
                color = colorTexto
                textSize = 12f
                isFakeBoldText = true
            }

            val paintHeader = Paint().apply {
                color = Color.WHITE
                textSize = 11f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintText = Paint().apply {
                color = colorTexto
                textSize = 10f
            }

            val paintTextCenter = Paint().apply {
                color = colorTexto
                textSize = 10f
                textAlign = Paint.Align.CENTER
            }

            val paintTextRight = Paint().apply {
                color = colorTexto
                textSize = 10f
                textAlign = Paint.Align.RIGHT
            }

            val paintPagado = Paint().apply {
                color = colorExito
                textSize = 10f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintPendiente = Paint().apply {
                color = colorPendiente
                textSize = 10f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintLine = Paint().apply {
                color = colorLinea
                strokeWidth = 1f
            }

            val paintHeaderBg = Paint().apply {
                color = colorPrimario
            }

            val paintTotalBg = Paint().apply {
                color = colorFondo
            }

            val ancho = 420 // Aumentado para mejor espacio
            val alto = 600
            val margen = 25f
            val espacioLinea = 16f

            var y = 40f
            var pageNum = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(ancho, alto, pageNum).create())
            var canvas = page.canvas

            fun nuevaPagina() {
                // Pie de página
                val piePagina = "Página $pageNum - Generado el $fechaExportacion"
                canvas.drawText(piePagina, ancho / 2f, alto - 15f, Paint().apply {
                    color = Color.GRAY
                    textSize = 8f
                    textAlign = Paint.Align.CENTER
                })

                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(ancho, alto, pageNum).create())
                canvas = page.canvas
                y = 40f
            }

            fun cargarLogo(): Bitmap? {
                return try {
                    val inputStream = context.assets.open("logo_capital.png")
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    // Si no se encuentra el logo, crear un placeholder
                    null
                }
            }

            // === ENCABEZADO CON LOGO ===
            val logo = cargarLogo()
            if (logo != null) {
                // Dibujar logo centrado
                val logoWidth = 60f
                val logoHeight = 40f
                val logoX = (ancho - logoWidth) / 2f
                val destRect = RectF(logoX, y, logoX + logoWidth, y + logoHeight)
                canvas.drawBitmap(logo, null, destRect, null)
                y += logoHeight + 15f
            } else {
                // Placeholder para logo
                canvas.drawText("CAPITAL EXPRESS", ancho / 2f, y, paintLogo)
                y += 25f
            }

            // Línea decorativa bajo el logo
            val paintLineDecorative = Paint().apply {
                color = colorSecundario
                strokeWidth = 3f
            }
            canvas.drawLine(ancho / 2f - 50f, y, ancho / 2f + 50f, y, paintLineDecorative)
            y += 20f

            // Título principal
            canvas.drawText("TABLA DE AMORTIZACIÓN", ancho / 2f, y, paintTitle)
            y += 25f

            // === INFORMACIÓN DEL PRÉSTAMO ===
            // Caja de información con fondo
            val infoRect = RectF(margen, y - 5f, ancho - margen, y + 45f)
            canvas.drawRoundRect(infoRect, 8f, 8f, paintTotalBg)
            canvas.drawRoundRect(infoRect, 8f, 8f, Paint().apply {
                color = colorLinea
                style = Paint.Style.STROKE
                strokeWidth = 1f
            })

            y += 10f
            canvas.drawText("PRESTAMO PERSONAL", margen + 10f, y, paintSubtitle)
            y += espacioLinea
            canvas.drawText("CLIENTE: $cliente", margen + 10f, y, paintSubtitle)
            y += espacioLinea
            canvas.drawText("FECHA DE EXPORTACIÓN: $fechaExportacion", margen + 10f, y, paintText)
            y += 25f

            // === CABECERA DE TABLA ===
            val headerHeight = 25f
            val headerRect = RectF(margen, y, ancho - margen, y + headerHeight)
            canvas.drawRoundRect(headerRect, 5f, 5f, paintHeaderBg)

            // Posiciones de columnas optimizadas y mejor espaciadas
            val colPago = margen + 25f
            val colFecha = margen + 70f
            val colMonto = margen + 150f
            val colEstado = margen + 230f
            val colResta = margen + 310f

            y += 18f
            canvas.drawText("PAGO", colPago, y, paintHeader)
            canvas.drawText("FECHA", colFecha, y, paintHeader)
            canvas.drawText("MONTO", colMonto, y, paintHeader)
            canvas.drawText("ESTADO", colEstado, y, paintHeader)
            canvas.drawText("SALDO", colResta, y, paintHeader)
            y += 15f

            val dec = DecimalFormat("#,##0.00")
            var saldoRestante = totalCapital + totalInteres + mora

            // === FILAS DE CUOTAS ===
            var filaImpar = true
            for (cuota in cuotas) {
                if (y > alto - 80) nuevaPagina()

                // Fondo alternado
                if (filaImpar) {
                    val filaRect = RectF(margen, y - 8f, ancho - margen, y + 8f)
                    canvas.drawRect(filaRect, Paint().apply { color = Color.parseColor("#F9FAFB") })
                }

                val estado = if (cuota.pagado) "PAGADO" else "PENDIENTE"
                val paintEstado = if (cuota.pagado) paintPagado else paintPendiente
                saldoRestante -= cuota.total

                // Datos de la fila con mejor alineación
                canvas.drawText("${cuota.numero}", colPago, y, paintTextCenter)
                canvas.drawText(cuota.fecha, colFecha, y, paintText)
                canvas.drawText("L. ${dec.format(cuota.total)}", colMonto, y, paintText)
                canvas.drawText(estado, colEstado, y, paintEstado)
                canvas.drawText("L. ${dec.format(saldoRestante.coerceAtLeast(0.0))}", colResta, y, paintText)

                y += espacioLinea
                filaImpar = !filaImpar
            }

            // Pie de página final
            val piePagina = "Página $pageNum - Generado el $fechaExportacion"
            canvas.drawText(piePagina, ancho / 2f, alto - 15f, Paint().apply {
                color = Color.GRAY
                textSize = 8f
                textAlign = Paint.Align.CENTER
            })

            pdf.finishPage(page)

            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "cuotas_${prestamoId}.pdf")
            pdf.writeTo(FileOutputStream(file))
            pdf.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    fun generarResumenPagosPDF(context: Context, pagos: List<PagoItem>): File? {
        return try {
            val doc = Document()
            val path = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(path, "HistorialPrestamos.pdf")

            PdfWriter.getInstance(doc, FileOutputStream(file))
            doc.open()

            val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18f)
            val textFont = FontFactory.getFont(FontFactory.HELVETICA, 13f)

            doc.add(Paragraph("CAPITAL EXPRESS", titleFont))
            doc.add(Paragraph("HISTORIAL DE PRÉSTAMOS", titleFont))
            doc.add(Paragraph("\n"))

            var total = 0.0
            pagos.forEach { pago ->
                doc.add(Paragraph("Cliente: ${pago.cliente}", textFont))
                doc.add(Paragraph("Préstamo ID: ${pago.prestamoId}", textFont))
                doc.add(Paragraph("Fecha: ${pago.fecha}", textFont))
                doc.add(Paragraph("Monto: L. %.2f".format(pago.monto), textFont))
                if (pago.interesTotal > 0.0) doc.add(
                    Paragraph(
                        "Interés: L. %.2f".format(pago.interesTotal),
                        textFont
                    )
                )
                if (pago.mora > 0.0) doc.add(Paragraph("Mora: L. %.2f".format(pago.mora), textFont))
                if (pago.cuota.isNotBlank()) doc.add(Paragraph("Cuota: ${pago.cuota}", textFont))
                if (pago.cobrador.isNotBlank()) doc.add(
                    Paragraph(
                        "Cobrador: ${pago.cobrador}",
                        textFont
                    )
                )
                doc.add(Paragraph("------------------------------", textFont))
                total += pago.monto
            }

            doc.add(Paragraph("TOTAL PAGADO: L. %.2f".format(total), titleFont))
            doc.close()
            file
        } catch (e: Exception) {
            Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    fun compartirReciboPDF(context: Context, file: File) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "El archivo PDF no existe", Toast.LENGTH_SHORT).show()
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Recibo de Pago - Capital Express")
                putExtra(Intent.EXTRA_TEXT, "Adjunto encontrarás el recibo de pago generado por Capital Express.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Compartir recibo PDF")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(chooser)
            Log.d("ReciboPDF", "✅ PDF compartido exitosamente: ${file.name}")

        } catch (e: Exception) {
            Log.e("ReciboPDF", "❌ Error al compartir PDF", e)
            Toast.makeText(context, "Error al compartir PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

}

    data class PagoSimple(
        val fecha: String,
        val monto: Double
    )




