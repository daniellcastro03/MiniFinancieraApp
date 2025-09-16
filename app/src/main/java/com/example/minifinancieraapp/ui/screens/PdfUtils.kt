import android.content.Context
import android.os.Environment
import android.widget.Toast
import com.itextpdf.text.*
import com.itextpdf.text.pdf.PdfWriter
import java.io.File
import java.io.FileOutputStream

fun generarReciboPDF(
    context: Context,
    cliente: String,
    prestamoId: String,
    fecha: String,
    montoPagado: String,
    saldoAnterior: Double
) {
    try {
        val nuevoSaldo = saldoAnterior - (montoPagado.toDoubleOrNull() ?: 0.0)
        val doc = Document()
        val path = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val file = File(path, "Recibo_$cliente.pdf")

        PdfWriter.getInstance(doc, FileOutputStream(file))
        doc.open()

        val titleFont = Font(Font.FontFamily.HELVETICA, 18f, Font.BOLD)
        val textFont = Font(Font.FontFamily.HELVETICA, 12f)

        doc.add(Paragraph("CAPITAL EXPRESS", titleFont))
        doc.add(Paragraph(" ", textFont))
        doc.add(Paragraph("RECIBO DE PAGO", textFont))
        doc.add(Paragraph("Cliente: $cliente", textFont))
        doc.add(Paragraph("Préstamo ID: $prestamoId", textFont))
        doc.add(Paragraph("Fecha: $fecha", textFont))
        doc.add(Paragraph("Monto abonado: L. $montoPagado", textFont))
        doc.add(Paragraph("Saldo anterior: L. %.2f".format(saldoAnterior), textFont))
        doc.add(Paragraph("Nuevo saldo: L. %.2f".format(nuevoSaldo), textFont))
        doc.add(Paragraph("--------------------------", textFont))
        doc.add(Paragraph("Gracias por su pago.", textFont))

        doc.close()

        Toast.makeText(context, "Recibo PDF generado en: ${file.path}", Toast.LENGTH_LONG).show()

    } catch (e: Exception) {
        Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
