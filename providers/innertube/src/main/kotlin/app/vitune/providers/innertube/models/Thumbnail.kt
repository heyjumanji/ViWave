package app.vitune.providers.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnail(
    val url: String,
    val height: Int?,
    val width: Int?
) {
    fun size(size: Int): String {
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return if (url.contains("=")) {
                val (base, options) = url.split("=", limit = 2)
                val newOptions = options
                    .replace(Regex("w\\d+"), "w$size")
                    .replace(Regex("h\\d+"), "h$size")
                    .replace(Regex("s\\d+"), "s$size")
                "$base=$newOptions"
            } else {
                "$url=w$size-h$size"
            }
        }
        return url
    }
}
