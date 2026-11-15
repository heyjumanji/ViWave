package app.vitune.core.data.utils

inline val String.version
    get() = Version(
        trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .split(".")
            .mapNotNull { part ->
                part.filter { it.isDigit() }.toIntOrNull()
            }
    )

@JvmInline
value class Version(private val parts: List<Int>) {
    val major get() = parts.firstOrNull() ?: 0
    val minor get() = parts.getOrNull(1) ?: 0
    val patch get() = parts.getOrNull(2) ?: 0
    val build get() = parts.getOrNull(3) ?: 0

    val isEmpty get() = parts.isEmpty()

    companion object {
        private val comparator = compareBy<Version> { it.major } then
            compareBy { it.minor } then
            compareBy { it.patch } then
            compareBy { it.build }
    }

    operator fun compareTo(other: Version) = comparator.compare(this, other)
}
