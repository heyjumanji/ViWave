package app.vitune.android.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object CsvParser {
    data class Track(val title: String, val artist: String, val album: String = "", val duration: String = "")

    suspend fun parse(context: Context, uri: Uri): List<Track> {
        val tracks = mutableListOf<Track>()
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var isHeader = true
                reader.forEachLine { line ->
                    if (isHeader) {
                        isHeader = false
                        return@forEachLine
                    }
                    val parts = parseCsvLine(line)
                    if (parts.size >= 2) {
                        val title = parts[0].trim()
                        val artist = parts[1].trim()
                        val album = if (parts.size > 2) parts[2].trim() else ""
                        val duration = if (parts.size > 3) parts[3].trim() else ""
                        if (title.isNotBlank() && artist.isNotBlank()) {
                            tracks.add(Track(title, artist, album, duration))
                        }
                    }
                }
            }
        }
        return tracks
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var inQuotes = false
        val currentPart = StringBuilder()

        for (char in line) {
            if (char == '\"') {
                inQuotes = !inQuotes
            } else if (char == ',' && !inQuotes) {
                result.add(currentPart.toString())
                currentPart.clear()
            } else {
                currentPart.append(char)
            }
        }
        result.add(currentPart.toString())
        return result
    }
}
