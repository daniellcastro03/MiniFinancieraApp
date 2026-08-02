package com.example.capitalexpressapp.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.capitalexpressapp.core.formatearLempiras
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.itextpdf.text.Document
import com.itextpdf.text.Font
import com.itextpdf.text.FontFactory
import com.itextpdf.text.Paragraph
import com.itextpdf.text.pdf.PdfWriter
import com.itextpdf.text.BaseColor
import java.util.Date
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
import com.example.capitalexpressapp.ui.screens.CuotaPendiente
import com.example.capitalexpressapp.ui.screens.EstadoClienteFiltro
import com.example.minifinancieraapp.ui.models.ClienteModel
import com.example.minifinancieraapp.ui.models.PagoItem
import com.example.minifinancieraapp.ui.models.Prestamo
import com.google.common.collect.Table
import com.google.firebase.firestore.Query
import com.itextpdf.text.PageSize
import com.itextpdf.text.Phrase
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import kotlinx.coroutines.Dispatchers
import java.util.Comparator
import kotlin.comparisons.naturalOrder
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import com.google.firebase.Timestamp
import kotlin.math.max

data class CuotaAmortizacion(
    val numero: Int,
    val fecha: String,
    val capital: Double,
    val interes: Double,
    val total: Double,
    val pagado: Boolean = false,
    val montoPagado: Double = 0.0,
    val fechaPago: String? = null
)

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
            val fechaGeneracion =
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText("FECHA DE GENERACIÓN: $fechaGeneracion", margen + 15f, y, paintText)
            y += 40f

            // === MÉTRICAS PRINCIPALES ===
            canvas.drawText("MÉTRICAS PRINCIPALES", margen, y, paintSubtitle)
            y += 30f

            val dec = DecimalFormat("#,##0.00")
            val decInt = DecimalFormat("#,##0")

            // Función para dibujar una métrica con color
            fun dibujarMetrica(
                label: String,
                value: String,
                metricaColor: Int,
                yPos: Float
            ): Float {
                // Icono de color
                val iconRect = RectF(margen, yPos - 8f, margen + 16f, yPos + 8f)
                canvas.drawRoundRect(iconRect, 4f, 4f, Paint().apply { color = metricaColor })

                // Label
                canvas.drawText(label, margen + 25f, yPos, paintLabel)

                // Value
                canvas.drawText(value, ancho - margen, yPos, paintValue)

                // Línea separadora sutil
                canvas.drawLine(
                    margen + 25f,
                    yPos + 10f,
                    ancho - margen,
                    yPos + 10f,
                    Paint().apply {
                        color = colorLinea
                        strokeWidth = 1f
                    })

                return yPos + espacioLinea + 5f
            }

            // Métricas generales
            y = dibujarMetrica("Total de Clientes:", decInt.format(clientes), colorSecundario, y)
            y = dibujarMetrica(
                "Total de Cobros Realizados:",
                decInt.format(cobros),
                colorSecundario,
                y
            )

            y += 10f
            canvas.drawText("ANÁLISIS FINANCIERO", margen, y, paintSubtitle)
            y += 25f

            // Métricas financieras con colores temáticos
            y = dibujarMetrica(
                "Monto Total Prestado:",
                "L.${dec.format(prestado)}",
                Color.parseColor("#8B5CF6"),
                y
            )
            y = dibujarMetrica("Total Pagado:", "L.${dec.format(pagado)}", colorExito, y)
            y = dibujarMetrica("Saldo Pendiente:", "L.${dec.format(pendiente)}", colorPendiente, y)
            y = dibujarMetrica(
                "Total de Intereses:",
                "L.${dec.format(interes)}",
                colorAdvertencia,
                y
            )

            y += 10f
            canvas.drawText("CONTROL DE MORAS", margen, y, paintSubtitle)
            y += 25f

            y = dibujarMetrica(
                "Total Moras Cobradas:",
                "L.${dec.format(moras)}",
                Color.parseColor("#DC2626"),
                y
            )
            y = dibujarMetrica(
                "Cantidad de Moras Aplicadas:",
                decInt.format(cantidadMoras),
                Color.parseColor("#B91C1C"),
                y
            )

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
            val porcentajePendiente =
                if (totalGeneral > 0) (pendiente / totalGeneral) * 100 else 0.0

            canvas.drawText("Total General (Capital + Interés):", margen + 15f, y, paintText)
            canvas.drawText(
                "L.${dec.format(totalGeneral)}",
                ancho - margen - 15f,
                y,
                Paint().apply {
                    color = colorPrimario
                    textSize = 12f
                    isFakeBoldText = true
                    textAlign = Paint.Align.RIGHT
                })
            y += espacioLinea

            canvas.drawText("Porcentaje Pagado:", margen + 15f, y, paintText)
            canvas.drawText(
                "%.1f%%".format(porcentajePagado),
                ancho - margen - 15f,
                y,
                Paint().apply {
                    color = colorExito
                    textSize = 12f
                    isFakeBoldText = true
                    textAlign = Paint.Align.RIGHT
                })
            y += espacioLinea

            canvas.drawText("Porcentaje Pendiente:", margen + 15f, y, paintText)
            canvas.drawText(
                "%.1f%%".format(porcentajePendiente),
                ancho - margen - 15f,
                y,
                Paint().apply {
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

            canvas.drawText(
                "Danlí, El Paraíso - Tu socio financiero de confianza",
                ancho / 2f,
                y,
                paintSmall
            )
            y += 15f

            canvas.drawText(
                "www.capitalexpress.hn | Tel: +504 0000-0000",
                ancho / 2f,
                y,
                paintSmall
            )

            // Información de generación
            canvas.drawText(
                "Reporte generado automáticamente el $fechaGeneracion",
                ancho / 2f,
                alto - 20f,
                paintSmall
            )

            pdf.finishPage(page)

            val fileName = "ResumenDashboard_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }.pdf"
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
        cobrador: String,
        proximoPago: String = "",
        metodoPago: String = "Efectivo"
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
            val smallFont = Font(Font.FontFamily.HELVETICA, 9f)

            document.add(Paragraph("CAPITAL EXPRESS", titleFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph("RECIBO DE ABONO", titleFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph(" "))

            // Información del cliente
            document.add(Paragraph("Fecha: $fecha", labelFont))
            document.add(Paragraph("Cliente: $cliente", textFont))
            document.add(Paragraph("Préstamo ID: $prestamoId", textFont))
            document.add(Paragraph("Registrado por: $cobrador", textFont))
            document.add(Paragraph(" "))

            // Línea separadora
            document.add(Paragraph("─────────────────────────────", smallFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph(" "))

            // Cuotas pagadas (con mejor formato y saltos de línea)
            document.add(Paragraph("CUOTAS PAGADAS:", labelFont))
            document.add(Paragraph(" "))

            // Separar las cuotas por comas y mostrarlas en líneas individuales
            val cuotas = cuota.split(",")
            cuotas.forEach { cuotaItem ->
                val cuotaLimpia = cuotaItem.trim()
                val cuotaParagraph = Paragraph("  • $cuotaLimpia", textFont)
                cuotaParagraph.indentationLeft = 10f
                cuotaParagraph.setLeading(0f, 1.5f)
                document.add(cuotaParagraph)
            }

            document.add(Paragraph(" "))

            // Línea separadora
            document.add(Paragraph("─────────────────────────────", smallFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph(" "))

            // Saldos con mejor formato
            document.add(Paragraph("DETALLE DE PAGO:", labelFont))
            document.add(Paragraph(" "))

            document.add(Paragraph("Saldo anterior:    ${formatearLempiras(saldoAnterior)}", textFont))
            document.add(Paragraph("Monto abonado:     ${formatearLempiras(montoAbonado)}", textFont))

            document.add(Paragraph(" "))
            document.add(Paragraph("─────────────────────────────", smallFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph(" "))

            document.add(
                Paragraph(
                    "Nuevo saldo:       ${formatearLempiras(nuevoSaldo)}",
                    labelFont
                ).apply {
                    setLeading(0f, 1.2f)
                })
            document.add(Paragraph(" "))
            document.add(Paragraph(" "))

            // CAMPOS CORREGIDOS - CON SALTOS DE LÍNEA
            if (proximoPago.isNotBlank()) {
                document.add(Paragraph("Próximo pago:", labelFont))
                document.add(Paragraph(proximoPago, textFont))
                document.add(Paragraph(" "))
            }

            document.add(Paragraph("Método de pago:", labelFont))
            document.add(Paragraph(metodoPago, textFont))
            document.add(Paragraph(" "))

            // Línea separadora
            document.add(Paragraph("─────────────────────────────", smallFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph(" "))

            // Firma
            document.add(Paragraph("Firma del cobrador:", textFont))
            document.add(Paragraph(" "))
            document.add(Paragraph("_____________________________", textFont))
            document.add(Paragraph(" "))
            document.add(Paragraph(" "))

            // Footer
            document.add(Paragraph("Gracias por su pago.", textFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph("Capital Express - Danlí, El Paraíso", smallFont).apply {
                alignment = Element.ALIGN_CENTER
            })
            document.add(Paragraph("Tu socio financiero de confianza", smallFont).apply {
                alignment = Element.ALIGN_CENTER
            })

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

            val printer = EscPosPrinter(printerConnection, 203, 80f, 48)
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
                builder.append("[L]<b>Monto:</b> ${formatearLempiras(pago.monto)}\n")
                if (pago.interesTotal > 0.0)
                    builder.append("[L]<b>Interés:</b> ${formatearLempiras(pago.interesTotal)}\n")
                if (pago.mora > 0.0)
                    builder.append("[L]<b>Mora:</b> ${formatearLempiras(pago.mora)}\n")
                if (pago.cuota.isNotBlank())
                    builder.append("[L]<b>Cuota:</b> ${pago.cuota}\n")
                if (pago.cobrador.isNotBlank())
                    builder.append("[L]<b>Cobrador:</b> ${pago.cobrador}\n")

                builder.append("[L]------------------------------\n")
                total += pago.monto
            }

            builder.append("\n[C]===============================\n")
            builder.append("[C]<b>TOTAL PAGADO:</b> ${formatearLempiras(total)}\n")
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
        mora: Double = 0.0,
        montoAplicadoCuota: Double? = null,
        saldoNuevoFijo: Double? = null,
        cuotasTotales: Int? = null
    ): Boolean {
        return try {
            val printerConnection = BluetoothPrintersConnections.selectFirstPaired()
            if (printerConnection == null) {
                Toast.makeText(context, "No hay impresora conectada", Toast.LENGTH_SHORT).show()
                return false
            }

            val printer = EscPosPrinter(printerConnection, 203, 80f, 48)
            val builder = StringBuilder()

            builder.append("[C]================================\n")
            builder.append("[C]<font size='big'><b>CAPITAL EXPRESS</b></font>\n")
            builder.append("[C]================================\n")
            builder.append("[C]Inversiones Victoria\n")
            builder.append("[C]Danlí, El Paraíso\n")
            builder.append("[C]<i>Tu socio financiero de confianza</i>\n")
            builder.append("[C]================================\n")

            builder.append("[L]<b>FECHA:</b> $fecha\n")
            builder.append("[L]<b>LUGAR:</b> $lugar\n")
            builder.append("[L]--------------------------------\n")

            val cuotaMostrar = if (cuotasTotales != null && cuotasTotales > 0) "$cuota de $cuotasTotales" else cuota
            builder.append("[L]<b>CLIENTE:</b> $cliente\n")
            builder.append("[L]<b>ID PRÉSTAMO:</b> $prestamoId\n")
            builder.append("[L]<b>CUOTA Nº:</b> $cuotaMostrar\n")
            builder.append("[L]--------------------------------\n")

            builder.append("[L]<b>TIPO DE PAGO:</b> $tipoPago\n")
            builder.append("[L]<b>COBRADOR:</b> $cobrador\n")

            val saldoAntesDeMora = (saldoAnterior - mora).coerceAtLeast(0.0)
            builder.append("[L]<b>SALDO ANTERIOR:</b> ${formatearLempiras(saldoAntesDeMora)}\n")
            if (mora > 0.0) {
                builder.append("[L]<b>MORA APLICADA:</b> ${formatearLempiras(mora)}\n")
                builder.append("[L]<b>SALDO ANTERIOR (CON MORA):</b> ${formatearLempiras(saldoAnterior)}\n")
                if (montoAplicadoCuota != null) {
                    builder.append("[L]<b>APLICADO A CUOTA:</b> ${formatearLempiras(montoAplicadoCuota)}\n")
                    builder.append("[L]<b>APLICADO A MORA:</b> ${formatearLempiras(mora)}\n")
                    builder.append(
                        "[L]Se tomaron ${formatearLempiras(montoAplicadoCuota)} para la cuota " +
                            "y ${formatearLempiras(mora)} de mora.\n"
                    )
                }
            }

            val pagado = montoPagado.toDoubleOrNull() ?: 0.0
            val saldoRestante = saldoNuevoFijo ?: (saldoAnterior + mora - pagado)

            builder.append("[L]<b>TOTAL PAGADO:</b> ${formatearLempiras(pagado)}\n")
            builder.append("[L]<b>SALDO PENDIENTE:</b> ${formatearLempiras(saldoRestante)}\n")
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
            true

        } catch (e: Exception) {
            Toast.makeText(context, "Error al imprimir: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Recibo de préstamo (desembolso) impreso directo por Bluetooth a la térmica,
     * misma calibración (203dpi/48mm/32 cols) que [imprimirRecibo] para que no
     * salga cortado ni con letra fuera de tamaño.
     */
    fun imprimirReciboPrestamoDirecto(
        context: Context,
        cliente: String,
        telefono: String = "",
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
    ): Boolean {
        return try {
            val printerConnection = BluetoothPrintersConnections.selectFirstPaired()
            if (printerConnection == null) {
                Toast.makeText(context, "No hay impresora conectada", Toast.LENGTH_SHORT).show()
                return false
            }

            val printer = EscPosPrinter(printerConnection, 203, 80f, 48)
            val builder = StringBuilder()

            builder.append("[C]================================\n")
            builder.append("[C]<font size='big'><b>CAPITAL EXPRESS</b></font>\n")
            builder.append("[C]================================\n")
            builder.append("[C]Inversiones Victoria\n")
            builder.append("[C]Danlí, El Paraíso\n")
            builder.append("[C]================================\n")

            builder.append("[L]<b>FECHA:</b> $fecha\n")
            builder.append("[L]<b>LUGAR:</b> $lugar\n")
            builder.append("[L]--------------------------------\n")

            builder.append("[L]<b>PRÉSTAMO Nº:</b> $numeroPrestamo\n")
            builder.append("[L]<b>CLIENTE:</b> $cliente\n")
            if (telefono.isNotBlank()) {
                builder.append("[L]<b>TEL:</b> $telefono\n")
            }
            builder.append("[L]--------------------------------\n")

            builder.append("[L]<b>COBRADOR:</b> $nombreCobrador\n")
            builder.append("[L]<b>MONTO PRESTADO:</b> ${formatearLempiras(monto)}\n")
            builder.append("[L]<b>INTERÉS:</b> ${formatearLempiras(interesTotal)}\n")
            if (mora > 0.0) {
                builder.append("[L]<b>MORA:</b> ${formatearLempiras(mora)}\n")
            }
            builder.append("[L]<b>TOTAL A PAGAR:</b> ${formatearLempiras(monto + interesTotal)}\n")
            builder.append("[L]<b>CUOTAS:</b> $cuotas\n")
            if (fechaProximoPago.isNotBlank()) {
                builder.append("[L]<b>PRÓXIMO PAGO:</b> $fechaProximoPago\n")
            }
            builder.append("[L]--------------------------------\n")

            builder.append("[L]\n")
            builder.append("[L]Firma del cliente:\n")
            builder.append("[L]______________________________\n")

            builder.append("[C]================================\n")
            builder.append("[C]Gracias por su confianza\n")
            builder.append("[C]<b>Capital Express</b>\n")
            builder.append("[C]<i>Tu aliado en finanzas</i>\n")

            printer.printFormattedTextAndCut(builder.toString())
            true

        } catch (e: Exception) {
            Toast.makeText(context, "Error al imprimir: ${e.message}", Toast.LENGTH_LONG).show()
            false
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
        return try {
            // Dimensiones: 5cm ancho x 20cm alto
            val pageWidth = 189    // 5cm = 189 puntos (REDUCIDO)
            val pageHeight = 756   // 20cm = 756 puntos
            val margin = 15f       // Márgenes ajustados
            val contentWidth = pageWidth - (margin * 2)

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            canvas.drawColor(Color.WHITE)

            // Paints ajustados para 5cm de ancho
            val paintHeader = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = 14f  // Reducido para caber en 5cm
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isAntiAlias = true
            }

            val paintSmall = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = 9f  // Reducido
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }

            val paintLabel = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.LEFT
                textSize = 8f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }

            val paintValue = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.RIGHT
                textSize = 8f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }

            val paintAmount = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = 16f  // Destacado pero que quepa
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isAntiAlias = true
            }

            val paintLine = Paint().apply {
                color = Color.BLACK
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
            }

            fun fmt(n: Double): String {
                val formatter = java.text.DecimalFormat("#,##0.00",
                    java.text.DecimalFormatSymbols(Locale.US).apply {
                        groupingSeparator = ','
                        decimalSeparator = '.'
                    })
                return "L${formatter.format(n)}"
            }

            // Función para dividir texto
            fun splitText(text: String, maxWidth: Float, paint: Paint): List<String> {
                if (paint.measureText(text) <= maxWidth) return listOf(text)

                val words = text.split(" ")
                val lines = mutableListOf<String>()
                var currentLine = ""

                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (paint.measureText(testLine) <= maxWidth) {
                        currentLine = testLine
                    } else {
                        if (currentLine.isNotEmpty()) lines.add(currentLine)
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                return lines.ifEmpty { listOf(text) }
            }

            val lineSpacing = 12f
            var y = 20f

            // Logo ajustado
            runCatching {
                BitmapFactory.decodeResource(context.resources, R.drawable.logo_capital)?.let {
                    val logoSize = 45
                    val scaled = Bitmap.createScaledBitmap(it, logoSize, logoSize, true)
                    canvas.drawBitmap(scaled, (pageWidth - logoSize) / 2f, y, null)
                    y += logoSize + 10f
                }
            }.onFailure {
                y += 10f
            }

            // Encabezado
            canvas.drawText("CAPITAL", pageWidth / 2f, y, paintHeader)
            y += 16f
            canvas.drawText("EXPRESS", pageWidth / 2f, y, paintHeader)
            y += 14f
            canvas.drawText("FINANCIERA", pageWidth / 2f, y, paintSmall)
            y += 12f

            // Número de cobrador
            canvas.drawText(numeroCobrador, pageWidth / 2f, y, paintSmall)
            y += 14f

            // Línea
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Lugar
            paintSmall.textAlign = Paint.Align.CENTER
            val lugarLines = splitText(lugar, contentWidth, paintSmall)
            lugarLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 11f
            }
            y += 4f

            // Información del préstamo
            val prestamoLines = splitText("Prestamo N° $numeroPrestamo", contentWidth, paintSmall)
            prestamoLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 11f
            }

            val docNumber = fecha.replace("/", "")
            canvas.drawText("Doc $docNumber", pageWidth / 2f, y, paintSmall)
            y += 14f

            // Cliente
            val clienteLines = splitText("Sr(a) ${cliente.nombre}", contentWidth, paintSmall)
            paintSmall.textAlign = Paint.Align.CENTER
            clienteLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 11f
            }
            y += 8f

            // Monto del préstamo
            val total = monto + interesTotal
            canvas.drawText("Prestamo", pageWidth / 2f, y, paintSmall)
            y += 14f
            canvas.drawText(fmt(total), pageWidth / 2f, y, paintAmount)
            y += 20f

            // Cuotas
            paintSmall.textAlign = Paint.Align.CENTER
            canvas.drawText("$cuotas cuotas", pageWidth / 2f, y, paintSmall)
            y += 14f

            // Línea
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Detalles en formato compacto
            fun drawCompactLine(label: String, value: String) {
                paintLabel.textAlign = Paint.Align.LEFT
                canvas.drawText(label, margin, y, paintLabel)
                paintValue.textAlign = Paint.Align.RIGHT
                canvas.drawText(value, pageWidth - margin, y, paintValue)
                y += lineSpacing
            }

            drawCompactLine("Cuotas", "$cuotas")
            drawCompactLine("Fecha", fecha)
            drawCompactLine("Monto", fmt(monto))
            drawCompactLine("Capital", fmt(monto))
            drawCompactLine("Interes", fmt(interesTotal))

            if (mora > 0.0) {
                drawCompactLine("Mora", fmt(mora))
            }

            y += 8f
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Total
            paintLabel.textAlign = Paint.Align.LEFT
            paintLabel.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paintLabel.textSize = 9f
            canvas.drawText("Total:", margin, y, paintLabel)
            paintLabel.typeface = Typeface.MONOSPACE
            paintLabel.textSize = 8f

            paintAmount.textAlign = Paint.Align.RIGHT
            paintAmount.textSize = 14f
            canvas.drawText(fmt(total), pageWidth - margin, y, paintAmount)
            paintAmount.textSize = 16f
            y += 20f

            // Próximo pago
            if (fechaProximoPago.isNotBlank()) {
                paintSmall.textAlign = Paint.Align.CENTER
                canvas.drawText("Proximo:", pageWidth / 2f, y, paintSmall)
                y += 11f
                canvas.drawText(fechaProximoPago, pageWidth / 2f, y, paintSmall)
                y += 14f
            }

            // Teléfono
            if (cliente.telefono.isNotBlank()) {
                paintSmall.textAlign = Paint.Align.CENTER
                canvas.drawText("Tel:", pageWidth / 2f, y, paintSmall)
                y += 11f
                canvas.drawText(cliente.telefono, pageWidth / 2f, y, paintSmall)
                y += 12f
            }

            // Dirección
            if (cliente.direccionCasa.isNotBlank()) {
                val direccionLines = splitText(cliente.direccionCasa, contentWidth, paintSmall)
                paintSmall.textSize = 7f
                direccionLines.forEach { line ->
                    canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                    y += 10f
                }
                paintSmall.textSize = 9f
                y += 8f
            }

            // Contacto
            paintSmall.textAlign = Paint.Align.CENTER
            paintSmall.textSize = 7f
            val contactoLines = splitText("Consultas llamar:", contentWidth, paintSmall)
            contactoLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 10f
            }
            canvas.drawText("($numeroCobrador)", pageWidth / 2f, y, paintSmall)
            y += 14f
            paintSmall.textSize = 9f

            // Línea
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Fecha y hora
            val timestamp = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            val timeStamp = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            paintSmall.textAlign = Paint.Align.CENTER
            paintSmall.textSize = 7f
            canvas.drawText(timestamp, pageWidth / 2f, y, paintSmall)
            y += 10f
            canvas.drawText(timeStamp, pageWidth / 2f, y, paintSmall)
            y += 12f

            // Mensaje final
            paintSmall.textSize = 8f
            paintSmall.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val mensajeLines = splitText("CONSERVE ESTE RECIBO", contentWidth, paintSmall)
            mensajeLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 10f
            }
            y += 8f

            // Espacio para firma
            canvas.drawLine(margin + 20f, y, pageWidth - margin - 20f, y, paintLine)
            y += 10f
            paintSmall.textSize = 7f
            canvas.drawText("Firma Cliente", pageWidth / 2f, y, paintSmall)

            // Guardar PDF
            pdfDocument.finishPage(page)

            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "recibo_prestamo_$numeroPrestamo.pdf"
            )

            FileOutputStream(file).use { fos ->
                pdfDocument.writeTo(fos)
            }
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
        incluirInfoCliente: Boolean = true,
        cobradorUid: String? = null,  // UID del cobrador
        cobradoresMap: Map<String, String> = emptyMap()  // uid -> nombre
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

            // Mostrar nombre del cobrador si está disponible
            if (!cobradorUid.isNullOrBlank() && cobradorUid != "Sin asignar") {
                val nombreCobrador = cobradoresMap[cobradorUid] ?: run {
                    // Fallback: mostrar parte del UID si no se encuentra el nombre
                    if (cobradorUid.length > 8) "${cobradorUid.take(8)}…" else cobradorUid
                }
                canvas.drawText("Cobrador: $nombreCobrador", 40f, y.toFloat(), paint)
                y += 20
            }

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
            canvas.drawText("Precio: ${formatearLempiras(monto)}", 40f, y.toFloat(), paint)

            val pagos = pagosPorPrestamo[producto] ?: emptyList()
            val totalPagado = pagos.sumOf { it.monto }
            val restante = monto - totalPagado

            y += 18
            canvas.drawText("Pagado: ${formatearLempiras(totalPagado)}", 40f, y.toFloat(), paint)
            y += 18
            canvas.drawText("Resta: ${formatearLempiras(restante)}", 40f, y.toFloat(), paint)
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
                Log.d(
                    "ReciboPDF",
                    "✅ PDF abierto con app de impresión: ${printApp.activityInfo.packageName}"
                )
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
            Saldo Anterior: ${formatearLempiras(saldoAnterior)}
            Próximo Pago: $proximoPago
            Cuota: $cuota
            Registrado por: $cobrador
            *************************************
            Gracias por su pago.
        """.trimIndent()
    }

    private suspend fun obtenerFechaCancelacionPrestamo(
        prestamoId: String,
        pagosFiltrados: List<PagoItem>
    ): String {
        return try {
            val db = FirebaseFirestore.getInstance()
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

            // 🔥 PASO 1: Obtener información del préstamo desde Firestore
            val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

            if (!prestamoDoc.exists()) {
                Log.w("FechaCancelacion", "⚠️ Préstamo $prestamoId no existe")
                return "Sin datos"
            }

            val clienteNombre = prestamoDoc.getString("cliente") ?: "Sin nombre"
            val estadoPrestamo = prestamoDoc.getString("estado") ?: "activo"
            val saldoActual = prestamoDoc.getDouble("saldo") ?: 0.0

            Log.d("FechaCancelacion", """
            🔍 Procesando: $clienteNombre
            • Préstamo ID: $prestamoId
            • Estado en Firestore: $estadoPrestamo
            • Saldo actual: L. $saldoActual
        """.trimIndent())

            // 🔥 PASO 2: Obtener TODOS los pagos del préstamo (NO usar pagosFiltrados)
            val todosPagosSnapshot = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .get()
                .await()

            Log.d("FechaCancelacion", "  📦 Total pagos en Firestore: ${todosPagosSnapshot.size()}")

            if (todosPagosSnapshot.isEmpty) {
                Log.d("FechaCancelacion", "  ⚠️ No hay pagos registrados")
                return "Sin pagos"
            }

            // 🔥 PASO 3: Convertir todos los pagos a una lista ordenada por fecha
            // CORRECCIÓN CRÍTICA: Buscar "fechaPago" primero, luego "fecha"
            val todosPagos = todosPagosSnapshot.documents.mapNotNull { doc ->
                // 🔥 Intentar obtener el timestamp del campo correcto
                val timestamp = doc.getTimestamp("fechaPago") ?: doc.getTimestamp("fecha")

                if (timestamp == null) {
                    Log.w("FechaCancelacion", "  ⚠️ Pago ${doc.id} no tiene campo 'fechaPago' ni 'fecha'")
                    return@mapNotNull null
                }

                val fechaDate = timestamp.toDate()
                val monto = doc.getDouble("monto") ?: 0.0

                Triple(doc.id, fechaDate, monto)
            }.sortedByDescending { it.second } // Ordenar del más reciente al más antiguo

            if (todosPagos.isEmpty()) {
                Log.d("FechaCancelacion", "  ⚠️ No se pudieron procesar los pagos (sin timestamps válidos)")
                return "Sin pagos"
            }

            Log.d("FechaCancelacion", "  ✅ Pagos procesados correctamente: ${todosPagos.size}")

            // 🔥 PASO 4: Calcular si el préstamo está realmente saldado
            val montoPrestamo = prestamoDoc.getDouble("monto") ?: 0.0
            val interes = prestamoDoc.getDouble("interesTotal")
                ?: prestamoDoc.getDouble("interes")
                ?: 0.0
            val totalAPagar = montoPrestamo + interes
            val totalPagado = todosPagos.sumOf { it.third }
            val saldoCalculado = (totalAPagar - totalPagado).coerceAtLeast(0.0)

            // Determinar si está saldado (con margen de error de 1 lempira)
            val estaSaldado = estadoPrestamo.equals("saldado", ignoreCase = true)
                    || saldoActual <= 0.01
                    || saldoCalculado <= 1.0

            Log.d("FechaCancelacion", """
            💰 Cálculo de saldo:
            • Monto préstamo: L. $montoPrestamo
            • Interés: L. $interes
            • Total a pagar: L. $totalAPagar
            • Total pagado: L. $totalPagado
            • Saldo calculado: L. $saldoCalculado
            • ¿Está saldado?: $estaSaldado
        """.trimIndent())

            // 🔥 PASO 5: Obtener la fecha del último pago
            val ultimoPago = todosPagos.firstOrNull() // Ya está ordenado descendente

            if (ultimoPago != null) {
                val fechaFormateada = sdf.format(ultimoPago.second)

                if (estaSaldado) {
                    Log.d("FechaCancelacion", "  ✅ SALDADO - Fecha de cancelación: $fechaFormateada")
                } else {
                    Log.d("FechaCancelacion", "  📅 ACTIVO - Último pago: $fechaFormateada")
                }

                return fechaFormateada
            }

            return "Sin fecha"

        } catch (e: Exception) {
            Log.e("FechaCancelacion", "❌ Error al obtener fecha de cancelación", e)
            return "Error"
        }
    }

    suspend fun generarResumenPagosPDF(
        context: Context,
        pagos: List<PagoItem>,
        fechaInicio: Date,
        fechaFin: Date,
        periodo: String
    ): File? {

        // Formatters locales, thread-safe
        val sdf     = SimpleDateFormat("dd/MM/yyyy",       Locale.getDefault())
        val sdfHora = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        if (pagos.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "No hay pagos para generar el reporte", Toast.LENGTH_SHORT).show()
            }
            return null
        }

        // ── Parser robusto de fecha (reutiliza el mismo orden que la pantalla) ──
        val formattersParse = listOf(
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()),
            SimpleDateFormat("dd/MM/yyyy HH:mm",    Locale.getDefault()),
            SimpleDateFormat("dd/MM/yyyy",           Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
            SimpleDateFormat("yyyy-MM-dd",           Locale.getDefault())
        ).also { list -> list.forEach { it.isLenient = false } }

        fun parsearFecha(fechaStr: String): Date {
            val limpia = fechaStr.trim()
            for (fmt in formattersParse) {
                try {
                    val d = fmt.parse(limpia)
                    if (d != null) return d
                } catch (_: Exception) { }
            }
            Log.w("GenerarPDF", "No se pudo parsear fecha '$fechaStr', usando Date()")
            return Date()
        }

        fun soloFecha(fechaStr: String): String {
            return try { sdf.format(parsearFecha(fechaStr)) } catch (_: Exception) { fechaStr }
        }

        // ── Estructura interna de fila ─────────────────────────────────────────
        data class Row(
            val cliente:        String,
            val numeroPrestamo: String,
            val fechaPago:      Date,
            val fechaPagoStr:   String,
            val fechaCancel:    String,
            val monto:          Double,
            val mora:           Double,
            val cuota:          String,
            val cobrador:       String,
            val prestamoId:     String
        )

        // ── Obtener fechas de cancelación con timeout individual ───────────────
        Log.d("GenerarPDF", "🔄 Obteniendo fechas de cancelación para ${pagos.distinctBy { it.prestamoId }.size} préstamos...")

        val fechasCancelacion = mutableMapOf<String, String>()
        for (pago in pagos.distinctBy { it.prestamoId }) {
            try {
                val fecha = obtenerFechaCancelacionPrestamo(pago.prestamoId, pagos)
                fechasCancelacion[pago.prestamoId] = fecha
            } catch (e: Exception) {
                Log.e("GenerarPDF", "Error obteniendo cancelación de ${pago.prestamoId}: ${e.message}")
                fechasCancelacion[pago.prestamoId] = "-"
            }
        }

        // ── Construir filas ordenadas ──────────────────────────────────────────
        val rows: List<Row> = pagos.map { pago ->
            val fechaDate = parsearFecha(pago.fecha)
            Row(
                cliente        = pago.cliente,
                numeroPrestamo = pago.numeroPrestamo.ifEmpty { "-" },
                fechaPago      = fechaDate,
                fechaPagoStr   = soloFecha(pago.fecha),
                fechaCancel    = fechasCancelacion[pago.prestamoId] ?: "-",
                monto          = pago.monto,
                mora           = pago.mora,
                cuota          = pago.cuota,
                cobrador       = pago.cobrador,
                prestamoId     = pago.prestamoId
            )
        }.sortedBy { it.fechaPago }

        Log.d("GenerarPDF", "📊 Generando PDF con ${rows.size} filas...")

        // ── Configuración de página ────────────────────────────────────────────
        val pageWidth  = 595
        val pageHeight = 842
        val margin     = 32f
        val headerY    = 120f
        val rowH       = 18f

        val colorPrimario = Color.parseColor("#1E3A8A")
        val colorTexto    = Color.parseColor("#1F2937")
        val colorLinea    = Color.parseColor("#E5E7EB")

        val titlePaint = Paint().apply {
            isAntiAlias = true; textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
        }
        val subPaint = Paint().apply {
            isAntiAlias = true; textSize = 11f; color = Color.DKGRAY
        }
        val headerPaint = Paint().apply {
            isAntiAlias = true; textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
        }
        val cellPaint = Paint().apply {
            isAntiAlias = true; textSize = 8.5f; color = colorTexto
        }
        val linePaint = Paint().apply { color = colorLinea; strokeWidth = 1f }
        val headerBg  = Paint().apply { color = colorPrimario }

        // Columnas: Cliente | N° | F.Pago | F.Cancel | Abono | Mora | Total | Cuota | Cobrador
        val colXs = floatArrayOf(
            margin,           // Cliente
            margin + 105f,    // N° Préstamo
            margin + 155f,    // Fecha Pago
            margin + 205f,    // Fecha Cancelación
            margin + 265f,    // Abono
            margin + 315f,    // Mora
            margin + 360f,    // Total
            margin + 410f,    // Cuota
            margin + 455f     // Cobrador
        )
        val headers = arrayOf("Cliente", "N° Prést", "F. Pago", "F. Cancel.", "Abono", "Mora", "Total", "Cuota", "Cobrador")

        val pdf = PdfDocument()
        var pageNum = 1
        var y       = 0f

        fun newPage(): PdfDocument.Page {
            val pi   = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            val pg   = pdf.startPage(pi)
            val c    = pg.canvas

            c.drawText("Resumen de Pagos – Cobrador",       margin, 38f, titlePaint)
            c.drawText("Periodo: $periodo",                  margin, 56f, subPaint)
            c.drawText(
                "Rango: ${sdf.format(fechaInicio)} a ${sdf.format(fechaFin)}   |   Generado: ${sdfHora.format(Date())}",
                margin, 72f, subPaint
            )
            c.drawText("Total registros: ${rows.size}", margin, 88f, subPaint)

            // Cabecera de tabla
            c.drawRect(margin, headerY - 14f, (pageWidth - margin), headerY + 6f, headerBg)
            for (i in headers.indices) {
                c.drawText(headers[i], colXs[i] + 2f, headerY, headerPaint)
            }
            c.drawLine(margin, headerY + 8f, (pageWidth - margin).toFloat(), headerY + 8f, linePaint)

            y = headerY + 22f
            return pg
        }

        fun footer(canvas: Canvas) {
            val p = Paint().apply { isAntiAlias = true; textSize = 9f; color = Color.GRAY }
            canvas.drawText("Página $pageNum", pageWidth / 2f - 20f, (pageHeight - 20).toFloat(), p)
        }

        var page   = newPage()
        var canvas = page.canvas

        var totalMonto = 0.0
        var totalMora  = 0.0

        // ── Pintar filas ────────────────────────────────────────────────────────
        rows.forEachIndexed { idx, r ->
            if (y + rowH > pageHeight - 55f) {
                footer(canvas)
                pdf.finishPage(page)
                pageNum++
                page   = newPage()
                canvas = page.canvas
            }

            // Fondo alternado
            if (idx % 2 == 0) {
                canvas.drawRect(
                    margin, y - rowH + 4f,
                    (pageWidth - margin).toFloat(), y + 4f,
                    Paint().apply { color = Color.parseColor("#F8FAFF") }
                )
            }

            val cols = arrayOf(
                r.cliente.take(16),
                r.numeroPrestamo,
                r.fechaPagoStr,
                r.fechaCancel,
                "L %,.2f".format(r.monto),
                if (r.mora > 0) "L %,.2f".format(r.mora) else "-",
                "L %,.2f".format(r.monto + r.mora), // <-- NUEVO COLUMNA TOTAL
                r.cuota.take(7),
                r.cobrador.take(14)
            )

            for (i in cols.indices) {
                canvas.drawText(cols[i], colXs[i] + 2f, y, cellPaint)
            }
            canvas.drawLine(margin, y + 4f, (pageWidth - margin).toFloat(), y + 4f, linePaint)
            y += rowH

            totalMonto += r.monto
            totalMora  += r.mora
        }

        // ── Fila de totales ─────────────────────────────────────────────────────
        if (y + 2 * rowH > pageHeight - 55f) {
            footer(canvas)
            pdf.finishPage(page)
            pageNum++
            page   = newPage()
            canvas = page.canvas
        }

        val totalY = y + 12f
        val totPaint = Paint().apply {
            isAntiAlias = true; textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = colorPrimario
        }
        canvas.drawRect(
            margin, totalY - 14f,
            (pageWidth - margin).toFloat(), totalY + 6f,
            Paint().apply { color = Color.parseColor("#EEF2FF") }
        )
        canvas.drawText("Registros: ${rows.size}", margin + 4f, totalY, totPaint)
        val totalRecaudado = totalMonto + totalMora
        canvas.drawText(
            "Abonos: L %,.2f   •   Mora: L %,.2f   •   TOTAL RECAUDADO: L %,.2f".format(totalMonto, totalMora, totalRecaudado),
            margin + 120f, totalY, totPaint
        )

        footer(canvas)
        pdf.finishPage(page)

        // ── Guardar ─────────────────────────────────────────────────────────────
        return try {
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            if (!outputDir.exists()) outputDir.mkdirs()

            val file = File(outputDir, "reporte_cobrador_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { pdf.writeTo(it) }
            pdf.close()

            Log.d("GenerarPDF", "✅ PDF generado: ${file.absolutePath} (${file.length()} bytes)")
            file
        } catch (e: Exception) {
            Log.e("GenerarPDF", "❌ Error guardando PDF: ${e.message}", e)
            pdf.close()
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error al guardar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            }
            null
        }
    }

    suspend fun generarResumenPrestamosSaldadosPDF(
        context: Context,
        pagos: List<PagoItem>,
        fechaInicio: Date?,
        fechaFin: Date?,
        periodo: String
    ): File? {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val sdfHora = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val db = FirebaseFirestore.getInstance()

        if (pagos.isEmpty()) return null

        // Agrupar pagos por préstamo
        val prestamosPorId = pagos.groupBy { it.prestamoId }

        data class PrestamoSaldado(
            val cliente: String,
            val numeroPrestamo: String,
            val montoPrestado: Double,
            val totalAbonado: Double,
            val fechaCreacion: String,
            val fechaFinalizacion: String
        )

        val prestamosSaldados = mutableListOf<PrestamoSaldado>()

        Log.d("GenerarPDFSaldados", "🔄 Procesando ${prestamosPorId.size} préstamos saldados...")

        // 🔥 Obtener información de cada préstamo
        for ((prestamoId, pagosPrestamo) in prestamosPorId) {
            try {
                val prestamoDoc = db.collection("prestamos").document(prestamoId).get().await()

                val cliente = prestamoDoc.getString("cliente") ?: "Sin nombre"
                val numeroPrestamo = prestamoDoc.getString("numeroPrestamo")
                    ?: prestamoDoc.getLong("numeroPrestamo")?.toString()
                    ?: "-"
                val montoPrestado = prestamoDoc.getDouble("monto") ?: 0.0

                // Calcular total abonado (suma de todos los pagos)
                val totalAbonado = pagosPrestamo.sumOf { it.monto + it.mora }

                // Fecha de creación del préstamo
                val fechaCreacionTimestamp = prestamoDoc.getTimestamp("fecha")
                val fechaCreacion = fechaCreacionTimestamp?.toDate()?.let { sdf.format(it) } ?: "-"

                // 🔥 CORRECCIÓN: Obtener la fecha REAL del último pago que saldó el préstamo
                // NO usar los pagos filtrados, sino TODOS los pagos del préstamo
                val fechaFinalizacion = obtenerFechaFinalizacionPrestamo(prestamoId)

                Log.d("GenerarPDFSaldados", "  • $cliente (N° $numeroPrestamo) - Finalizado: $fechaFinalizacion")

                prestamosSaldados.add(
                    PrestamoSaldado(
                        cliente = cliente,
                        numeroPrestamo = numeroPrestamo,
                        montoPrestado = montoPrestado,
                        totalAbonado = totalAbonado,
                        fechaCreacion = fechaCreacion,
                        fechaFinalizacion = fechaFinalizacion
                    )
                )
            } catch (e: Exception) {
                Log.e("GenerarPDFSaldados", "❌ Error obteniendo préstamo $prestamoId: ${e.message}")
            }
        }

        Log.d("GenerarPDFSaldados", "✅ ${prestamosSaldados.size} préstamos procesados")

        // Ordenar por fecha de finalización
        prestamosSaldados.sortBy {
            try {
                sdf.parse(it.fechaFinalizacion)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }

        // ✅ Configuración de página
        val pageWidth = 595
        val pageHeight = 842
        val margin = 32f
        val headerY = 120f
        val rowH = 20f

        val colorPrimario = Color.parseColor("#059669") // Verde para saldados
        val colorTexto = Color.parseColor("#1F2937")
        val colorLinea = Color.parseColor("#E5E7EB")

        val titlePaint = Paint().apply {
            isAntiAlias = true
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
        }
        val subPaint = Paint().apply {
            isAntiAlias = true
            textSize = 12f
            color = Color.DKGRAY
        }
        val headerPaint = Paint().apply {
            isAntiAlias = true
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
        }
        val cellPaint = Paint().apply {
            isAntiAlias = true
            textSize = 9f
            color = colorTexto
        }
        val linePaint = Paint().apply {
            color = colorLinea
            strokeWidth = 1f
        }
        val headerBg = Paint().apply {
            color = colorPrimario
        }

        // Posiciones de columnas
        val colXs = floatArrayOf(
            margin,              // Cliente
            margin + 120f,       // Nº Préstamo
            margin + 200f,       // Monto Prestado
            margin + 290f,       // Total Abonado
            margin + 380f,       // Fecha Creación
            margin + 460f        // Fecha Finalización
        )

        val pdf = PdfDocument()
        var pageNum = 1
        var y = 0f

        fun newPage(): PdfDocument.Page {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            val page = pdf.startPage(pageInfo)
            val c = page.canvas

            c.drawText("Préstamos Saldados", margin, 40f, titlePaint)
            c.drawText("Periodo: $periodo", margin, 58f, subPaint)

            val rangoTexto = if (fechaInicio != null && fechaFin != null) {
                "Rango: ${sdf.format(fechaInicio)} a ${sdf.format(fechaFin)}"
            } else {
                "Todos los préstamos saldados"
            }
            c.drawText(
                "$rangoTexto   |   Generado: ${sdfHora.format(Date())}",
                margin, 74f, subPaint
            )

            // Encabezados
            c.drawRect(margin, headerY - 14f, pageWidth - margin, headerY + 6f, headerBg)
            val headers = arrayOf("Cliente", "Nº Préstamo", "Prestado", "Abonado", "F. Creación", "F. Finalización")
            for (i in headers.indices) {
                c.drawText(headers[i], colXs[i] + 2f, headerY, headerPaint)
            }
            c.drawLine(margin, headerY + 8f, pageWidth - margin, headerY + 8f, linePaint)

            y = headerY + 24f
            return page
        }

        fun footer(canvas: Canvas) {
            val p = Paint().apply {
                isAntiAlias = true
                textSize = 10f
                color = Color.GRAY
            }
            canvas.drawText("Página $pageNum", pageWidth / 2f - 20f, pageHeight - 24f, p)
        }

        var page = newPage()
        var canvas = page.canvas

        var totalPrestado = 0.0
        var totalAbonado = 0.0

        prestamosSaldados.forEach { prestamo ->
            if (y + rowH > pageHeight - 60f) {
                footer(canvas)
                pdf.finishPage(page)
                pageNum++
                page = newPage()
                canvas = page.canvas
            }

            val cols = arrayOf(
                prestamo.cliente,
                prestamo.numeroPrestamo,
                "L. %,.2f".format(Locale.getDefault(), prestamo.montoPrestado),
                "L. %,.2f".format(Locale.getDefault(), prestamo.totalAbonado),
                prestamo.fechaCreacion,
                prestamo.fechaFinalizacion // 🔥 Ahora muestra la fecha real del último pago
            )

            for (i in cols.indices) {
                canvas.drawText(cols[i], colXs[i] + 2f, y, cellPaint)
            }
            canvas.drawLine(margin, y + 4f, pageWidth - margin, y + 4f, linePaint)
            y += rowH

            totalPrestado += prestamo.montoPrestado
            totalAbonado += prestamo.totalAbonado
        }

        // Totales
        if (y + 3 * rowH > pageHeight - 60f) {
            footer(canvas)
            pdf.finishPage(page)
            pageNum++
            page = newPage()
            canvas = page.canvas
        }

        y += 10f
        canvas.drawRect(margin, y - 14f, pageWidth - margin, y + 30f, Paint().apply {
            color = Color.parseColor("#F0FDF4") // Fondo verde claro
        })

        val totalPaint = Paint().apply {
            isAntiAlias = true
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = colorPrimario
        }

        canvas.drawText("Préstamos Saldados: ${prestamosSaldados.size}", margin + 10f, y + 5f, totalPaint)
        canvas.drawText(
            "Total Prestado: L. %,.2f".format(Locale.getDefault(), totalPrestado),
            margin + 10f, y + 20f, totalPaint
        )
        canvas.drawText(
            "Total Recaudado: L. %,.2f".format(Locale.getDefault(), totalAbonado),
            margin + 260f, y + 20f, totalPaint
        )

        footer(canvas)
        pdf.finishPage(page)

        return try {
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "prestamos_saldados_${System.currentTimeMillis()}.pdf"
            )
            FileOutputStream(file).use { pdf.writeTo(it) }
            pdf.close()

            Log.d("GenerarPDFSaldados", "✅ PDF de préstamos saldados generado: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e("GenerarPDFSaldados", "❌ Error al guardar PDF: ${e.message}", e)
            pdf.close()
            Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private suspend fun obtenerFechaFinalizacionPrestamo(prestamoId: String): String {
        return try {
            val db = FirebaseFirestore.getInstance()
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

            Log.d("FechaFinalizacion", "🔍 Buscando fecha de finalización para préstamo: $prestamoId")

            // 🔥 Obtener TODOS los pagos del préstamo (sin filtrar)
            val todosPagos = db.collection("pagos")
                .whereEqualTo("prestamoId", prestamoId)
                .get()
                .await()

            if (todosPagos.isEmpty) {
                Log.w("FechaFinalizacion", "  ⚠️ No hay pagos para este préstamo")
                return "Sin pagos"
            }

            Log.d("FechaFinalizacion", "  📦 Encontrados ${todosPagos.size()} pagos")

            // 🔥 CORRECCIÓN CRÍTICA: Buscar "fechaPago" primero, luego "fecha"
            val pagosOrdenados = todosPagos.documents.mapNotNull { doc ->
                // Intentar obtener el timestamp del campo correcto
                val timestamp = doc.getTimestamp("fechaPago") ?: doc.getTimestamp("fecha")

                if (timestamp == null) {
                    Log.w("FechaFinalizacion", "  ⚠️ Pago ${doc.id} no tiene campo 'fechaPago' ni 'fecha'")
                    return@mapNotNull null
                }

                val fechaDate = timestamp.toDate()
                Pair(doc.id, fechaDate)
            }.sortedByDescending { it.second } // Del más reciente al más antiguo

            if (pagosOrdenados.isEmpty()) {
                Log.w("FechaFinalizacion", "  ⚠️ No se pudieron procesar los pagos (sin timestamps válidos)")
                return "Sin fecha"
            }

            Log.d("FechaFinalizacion", "  ✅ Pagos procesados correctamente: ${pagosOrdenados.size}")

            // 🔥 El último pago es el que saldó el préstamo
            val ultimoPago = pagosOrdenados.firstOrNull()
            if (ultimoPago != null) {
                val fechaFormateada = sdf.format(ultimoPago.second)
                Log.d("FechaFinalizacion", "  ✅ Fecha de finalización encontrada: $fechaFormateada")
                return fechaFormateada
            }

            return "Sin fecha"

        } catch (e: Exception) {
            Log.e("FechaFinalizacion", "❌ Error al obtener fecha de finalización", e)
            return "Error"
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
            Triple(index + 1, nombreCliente, "${formatearLempiras(monto)}  |  $fecha")
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
                Toast.makeText(context, "No hay aplicación para abrir PDF", Toast.LENGTH_LONG)
                    .show()
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
        cobrador: String?,
        lugar: String,
        firma: String,
        tipoPago: String,
        mora: Double = 0.0,
        saldoNuevoFijo: Double? = null,
        montoAplicadoCuota: Double? = null,
        cuotasTotales: Int? = null
    ): File? {
        return try {
            Log.d("ReciboPDF", "cliente='$cliente', cobrador='${cobrador ?: "null"}'")

            val pagoIngresado = montoPagado.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val saldoPrevio = saldoAnterior.coerceAtLeast(0.0)

            val nuevoSaldo = if (saldoNuevoFijo != null) {
                saldoNuevoFijo.coerceAtLeast(0.0)
            } else {
                val pagoAplicado = minOf(pagoIngresado, saldoPrevio)
                (saldoPrevio - pagoAplicado).coerceAtLeast(0.0)
            }

            fun fmt(n: Double): String {
                val formatter = java.text.DecimalFormat("#,##0.00",
                    java.text.DecimalFormatSymbols(Locale.US).apply {
                        groupingSeparator = ','
                        decimalSeparator = '.'
                    })
                return "L${formatter.format(n)}"
            }

            val pageWidth = 189
            val pageHeight = 756
            val margin = 15f
            val contentWidth = pageWidth - (margin * 2)

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            canvas.drawColor(Color.WHITE)

            val paintHeader = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = 14f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isAntiAlias = true
            }

            val paintSmall = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = 9f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }

            val paintLabel = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.LEFT
                textSize = 8f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }

            val paintValue = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.RIGHT
                textSize = 8f
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }

            val paintAmount = Paint().apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                textSize = 16f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                isAntiAlias = true
            }

            val paintLine = Paint().apply {
                color = Color.BLACK
                strokeWidth = 1.5f
                style = Paint.Style.STROKE
            }

            fun splitText(text: String, maxWidth: Float, paint: Paint): List<String> {
                if (paint.measureText(text) <= maxWidth) return listOf(text)

                val words = text.split(" ")
                val lines = mutableListOf<String>()
                var currentLine = ""

                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (paint.measureText(testLine) <= maxWidth) {
                        currentLine = testLine
                    } else {
                        if (currentLine.isNotEmpty()) lines.add(currentLine)
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                return lines.ifEmpty { listOf(text) }
            }

            val lineSpacing = 12f
            var y = 20f

            // Logo — más grande y con un anillo marcado alrededor para que no se
            // pierda al imprimirlo (antes era muy chico y quedaba borroso/plano).
            runCatching {
                BitmapFactory.decodeResource(context.resources, R.drawable.logo_capital)?.let {
                    val logoSize = 85
                    val scaled = Bitmap.createScaledBitmap(it, logoSize, logoSize, true)
                    val cx = pageWidth / 2f
                    val cy = y + logoSize / 2f
                    val radio = logoSize / 2f + 4f
                    canvas.drawCircle(cx, cy, radio, Paint().apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeWidth = 2.5f
                        isAntiAlias = true
                    })
                    canvas.drawBitmap(scaled, (pageWidth - logoSize) / 2f, y, null)
                    y += logoSize + 14f
                }
            }.onFailure {
                y += 10f
            }

            // Encabezado
            canvas.drawText("CAPITAL", pageWidth / 2f, y, paintHeader)
            y += 16f
            canvas.drawText("EXPRESS", pageWidth / 2f, y, paintHeader)
            y += 14f
            canvas.drawText("FINANCIERA", pageWidth / 2f, y, paintSmall)
            y += 16f

            // Línea
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Lugar
            paintSmall.textAlign = Paint.Align.CENTER
            val lugarLines = splitText(lugar, contentWidth, paintSmall)
            lugarLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 11f
            }
            y += 4f

            // Información del préstamo
            val prestamoLines = splitText("Prestamo N° $prestamoId", contentWidth, paintSmall)
            prestamoLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 11f
            }

            canvas.drawText("Doc ${fecha.replace("/", "")}", pageWidth / 2f, y, paintSmall)
            y += 14f

            // Cliente
            val clienteLines = splitText("Sr(a) $cliente", contentWidth, paintSmall)
            paintSmall.textAlign = Paint.Align.CENTER
            clienteLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 11f
            }
            y += 8f

            // Monto del abono
            canvas.drawText("Abono", pageWidth / 2f, y, paintSmall)
            y += 14f
            canvas.drawText(fmt(pagoIngresado), pageWidth / 2f, y, paintAmount)
            y += 20f

            // Cuotas completadas (centrado, sin truncar)
            val cuotaMostrar = if (cuotasTotales != null && cuotasTotales > 0) "$cuota de $cuotasTotales" else cuota
            val cuotasPagadas = cuota.split(" de ").firstOrNull() ?: cuota
            paintSmall.textAlign = Paint.Align.CENTER
            val cuotasLines = splitText("$cuotasPagadas completas", contentWidth, paintSmall)
            cuotasLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 11f
            }
            y += 8f

            // Línea
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Detalles en formato compacto
            fun drawCompactLine(label: String, value: String) {
                paintLabel.textAlign = Paint.Align.LEFT
                canvas.drawText(label, margin, y, paintLabel)
                paintValue.textAlign = Paint.Align.RIGHT
                canvas.drawText(value, pageWidth - margin, y, paintValue)
                y += lineSpacing
            }

            // ✅ CAMBIO: Cuotas completas sin truncar, en líneas separadas
            paintLabel.textAlign = Paint.Align.LEFT
            canvas.drawText("Cuotas:", margin, y, paintLabel)
            y += lineSpacing

            val cuotaLines = splitText(cuotaMostrar, contentWidth, paintLabel)
            cuotaLines.forEach { line ->
                canvas.drawText(line, margin, y, paintLabel)
                y += lineSpacing
            }

            val saldoAntesDeMora = (saldoPrevio - mora).coerceAtLeast(0.0)
            drawCompactLine("Fecha", fecha)
            drawCompactLine("Saldo anterior", fmt(saldoAntesDeMora))

            if (mora > 0.0) {
                drawCompactLine("Mora aplicada", fmt(mora))
                drawCompactLine("Saldo anterior (con mora)", fmt(saldoPrevio))
            }

            drawCompactLine("Abono", fmt(pagoIngresado))

            if (mora > 0.0) {
                if (montoAplicadoCuota != null) {
                    drawCompactLine("Aplicado a cuota", fmt(montoAplicadoCuota))
                    drawCompactLine("Aplicado a mora", fmt(mora))

                    y += 4f
                    paintLabel.textAlign = Paint.Align.LEFT
                    val explicacion = "Se tomaron ${fmt(montoAplicadoCuota)} para la cuota y " +
                        "${fmt(mora)} de mora."
                    val explicacionLines = splitText(explicacion, contentWidth, paintLabel)
                    explicacionLines.forEach { line ->
                        canvas.drawText(line, margin, y, paintLabel)
                        y += lineSpacing
                    }
                }
            }

            y += 8f
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Saldo nuevo
            paintLabel.textAlign = Paint.Align.LEFT
            paintLabel.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            paintLabel.textSize = 9f
            canvas.drawText("Saldo pendiente:", margin, y, paintLabel)
            paintLabel.typeface = Typeface.MONOSPACE
            paintLabel.textSize = 8f

            paintAmount.textAlign = Paint.Align.RIGHT
            paintAmount.textSize = 14f
            canvas.drawText(fmt(nuevoSaldo), pageWidth - margin, y, paintAmount)
            paintAmount.textSize = 16f
            y += 20f

            // Próximo pago
            if (proximoPago.isNotBlank() && !proximoPago.equals("saldado", ignoreCase = true)) {
                paintSmall.textAlign = Paint.Align.CENTER
                canvas.drawText("Proximo:", pageWidth / 2f, y, paintSmall)
                y += 11f
                canvas.drawText(proximoPago, pageWidth / 2f, y, paintSmall)
                y += 14f
            }

            // Contacto
            paintSmall.textAlign = Paint.Align.CENTER
            paintSmall.textSize = 7f
            val contactoLines = splitText("Consultas llamar:", contentWidth, paintSmall)
            contactoLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 10f
            }

            cobrador?.let {
                canvas.drawText("($it)", pageWidth / 2f, y, paintSmall)
                y += 14f
            } ?: run { y += 10f }
            paintSmall.textSize = 9f

            // Línea
            canvas.drawLine(margin, y, pageWidth - margin, y, paintLine)
            y += 14f

            // Fecha y hora
            val timestamp = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            val timeStamp = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            paintSmall.textAlign = Paint.Align.CENTER
            paintSmall.textSize = 7f
            canvas.drawText(timestamp, pageWidth / 2f, y, paintSmall)
            y += 10f
            canvas.drawText(timeStamp, pageWidth / 2f, y, paintSmall)
            y += 12f

            // Mensaje final
            paintSmall.textSize = 8f
            paintSmall.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            val mensajeLines = splitText("CONSERVE ESTE RECIBO", contentWidth, paintSmall)
            mensajeLines.forEach { line ->
                canvas.drawText(line, pageWidth / 2f, y, paintSmall)
                y += 10f
            }

            // Guardar PDF
            pdfDocument.finishPage(page)

            val timestampFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "recibo_${prestamoId.replace(" ", "_")}_$timestampFile.pdf"
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
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

    suspend fun generarReporteClientesPDF(
        context: Context,
        clientes: List<ClienteModel>,
        prestamosPorCliente: Map<String, List<Prestamo>>,
        filtroEstado: EstadoClienteFiltro,           // TODOS | ACTIVOS | SALDADOS
        filtroCobrador: String?,                    // uid normalizado del filtro (o null = todos)
        nombresCobradores: Map<String, String> = emptyMap(),
        cobradoresPorClienteFromPagos: Map<String, Set<String>> = emptyMap()
    ): File? {

        fun asDateOrNull(any: Any?): Date? = when (any) {
            is Timestamp -> any.toDate()
            is Date -> any
            is String -> try {
                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(any)
            } catch (_: Exception) {
                null
            }
            else -> null
        }

        // misma lógica que la pantalla
        fun normalizarUidCobrador(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            val limpio = raw.trim()

            val basura = listOf(
                "Sin asignar", "Sin_asignar",
                "COBRADOR", "Cobrador",
                "Desconocido",
                "N/A",
                "No asignado", "No_asignado",
                "No asignar", "Sin asignar."
            )

            if (basura.any { basuraVal ->
                    limpio.equals(basuraVal, ignoreCase = true)
                }
            ) {
                return ""
            }

            return limpio
        }

        fun totales(clienteId: String): Triple<Double, Double, Double> {
            val prs = prestamosPorCliente[clienteId].orEmpty()
            var prestado = 0.0
            var abonado = 0.0
            var pendiente = 0.0

            prs.forEach { p ->
                val totalPagar = if ((p.totalPagar ?: 0.0) > 0) {
                    p.totalPagar!!
                } else {
                    p.monto + (p.interesTotal ?: p.interes)
                }

                prestado += totalPagar
                abonado += (p.montoPagado ?: 0.0)
                pendiente += kotlin.math.max(
                    0.0,
                    totalPagar - (p.montoPagado ?: 0.0)
                )
            }

            return Triple(prestado, abonado, pendiente)
        }

        fun estadoEfectivo(c: ClienteModel): String {
            val prs = prestamosPorCliente[c.id].orEmpty()
            val todosSaldados = prs.isNotEmpty() && prs.all { p ->
                val total = (p.totalPagar ?: 0.0).takeIf { it > 0 }
                    ?: (p.monto + (p.interesTotal ?: p.interes))

                val pagado = p.montoPagado ?: 0.0

                (total - pagado) <= 0.01 ||
                        p.estado.equals("saldado", true) ||
                        p.estado.equals("completado", true)
            }

            return if (todosSaldados || c.estado.equals("saldado", true)) {
                "saldado"
            } else {
                "activo"
            }
        }

        fun proximoPagoCliente(clienteId: String): Date? {
            val hoy = Date()
            val fechas = prestamosPorCliente[clienteId].orEmpty()
                .filter { (it.saldo ?: 0.0) > 0.01 && it.proximoPago != null }
                .mapNotNull { asDateOrNull(it.proximoPago) }

            val futuras = fechas.filter { !it.before(hoy) }
            return futuras.minOrNull() ?: fechas.minOrNull()
        }

        // 🔥 misma regla que en la pantalla
        fun pasaCobradorParaCliente(c: ClienteModel, filtroUid: String?): Boolean {
            if (filtroUid.isNullOrBlank()) return true

            val uidClienteNorm = normalizarUidCobrador(c.cobradorAsignado)

            val matchCliente = uidClienteNorm == filtroUid

            val matchPrestamo = prestamosPorCliente[c.id]
                .orEmpty()
                .any { p ->
                    normalizarUidCobrador(p.cobradorAsignado) == filtroUid
                }

            val matchPago = cobradoresPorClienteFromPagos[c.id]
                ?.any { uidPago -> uidPago == filtroUid }
                ?: false

            return matchCliente || matchPrestamo || matchPago
        }

        return try {
            if (clientes.isEmpty()) {
                Toast.makeText(
                    context,
                    "No hay clientes para exportar",
                    Toast.LENGTH_SHORT
                ).show()
                return null
            }

            // === 1) Asegurar mapa id -> nombre bonito de cobradores ===
            val nombresPorId: Map<String, String> =
                if (nombresCobradores.isNotEmpty()) {
                    nombresCobradores
                } else {
                    try {
                        val snap = FirebaseFirestore.getInstance()
                            .collection("usuarios")
                            .get()
                            .await()

                        snap.documents
                            .filter {
                                val rol = it.getString("rol")
                                    ?.lowercase(Locale.getDefault())
                                rol == "cobrador" || rol == "admin"
                            }
                            .associate { it.id to (it.getString("nombre") ?: it.id) }
                    } catch (e: Exception) {
                        Log.w(
                            "ReporteClientesPDF",
                            "No se pudo cargar 'usuarios': ${e.message}"
                        )
                        emptyMap()
                    }
                }

            // Helper: resolver nombre del cobrador a partir del id
            fun nombreCobrador(id: String?): String {
                if (id.isNullOrBlank()) return "No asignado"
                return nombresPorId[id] ?: id
            }

            val sdfCab = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

            // === 2) Aplicar filtros EXACTAMENTE igual que en pantalla ===
            val base = clientes.filter { c ->
                val cobOk = pasaCobradorParaCliente(c, filtroCobrador)

                val estadoOk = when (filtroEstado) {
                    EstadoClienteFiltro.TODOS -> true
                    EstadoClienteFiltro.ACTIVOS -> estadoEfectivo(c) == "activo"
                    EstadoClienteFiltro.SALDADOS -> estadoEfectivo(c) == "saldado"
                }

                cobOk && estadoOk
            }.sortedBy { it.nombre.lowercase(Locale.getDefault()) }

            // === 3) Crear archivo PDF ===
            val file = File(
                context.getExternalFilesDir(null),
                "Reporte_Clientes_${System.currentTimeMillis()}.pdf"
            )

            val document = Document(PageSize.LETTER, 36f, 36f, 36f, 36f)
            PdfWriter.getInstance(document, FileOutputStream(file))
            document.open()

            val titleFont = Font(Font.FontFamily.HELVETICA, 16f, Font.BOLD)
            val headerFont = Font(Font.FontFamily.HELVETICA, 12f, Font.BOLD)
            val normalFont = Font(Font.FontFamily.HELVETICA, 10f, Font.NORMAL)

            // === 4) Encabezado ===
            document.add(Paragraph("Capital Express", titleFont))
            document.add(
                Paragraph(
                    "Reporte de Clientes — ${sdfCab.format(Date())}",
                    normalFont
                )
            )

            val filtrosTxt = buildString {
                append("Filtros: ")
                append(
                    when (filtroEstado) {
                        EstadoClienteFiltro.TODOS -> "Estado: Todos"
                        EstadoClienteFiltro.ACTIVOS -> "Estado: Activos"
                        EstadoClienteFiltro.SALDADOS -> "Estado: Saldados"
                    }
                )
                append(" | Cobrador: ")
                append(
                    if (filtroCobrador.isNullOrBlank()) {
                        "Todos"
                    } else {
                        nombreCobrador(filtroCobrador)
                    }
                )
            }

            document.add(Paragraph(filtrosTxt, normalFont))
            document.add(Paragraph(" "))

            // === 5) Agrupar por cobrador ASIGNADO al cliente (cobradorAsignado),
            // para secciones tipo "COBRADOR: Fulano"
            val porCobrador: Map<String?, List<ClienteModel>> =
                base.groupBy { c -> c.cobradorAsignado }

            // Ordenar manualmente los cobradores (compatible con API 23)
            val cobradoresOrdenados = porCobrador.keys
                .sortedWith(compareBy(
                    { it == null },  // Los null primero
                    { it }           // Luego ordenar alfabéticamente
                ))

            fun tablaClientes(items: List<ClienteModel>): PdfPTable {
                val table = PdfPTable(8)
                table.widthPercentage = 100f
                table.setWidths(
                    floatArrayOf(
                        3.0f, // Cliente
                        2.0f, // Teléfono
                        3.0f, // Dirección
                        1.2f, // Prestado
                        1.2f, // Abonado
                        1.2f, // Pendiente
                        1.4f, // Estado
                        1.6f  // Próximo pago
                    )
                )

                fun header(text: String) {
                    val cell = PdfPCell(
                        Paragraph(text, headerFont)
                    ).apply {
                        horizontalAlignment = Element.ALIGN_CENTER
                        backgroundColor = BaseColor(0xE3, 0xF2, 0xFD)
                    }
                    table.addCell(cell)
                }

                header("Cliente")
                header("Teléfono")
                header("Dirección")
                header("Prestado")
                header("Abonado")
                header("Pendiente")
                header("Estado")
                header("Próximo pago")

                items.forEach { c ->
                    val (pres, abo, pen) = totales(c.id)
                    val estadoTxt =
                        estadoEfectivo(c).uppercase(Locale.getDefault())
                    val proxTxt =
                        proximoPagoCliente(c.id)?.let { sdf.format(it) } ?: "—"

                    table.addCell(Paragraph(c.nombre, normalFont))
                    table.addCell(
                        Paragraph(
                            c.telefono.ifBlank { "N/D" },
                            normalFont
                        )
                    )
                    table.addCell(
                        Paragraph(
                            c.direccionCasa.ifBlank { c.direccionNegocio },
                            normalFont
                        )
                    )
                    table.addCell(
                        Paragraph(formatearLempiras(pres), normalFont)
                    )
                    table.addCell(
                        Paragraph(formatearLempiras(abo), normalFont)
                    )
                    table.addCell(
                        Paragraph(formatearLempiras(pen), normalFont)
                    )
                    table.addCell(Paragraph(estadoTxt, normalFont))
                    table.addCell(Paragraph(proxTxt, normalFont))
                }

                return table
            }

            // Usar cobradoresOrdenados en lugar de porCobrador.toSortedMap(orden)
            cobradoresOrdenados.forEach { cobradorId ->
                val lista = porCobrador[cobradorId] ?: return@forEach

                document.add(
                    Paragraph(
                        "COBRADOR: ${nombreCobrador(cobradorId)}",
                        headerFont
                    )
                )
                document.add(
                    Paragraph("Clientes: ${lista.size}", normalFont)
                )
                document.add(Paragraph(" "))
                document.add(tablaClientes(lista))
                document.add(Paragraph(" "))

                var subPres = 0.0
                var subAbo = 0.0
                var subPen = 0.0
                var subAct = 0
                var subSal = 0

                lista.forEach { c ->
                    val (a, b, d) = totales(c.id)
                    subPres += a
                    subAbo += b
                    subPen += d

                    if (estadoEfectivo(c) == "saldado") {
                        subSal++
                    } else {
                        subAct++
                    }
                }

                val subt = PdfPTable(5).apply {
                    widthPercentage = 100f
                    setWidths(
                        floatArrayOf(
                            1.2f,
                            1.2f,
                            1.2f,
                            1f,
                            1f
                        )
                    )

                    fun cell(label: String) =
                        addCell(
                            PdfPCell(
                                Paragraph(label, normalFont)
                            ).apply {
                                horizontalAlignment = Element.ALIGN_CENTER
                            }
                        )

                    cell("Prestado: ${formatearLempiras(subPres)}")
                    cell("Abonado: ${formatearLempiras(subAbo)}")
                    cell("Pendiente: ${formatearLempiras(subPen)}")
                    cell("Activos: $subAct")
                    cell("Saldados: $subSal")
                }

                document.add(subt)
                document.add(Paragraph(" "))
            }

            // === 7) Bloques globales por estado
            fun bloqueEstado(titulo: String, filtro: (ClienteModel) -> Boolean) {
                val items = base.filter(filtro)
                if (items.isNotEmpty()) {
                    document.add(Paragraph(titulo, headerFont))
                    document.add(
                        Paragraph(
                            "Total: ${items.size}",
                            normalFont
                        )
                    )
                    document.add(Paragraph(" "))
                    document.add(tablaClientes(items))
                    document.add(Paragraph(" "))
                }
            }

            bloqueEstado("CLIENTES ACTIVOS") { estadoEfectivo(it) == "activo" }
            bloqueEstado("CLIENTES SALDADOS") { estadoEfectivo(it) == "saldado" }

            // === 8) Resumen general final
            var gPres = 0.0
            var gAbo = 0.0
            var gPen = 0.0
            var gAct = 0
            var gSal = 0

            base.forEach { c ->
                val (a, b, d) = totales(c.id)
                gPres += a
                gAbo += b
                gPen += d

                if (estadoEfectivo(c) == "saldado") {
                    gSal++
                } else {
                    gAct++
                }
            }

            document.add(Paragraph("RESUMEN GENERAL", headerFont))

            val resumen = PdfPTable(5).apply {
                widthPercentage = 100f
                setWidths(
                    floatArrayOf(
                        1.2f,
                        1.2f,
                        1.2f,
                        1f,
                        1f
                    )
                )

                fun cell(label: String) =
                    addCell(
                        PdfPCell(
                            Paragraph(label, normalFont)
                        ).apply {
                            horizontalAlignment = Element.ALIGN_CENTER
                        }
                    )

                cell("Prestado: ${formatearLempiras(gPres)}")
                cell("Abonado: ${formatearLempiras(gAbo)}")
                cell("Pendiente: ${formatearLempiras(gPen)}")
                cell("Activos: $gAct")
                cell("Saldados: $gSal")
            }

            document.add(resumen)

            document.close()
            file
        } catch (e: Exception) {
            Log.e("ReporteClientesPDF", "Error: ${e.message}", e)
            Toast.makeText(
                context,
                "Error al generar PDF: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            null
        }
    }

    // Agregar esta función a la clase ReciboHelper

    fun generarResumenCuotasPendientesPDF(
        context: Context,
        cuotasPorCliente: List<Pair<String, List<CuotaPendiente>>>,
        filtroCliente: String = "",
        filtroCobradorNombre: String = ""
    ): File? {
        return try {
            val pdfDocument = PdfDocument()
            val paint = Paint()
            val titlePaint = Paint().apply {
                textSize = 20f
                isFakeBoldText = true
                color = Color.parseColor("#0061A7")
                textAlign = Paint.Align.CENTER
            }
            val headerPaint = Paint().apply {
                textSize = 14f
                isFakeBoldText = true
                color = Color.parseColor("#0061A7")
            }
            val normalPaint = Paint().apply {
                textSize = 11f
                color = Color.BLACK
            }
            val smallPaint = Paint().apply {
                textSize = 9f
                color = Color.GRAY
            }
            val totalPaint = Paint().apply {
                textSize = 12f
                isFakeBoldText = true
                color = Color.parseColor("#D32F2F")
            }

            val pageWidth = 595 // A4
            val pageHeight = 842
            val margin = 40f
            val contentWidth = pageWidth - (2 * margin)

            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas
            var yPos = margin

            // Función para crear nueva página
            fun nuevaPagina() {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                yPos = margin
            }

            // ENCABEZADO
            canvas.drawText("CAPITAL EXPRESS", pageWidth / 2f, yPos, titlePaint)
            yPos += 25f

            paint.textSize = 14f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = false
            canvas.drawText("Reporte de Cuotas Pendientes", pageWidth / 2f, yPos, paint)
            yPos += 20f

            // Fecha del reporte
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            paint.textSize = 10f
            paint.color = Color.GRAY
            canvas.drawText("Generado: ${dateFormat.format(Date())}", pageWidth / 2f, yPos, paint)
            yPos += 15f

            // Filtros aplicados
            if (filtroCliente.isNotEmpty() || filtroCobradorNombre.isNotEmpty()) {
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 9f
                yPos += 5f
                if (filtroCliente.isNotEmpty()) {
                    canvas.drawText("Filtro Cliente: $filtroCliente", margin, yPos, paint)
                    yPos += 12f
                }
                if (filtroCobradorNombre.isNotEmpty()) {
                    canvas.drawText("Filtro Cobrador: $filtroCobradorNombre", margin, yPos, paint)
                    yPos += 12f
                }
            }

            yPos += 10f
            paint.color = Color.LTGRAY
            canvas.drawRect(margin, yPos, pageWidth - margin, yPos + 1, paint)
            yPos += 20f

            // RESUMEN GENERAL
            var totalCuotasPendientes = 0
            var totalMontoPendiente = 0.0
            var totalClientesConPendientes = cuotasPorCliente.size

            cuotasPorCliente.forEach { (_, cuotas) ->
                totalCuotasPendientes += cuotas.size
                totalMontoPendiente += cuotas.sumOf { it.montoPendiente }
            }

            // Caja de resumen
            val boxPaint = Paint().apply {
                color = Color.parseColor("#E3F2FD")
                style = Paint.Style.FILL
            }
            val boxHeight = 60f
            canvas.drawRoundRect(
                RectF(margin, yPos, pageWidth - margin, yPos + boxHeight),
                8f, 8f, boxPaint
            )

            // Contenido del resumen
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.parseColor("#0061A7")
            paint.textSize = 11f
            paint.isFakeBoldText = true

            val col1X = margin + 15f
            val col2X = pageWidth / 2f + 15f
            var summaryY = yPos + 20f

            canvas.drawText("Clientes con pendientes:", col1X, summaryY, paint)
            paint.isFakeBoldText = false
            canvas.drawText(totalClientesConPendientes.toString(), col1X + 150f, summaryY, paint)

            canvas.drawText("Total cuotas pendientes:", col2X, summaryY, paint)
            canvas.drawText(totalCuotasPendientes.toString(), col2X + 140f, summaryY, paint)

            summaryY += 20f
            paint.isFakeBoldText = true
            canvas.drawText("Monto total pendiente:", col1X, summaryY, paint)
            paint.color = Color.parseColor("#D32F2F")
            paint.textSize = 13f
            canvas.drawText(formatearLempiras(totalMontoPendiente), col1X + 150f, summaryY, paint)

            yPos += boxHeight + 25f

            // Verificar espacio para continuar
            if (yPos > pageHeight - 100) {
                nuevaPagina()
            }

            // DETALLE POR CLIENTE
            cuotasPorCliente.forEachIndexed { index, (clienteNombre, cuotas) ->
                // Verificar si necesitamos nueva página para el cliente
                if (yPos > pageHeight - 150) {
                    nuevaPagina()
                }

                // Nombre del cliente
                headerPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("$clienteNombre", margin, yPos, headerPaint)
                yPos += 18f

                // Total de cuotas y monto del cliente
                val totalCuotasCliente = cuotas.size
                val totalMontoCliente = cuotas.sumOf { it.montoPendiente }

                smallPaint.color = Color.GRAY
                canvas.drawText(
                    "$totalCuotasCliente cuota(s) pendiente(s) - Total: ${formatearLempiras(totalMontoCliente)}",
                    margin,
                    yPos,
                    smallPaint
                )
                yPos += 15f

                // Línea separadora
                paint.color = Color.LTGRAY
                canvas.drawRect(margin, yPos, pageWidth - margin, yPos + 0.5f, paint)
                yPos += 10f

                // Encabezados de columnas
                smallPaint.color = Color.DKGRAY
                smallPaint.isFakeBoldText = true

                val colCuota = margin + 10f
                val colFecha = margin + 80f
                val colEstado = margin + 180f
                val colPagado = margin + 280f
                val colPendiente = margin + 380f

                canvas.drawText("Cuota", colCuota, yPos, smallPaint)
                canvas.drawText("Fecha Prog.", colFecha, yPos, smallPaint)
                canvas.drawText("Estado", colEstado, yPos, smallPaint)
                canvas.drawText("Pagado", colPagado, yPos, smallPaint)
                canvas.drawText("Pendiente", colPendiente, yPos, smallPaint)
                yPos += 12f

                smallPaint.isFakeBoldText = false

                // Línea debajo de encabezados
                paint.color = Color.LTGRAY
                canvas.drawRect(margin, yPos, pageWidth - margin, yPos + 0.5f, paint)
                yPos += 8f

                // Listar cuotas
                cuotas.forEach { cuota ->
                    // Verificar espacio antes de cada cuota
                    if (yPos > pageHeight - 80) {
                        nuevaPagina()

                        // Repetir encabezado en nueva página
                        headerPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText("$clienteNombre (continuación)", margin, yPos, headerPaint)
                        yPos += 25f
                    }

                    normalPaint.color = Color.BLACK
                    canvas.drawText("#${cuota.numeroCuota}", colCuota, yPos, normalPaint)
                    canvas.drawText(cuota.fechaProgramada, colFecha, yPos, normalPaint)

                    // Estado con color
                    val estadoTexto = when {
                        cuota.esProxima && !cuota.esVencida -> "PRÓXIMA"
                        cuota.esVencida && cuota.diasAtraso > 7 -> "VENCIDA (${cuota.diasAtraso}d)"
                        cuota.esVencida -> "Vencida (${cuota.diasAtraso}d)"
                        cuota.montoPagado > 0 -> "Parcial"
                        else -> "Pendiente"
                    }

                    val estadoColor = when {
                        cuota.esProxima && !cuota.esVencida -> Color.parseColor("#FBC02D")
                        cuota.esVencida && cuota.diasAtraso > 7 -> Color.parseColor("#D32F2F")
                        cuota.esVencida -> Color.parseColor("#FF9800")
                        cuota.montoPagado > 0 -> Color.parseColor("#4CAF50")
                        else -> Color.GRAY
                    }

                    smallPaint.color = estadoColor
                    smallPaint.isFakeBoldText = true
                    canvas.drawText(estadoTexto, colEstado, yPos, smallPaint)
                    smallPaint.isFakeBoldText = false

                    // Monto pagado
                    if (cuota.montoPagado > 0) {
                        normalPaint.color = Color.parseColor("#4CAF50")
                        canvas.drawText("L. ${String.format("%.2f", cuota.montoPagado)}", colPagado, yPos, normalPaint)
                    } else {
                        normalPaint.color = Color.GRAY
                        canvas.drawText("-", colPagado, yPos, normalPaint)
                    }

                    // Monto pendiente
                    totalPaint.color = Color.parseColor("#D32F2F")
                    canvas.drawText("L. ${String.format("%.2f", cuota.montoPendiente)}", colPendiente, yPos, totalPaint)

                    yPos += 15f

                    // Línea separadora suave entre cuotas
                    if (cuota != cuotas.last()) {
                        paint.color = Color.parseColor("#F0F0F0")
                        canvas.drawRect(margin + 5f, yPos, pageWidth - margin - 5f, yPos + 0.5f, paint)
                        yPos += 5f
                    }
                }

                yPos += 15f

                // Línea separadora entre clientes
                if (index < cuotasPorCliente.size - 1) {
                    paint.color = Color.LTGRAY
                    canvas.drawRect(margin, yPos, pageWidth - margin, yPos + 1f, paint)
                    yPos += 20f
                }
            }

            // PIE DE PÁGINA
            yPos = pageHeight - 30f
            paint.color = Color.LTGRAY
            canvas.drawRect(margin, yPos - 10, pageWidth - margin, yPos - 9, paint)

            smallPaint.color = Color.GRAY
            smallPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Página $pageNumber", pageWidth / 2f, yPos, smallPaint)

            smallPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Capital Express - Sistema de Préstamos", pageWidth - margin, yPos, smallPaint)

            pdfDocument.finishPage(page)

            // Guardar archivo
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Cuotas_Pendientes_$timestamp.pdf"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            FileOutputStream(file).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }

            pdfDocument.close()
            file

        } catch (e: Exception) {
            Log.e("GenerarPDFPendientes", "Error: ${e.message}", e)
            null
        }
    }

    fun generarHistorialPDF(
        context: Context,
        prestamos: List<Map<String, Any>>, // Lista de préstamos con sus datos
        filtros: Map<String, String>, // Filtros aplicados (fecha, estado, búsqueda)
        fechaExportacion: String
    ): File? {
        return try {
            val pdf = PdfDocument()

            // Colores corporativos profesionales
            val colorPrimario = Color.parseColor("#2196F3") // Azul principal
            val colorSecundario = Color.parseColor("#1976D2") // Azul oscuro
            val colorTexto = Color.parseColor("#212121") // Negro/gris oscuro
            val colorActivo = Color.parseColor("#4CAF50") // Verde
            val colorVencido = Color.parseColor("#FF5722") // Rojo
            val colorCompletado = Color.parseColor("#2196F3") // Azul
            val colorSaldado = Color.parseColor("#00BCD4") // Cyan
            val colorFondo = Color.parseColor("#F5F5F5") // Gris claro
            val colorLinea = Color.parseColor("#E0E0E0") // Gris línea

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
                textSize = 13f
                isFakeBoldText = true
            }

            val paintHeader = Paint().apply {
                color = Color.WHITE
                textSize = 10f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintText = Paint().apply {
                color = colorTexto
                textSize = 9f
            }

            val paintTextSmall = Paint().apply {
                color = Color.GRAY
                textSize = 8f
            }

            val paintTextCenter = Paint().apply {
                color = colorTexto
                textSize = 9f
                textAlign = Paint.Align.CENTER
            }

            val paintHeaderBg = Paint().apply {
                color = colorPrimario
            }

            val paintResumenBg = Paint().apply {
                color = colorFondo
            }

            val dec = DecimalFormat("#,##0.00")
            val decEntero = DecimalFormat("#,##0")

            val ancho = 595 // Tamaño A4 landscape
            val alto = 842
            val margen = 30f
            val espacioLinea = 18f

            var y = 50f
            var pageNum = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(ancho, alto, pageNum).create())
            var canvas = page.canvas

            fun nuevaPagina() {
                // Pie de página
                val piePagina = "Página $pageNum - Capital Express - Generado el $fechaExportacion"
                canvas.drawText(piePagina, ancho / 2f, alto - 20f, Paint().apply {
                    color = Color.GRAY
                    textSize = 8f
                    textAlign = Paint.Align.CENTER
                })

                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(ancho, alto, pageNum).create())
                canvas = page.canvas
                y = 50f
            }

            fun cargarLogo(): Bitmap? {
                return try {
                    val inputStream = context.assets.open("logo_capital.png")
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    null
                }
            }

            // === ENCABEZADO ===
            val logo = cargarLogo()
            if (logo != null) {
                val logoWidth = 70f
                val logoHeight = 50f
                val logoX = (ancho - logoWidth) / 2f
                val destRect = RectF(logoX, y, logoX + logoWidth, y + logoHeight)
                canvas.drawBitmap(logo, null, destRect, null)
                y += logoHeight + 20f
            } else {
                canvas.drawText("💼 CAPITAL EXPRESS", ancho / 2f, y, paintLogo)
                y += 30f
            }

            // Línea decorativa
            val paintLineDecorative = Paint().apply {
                color = colorSecundario
                strokeWidth = 3f
            }
            canvas.drawLine(ancho / 2f - 80f, y, ancho / 2f + 80f, y, paintLineDecorative)
            y += 25f

            // Título principal
            canvas.drawText("HISTORIAL DE PRÉSTAMOS", ancho / 2f, y, paintTitle)
            y += 30f

            // === INFORMACIÓN DE FILTROS APLICADOS ===
            val infoRect = RectF(margen, y - 5f, ancho - margen, y + 70f)
            canvas.drawRoundRect(infoRect, 8f, 8f, paintResumenBg)
            canvas.drawRoundRect(infoRect, 8f, 8f, Paint().apply {
                color = colorLinea
                style = Paint.Style.STROKE
                strokeWidth = 1f
            })

            y += 15f
            canvas.drawText(
                "REPORTE DE PRÉSTAMOS - RESUMEN EJECUTIVO",
                margen + 15f,
                y,
                paintSubtitle
            )
            y += espacioLinea

            // Mostrar filtros aplicados
            val filtroFecha = filtros["fecha"] ?: "Todos"
            val filtroEstado = filtros["estado"] ?: "Todos"
            val busqueda = filtros["busqueda"] ?: ""

            canvas.drawText("📅 Filtro de fecha: $filtroFecha", margen + 15f, y, paintText)
            if (filtros["fechaInicio"] != null && filtros["fechaFin"] != null) {
                canvas.drawText(
                    "(${filtros["fechaInicio"]} - ${filtros["fechaFin"]})",
                    margen + 200f,
                    y,
                    paintTextSmall
                )
            }
            y += espacioLinea

            canvas.drawText("📊 Filtro de estado: $filtroEstado", margen + 15f, y, paintText)
            y += espacioLinea

            if (busqueda.isNotEmpty()) {
                canvas.drawText("🔍 Búsqueda: $busqueda", margen + 15f, y, paintText)
                y += espacioLinea
            }

            canvas.drawText("📋 Total de préstamos: ${prestamos.size}", margen + 15f, y, paintText)
            y += 30f

            // === CÁLCULOS DE RESUMEN ===
            val totalPrestado = prestamos.sumOf { (it["monto"] as? Number)?.toDouble() ?: 0.0 }
            val totalPagado = prestamos.sumOf { (it["montoPagado"] as? Number)?.toDouble() ?: 0.0 }
            val totalPendiente = prestamos.sumOf { (it["saldo"] as? Number)?.toDouble() ?: 0.0 }
            val prestamosActivos =
                prestamos.count { (it["estado"] as? String)?.lowercase() == "activo" }
            val prestamosVencidos =
                prestamos.count { (it["estado"] as? String)?.lowercase() == "vencido" }
            val prestamosCompletados = prestamos.count {
                (it["estado"] as? String)?.lowercase() in listOf(
                    "completado",
                    "saldado"
                )
            }

            // === RESUMEN FINANCIERO ===
            val resumenRect = RectF(margen, y - 5f, ancho - margen, y + 85f)
            canvas.drawRoundRect(resumenRect, 8f, 8f, Paint().apply { color = colorPrimario })

            y += 15f
            canvas.drawText("RESUMEN FINANCIERO", ancho / 2f, y, Paint().apply {
                color = Color.WHITE
                textSize = 14f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            })
            y += 25f

            // Columnas del resumen
            val col1 = margen + 80f
            val col2 = ancho / 2f
            val col3 = ancho - margen - 80f

            val paintWhite = Paint().apply {
                color = Color.WHITE
                textSize = 10f
                textAlign = Paint.Align.CENTER
            }
            val paintWhiteBold = Paint().apply {
                color = Color.WHITE
                textSize = 12f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            canvas.drawText("💰 PRESTADO", col1, y, paintWhite)
            canvas.drawText("💳 PAGADO", col2, y, paintWhite)
            canvas.drawText("⏰ PENDIENTE", col3, y, paintWhite)
            y += espacioLinea

            canvas.drawText("L.${decEntero.format(totalPrestado)}", col1, y, paintWhiteBold)
            canvas.drawText("L.${decEntero.format(totalPagado)}", col2, y, paintWhiteBold)
            canvas.drawText("L.${decEntero.format(totalPendiente)}", col3, y, paintWhiteBold)
            y += 35f

            // === ESTADÍSTICAS POR ESTADO ===
            y += 10f
            canvas.drawText("📊 ESTADÍSTICAS POR ESTADO", margen + 15f, y, paintSubtitle)
            y += espacioLinea

            val statsRect = RectF(margen, y - 5f, ancho - margen, y + 25f)
            canvas.drawRect(statsRect, paintResumenBg)

            y += 15f
            canvas.drawText("🟢 Activos: $prestamosActivos", margen + 50f, y, paintText)
            canvas.drawText("🔴 Vencidos: $prestamosVencidos", margen + 200f, y, paintText)
            canvas.drawText("🔵 Completados: $prestamosCompletados", margen + 350f, y, paintText)
            y += 30f

            // === TABLA DE PRÉSTAMOS ===
            if (y > alto - 150) nuevaPagina()

            canvas.drawText("DETALLE DE PRÉSTAMOS", margen + 15f, y, paintSubtitle)
            y += 20f

            // Cabecera de tabla
            val headerHeight = 25f
            val headerRect = RectF(margen, y, ancho - margen, y + headerHeight)
            canvas.drawRoundRect(headerRect, 5f, 5f, paintHeaderBg)

            // Posiciones de columnas
            val colNro = margen + 25f
            val colCliente = margen + 80f
            val colPrestamo = margen + 210f
            val colPagado = margen + 300f
            val colSaldo = margen + 390f
            val colEstado = margen + 480f

            y += 18f
            canvas.drawText("Nº", colNro, y, paintHeader)
            canvas.drawText("CLIENTE", colCliente, y, paintHeader)
            canvas.drawText("PRÉSTAMO", colPrestamo, y, paintHeader)
            canvas.drawText("PAGADO", colPagado, y, paintHeader)
            canvas.drawText("SALDO", colSaldo, y, paintHeader)
            canvas.drawText("ESTADO", colEstado, y, paintHeader)
            y += 15f

            // === FILAS DE DATOS ===
            var filaImpar = true
            for ((index, prestamo) in prestamos.withIndex()) {
                if (y > alto - 60) nuevaPagina()

                // Fondo alternado
                if (filaImpar) {
                    val filaRect = RectF(margen, y - 10f, ancho - margen, y + 8f)
                    canvas.drawRect(filaRect, Paint().apply { color = Color.parseColor("#FAFAFA") })
                }

                // Extraer datos
                val numero = (index + 1).toString()
                val cliente = (prestamo["cliente"] as? String ?: "").take(25)
                val monto = (prestamo["monto"] as? Number)?.toDouble() ?: 0.0
                val montoPagado = (prestamo["montoPagado"] as? Number)?.toDouble() ?: 0.0
                val saldo = (prestamo["saldo"] as? Number)?.toDouble() ?: 0.0
                val estado = (prestamo["estado"] as? String ?: "activo").uppercase()

                // Determinar color del estado
                val paintEstado = Paint().apply {
                    color = when (estado.lowercase()) {
                        "activo" -> colorActivo
                        "vencido" -> colorVencido
                        "completado" -> colorCompletado
                        "saldado" -> colorSaldado
                        else -> Color.GRAY
                    }
                    textSize = 9f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }

                // Dibujar datos
                canvas.drawText(numero, colNro, y, paintTextCenter)
                canvas.drawText(cliente, margen + 55f, y, paintText)
                canvas.drawText("L.${decEntero.format(monto)}", colPrestamo, y, paintText)
                canvas.drawText("L.${decEntero.format(montoPagado)}", colPagado, y, paintText)
                canvas.drawText("L.${decEntero.format(saldo)}", colSaldo, y,
                    Paint().apply {
                        color = if (saldo > 0) colorVencido else colorActivo
                        textSize = 9f
                        isFakeBoldText = true
                    })
                canvas.drawText(estado, colEstado, y, paintEstado)

                y += espacioLinea
                filaImpar = !filaImpar
            }

            // === PIE DE PÁGINA FINAL ===
            if (prestamos.isEmpty()) {
                y += 40f
                canvas.drawText("📋 No se encontraron préstamos con los filtros aplicados",
                    ancho / 2f, y, Paint().apply {
                        color = Color.GRAY
                        textSize = 12f
                        textAlign = Paint.Align.CENTER
                    })
            }

            val piePagina = "Página $pageNum - Capital Express - Generado el $fechaExportacion"
            canvas.drawText(piePagina, ancho / 2f, alto - 20f, Paint().apply {
                color = Color.GRAY
                textSize = 8f
                textAlign = Paint.Align.CENTER
            })

            pdf.finishPage(page)

            // Guardar archivo
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "historial_prestamos_$timestamp.pdf"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            pdf.writeTo(FileOutputStream(file))
            pdf.close()

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generarCuotasPDF(
        context: Context,
        cliente: String,
        prestamoId: String,
        cuotas: List<Map<String, Any>>, // Mantiene el formato que usas actualmente
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

            val paintParcial = Paint().apply {
                color = Color.parseColor("#FF9800") // Naranja
                textSize = 10f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val paintHeaderBg = Paint().apply {
                color = colorPrimario
            }

            val paintTotalBg = Paint().apply {
                color = colorFondo
            }

            val ancho = 420
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
                    null
                }
            }

            // === ENCABEZADO CON LOGO ===
            val logo = cargarLogo()
            if (logo != null) {
                val logoWidth = 60f
                val logoHeight = 40f
                val logoX = (ancho - logoWidth) / 2f
                val destRect = RectF(logoX, y, logoX + logoWidth, y + logoHeight)
                canvas.drawBitmap(logo, null, destRect, null)
                y += logoHeight + 15f
            } else {
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
            canvas.drawText("ID PRÉSTAMO: $prestamoId", margen + 10f, y, paintText)
            y += espacioLinea
            canvas.drawText("FECHA DE EXPORTACIÓN: $fechaExportacion", margen + 10f, y, paintText)
            y += 25f

            // === CABECERA DE TABLA ===
            val headerHeight = 25f
            val headerRect = RectF(margen, y, ancho - margen, y + headerHeight)
            canvas.drawRoundRect(headerRect, 5f, 5f, paintHeaderBg)

            // Posiciones de columnas optimizadas
            val colPago = margen + 25f
            val colFecha = margen + 80f
            val colMonto = margen + 160f
            val colPagado = margen + 230f
            val colEstado = margen + 310f

            y += 18f
            canvas.drawText("CUOTA", colPago, y, paintHeader)
            canvas.drawText("FECHA", colFecha, y, paintHeader)
            canvas.drawText("MONTO", colMonto, y, paintHeader)
            canvas.drawText("PAGADO", colPagado, y, paintHeader)
            canvas.drawText("ESTADO", colEstado, y, paintHeader)
            y += 15f

            val dec = DecimalFormat("#,##0.00")

            // === FILAS DE CUOTAS ===
            var filaImpar = true
            for (cuotaMap in cuotas) {
                if (y > alto - 80) nuevaPagina()

                // Fondo alternado
                if (filaImpar) {
                    val filaRect = RectF(margen, y - 8f, ancho - margen, y + 8f)
                    canvas.drawRect(filaRect, Paint().apply { color = Color.parseColor("#F9FAFB") })
                }

                // Extraer datos del Map
                val numero = (cuotaMap["numero"] as? Number)?.toInt() ?: 0
                val fecha = cuotaMap["fecha"] as? String ?: ""
                val total = (cuotaMap["total"] as? Number)?.toDouble() ?: 0.0
                val montoPagado = (cuotaMap["montoPagado"] as? Number)?.toDouble() ?: 0.0
                val pagado = cuotaMap["pagado"] as? Boolean ?: false

                // Determinar estado basado en montoPagado y total
                val estado = when {
                    montoPagado >= total - 0.01 -> "COMPLETA"
                    montoPagado > 0 -> "PARCIAL"
                    else -> "PENDIENTE"
                }

                val paintEstado = when (estado) {
                    "COMPLETA" -> paintPagado
                    "PARCIAL" -> paintParcial
                    else -> paintPendiente
                }

                // Datos de la fila
                canvas.drawText("$numero", colPago, y, paintTextCenter)
                canvas.drawText(fecha, colFecha, y, paintText)
                canvas.drawText("L.${dec.format(total)}", colMonto, y, paintText)
                canvas.drawText("L.${dec.format(montoPagado)}", colPagado, y, paintText)
                canvas.drawText(estado, colEstado, y, paintEstado)

                y += espacioLinea
                filaImpar = !filaImpar
            }

            // === RESUMEN FINAL ===
            if (y > alto - 120) nuevaPagina()

            y += 20f
            val resumenRect = RectF(margen, y - 5f, ancho - margen, y + 80f)
            canvas.drawRoundRect(resumenRect, 8f, 8f, paintTotalBg)
            canvas.drawRoundRect(resumenRect, 8f, 8f, Paint().apply {
                color = colorLinea
                style = Paint.Style.STROKE
                strokeWidth = 1f
            })

            y += 15f
            canvas.drawText("RESUMEN DEL PRÉSTAMO", margen + 10f, y, paintSubtitle)
            y += espacioLinea
            canvas.drawText(
                "Total Capital: L.${dec.format(totalCapital)}",
                margen + 10f,
                y,
                paintText
            )
            y += espacioLinea
            canvas.drawText(
                "Total Intereses: L.${dec.format(totalInteres)}",
                margen + 10f,
                y,
                paintText
            )
            if (mora > 0) {
                y += espacioLinea
                canvas.drawText("Mora: L.${dec.format(mora)}", margen + 10f, y, paintText)
            }
            y += espacioLinea

            val totalGeneral = totalCapital + totalInteres + mora
            val totalPagado = cuotas.sumOf { (it["montoPagado"] as? Number)?.toDouble() ?: 0.0 }
            val saldoPendiente = (totalGeneral - totalPagado).coerceAtLeast(0.0)

            canvas.drawText(
                "TOTAL PRÉSTAMO: L.${dec.format(totalGeneral)}",
                margen + 10f,
                y,
                paintSubtitle
            )
            y += espacioLinea
            canvas.drawText(
                "TOTAL PAGADO: L.${dec.format(totalPagado)}",
                margen + 10f,
                y,
                paintText
            )
            y += espacioLinea
            canvas.drawText("SALDO PENDIENTE: L.${dec.format(saldoPendiente)}", margen + 10f, y,
                Paint().apply {
                    color = if (saldoPendiente > 0) Color.RED else colorExito
                    textSize = 12f
                    isFakeBoldText = true
                })

            // Pie de página final
            val piePagina = "Página $pageNum - Generado el $fechaExportacion - Sistema de Cascada"
            canvas.drawText(piePagina, ancho / 2f, alto - 15f, Paint().apply {
                color = Color.GRAY
                textSize = 8f
                textAlign = Paint.Align.CENTER
            })

            pdf.finishPage(page)

            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "cuotas_${prestamoId}.pdf"
            )
            pdf.writeTo(FileOutputStream(file))
            pdf.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
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
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Adjunto encontrarás el recibo de pago generado por Capital Express."
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Compartir recibo PDF")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(chooser)
            Log.d("ReciboPDF", "✅ PDF compartido exitosamente: ${file.name}")

        } catch (e: Exception) {
            Log.e("ReciboPDF", "❌ Error al compartir PDF", e)
            Toast.makeText(context, "Error al compartir PDF: ${e.message}", Toast.LENGTH_LONG)
                .show()
        }
    }
}

    data class PagoSimple(
        val fecha: String,
        val monto: Double
    )




