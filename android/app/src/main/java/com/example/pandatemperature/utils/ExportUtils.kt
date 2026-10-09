package com.example.pandatemperature.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.example.pandatemperature.data.model.TemperatureRecord
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object ExportUtils {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    fun exportToCsv(context: Context, records: List<TemperatureRecord>): File {
        val fileName = "PandaTemp_Export_${fileDateFormat.format(Date())}.csv"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        
        FileWriter(file).use { writer ->
            // Header
            writer.append("Time,Temperature(°C),Humidity(%),Pressure(hPa),Latitude,Longitude\n")
            
            // Data
            for (record in records) {
                val time = dateFormat.format(Date(record.timestamp * 1000))
                val temp = String.format("%.2f", record.temperature)
                val hum = String.format("%.2f", record.humidity)
                val press = record.pressure?.let { String.format("%.2f", it) } ?: ""
                val lat = record.latitude?.toString() ?: ""
                val lon = record.longitude?.toString() ?: ""
                
                writer.append("$time,$temp,$hum,$press,$lat,$lon\n")
            }
        }
        return file
    }

    fun exportToPdf(context: Context, records: List<TemperatureRecord>): File {
        val fileName = "PandaTemp_Report_${fileDateFormat.format(Date())}.pdf"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size in points (approx)
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()
        
        // Title
        paint.textSize = 24f
        paint.isFakeBoldText = true
        paint.color = Color.BLACK
        canvas.drawText("Sensor Data Report", 50f, 50f, paint)
        
        // Summary
        paint.textSize = 14f
        paint.isFakeBoldText = false
        var yPos = 90f
        val lineHeight = 20f
        
        if (records.isNotEmpty()) {
            val startTime = dateFormat.format(Date(records.first().timestamp * 1000))
            val endTime = dateFormat.format(Date(records.last().timestamp * 1000))
            val count = records.size
            
            // Calculate stats
            var maxTemp = Float.MIN_VALUE
            var minTemp = Float.MAX_VALUE
            var sumTemp = 0.0
            var maxHum = Float.MIN_VALUE
            var minHum = Float.MAX_VALUE
            var sumHum = 0.0
            var hasPressure = false
            var maxPress = Float.MIN_VALUE
            var minPress = Float.MAX_VALUE
            var sumPress = 0.0
            var pressCount = 0
            
            for (record in records) {
                maxTemp = max(maxTemp, record.temperature)
                minTemp = min(minTemp, record.temperature)
                sumTemp += record.temperature
                
                maxHum = max(maxHum, record.humidity)
                minHum = min(minHum, record.humidity)
                sumHum += record.humidity
                
                if (record.pressure != null) {
                    hasPressure = true
                    maxPress = max(maxPress, record.pressure)
                    minPress = min(minPress, record.pressure)
                    sumPress += record.pressure
                    pressCount++
                }
            }
            
            val avgTemp = sumTemp / count
            val avgHum = sumHum / count
            
            canvas.drawText("Summary:", 50f, yPos, paint)
            yPos += lineHeight
            canvas.drawText("Time Range: $startTime - $endTime", 70f, yPos, paint)
            yPos += lineHeight
            canvas.drawText("Total Records: $count", 70f, yPos, paint)
            yPos += lineHeight * 2
            
            canvas.drawText("Temperature:", 50f, yPos, paint)
            yPos += lineHeight
            canvas.drawText("Max: ${String.format("%.2f", maxTemp)}°C, Min: ${String.format("%.2f", minTemp)}°C, Avg: ${String.format("%.2f", avgTemp)}°C", 70f, yPos, paint)
            yPos += lineHeight * 2
            
            canvas.drawText("Humidity:", 50f, yPos, paint)
            yPos += lineHeight
            canvas.drawText("Max: ${String.format("%.2f", maxHum)}%, Min: ${String.format("%.2f", minHum)}%, Avg: ${String.format("%.2f", avgHum)}%", 70f, yPos, paint)
            yPos += lineHeight * 2
            
            if (hasPressure) {
                val avgPress = sumPress / pressCount
                canvas.drawText("Pressure:", 50f, yPos, paint)
                yPos += lineHeight
                canvas.drawText("Max: ${String.format("%.2f", maxPress)}hPa, Min: ${String.format("%.2f", minPress)}hPa, Avg: ${String.format("%.2f", avgPress)}hPa", 70f, yPos, paint)
                yPos += lineHeight * 2
            }
        } else {
            canvas.drawText("No data available for the selected period.", 50f, yPos, paint)
            yPos += lineHeight * 2
        }
        
        // Data Table Header
        paint.textSize = 12f
        paint.isFakeBoldText = true
        val col1 = 50f
        val col2 = 200f
        val col3 = 300f
        val col4 = 400f
        val col5 = 500f
        
        canvas.drawText("Time", col1, yPos, paint)
        canvas.drawText("Temp(°C)", col2, yPos, paint)
        canvas.drawText("Hum(%)", col3, yPos, paint)
        canvas.drawText("Press(hPa)", col4, yPos, paint)
        yPos += lineHeight
        
        paint.isFakeBoldText = false
        
        // Draw first 30 records to avoid overflow (simple implementation for now)
        // In a real app, we would handle pagination
        val limit = 30
        var drawnCount = 0
        
        for (record in records) {
            if (drawnCount >= limit) {
                canvas.drawText("... and ${records.size - limit} more records (truncated)", col1, yPos, paint)
                break
            }
            
            val time = dateFormat.format(Date(record.timestamp * 1000))
            val temp = String.format("%.2f", record.temperature)
            val hum = String.format("%.2f", record.humidity)
            val press = record.pressure?.let { String.format("%.2f", it) } ?: "-"
            
            canvas.drawText(time, col1, yPos, paint)
            canvas.drawText(temp, col2, yPos, paint)
            canvas.drawText(hum, col3, yPos, paint)
            canvas.drawText(press, col4, yPos, paint)
            
            yPos += lineHeight
            drawnCount++
        }
        
        pdfDocument.finishPage(page)
        
        FileOutputStream(file).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        
        return file
    }
}
