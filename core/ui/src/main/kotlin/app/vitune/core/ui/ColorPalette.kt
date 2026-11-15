package app.vitune.core.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Parcel
import android.os.Parcelable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith

typealias ParcelableColor = @WriteWith<ColorParceler> Color
typealias ParcelableDp = @WriteWith<DpParceler> Dp

@Parcelize
@Immutable
data class ColorPalette(
    val background0: ParcelableColor,
    val background1: ParcelableColor,
    val background2: ParcelableColor,
    val accent: ParcelableColor,
    val onAccent: ParcelableColor,
    val red: ParcelableColor = Color(0xffbf4040),
    val blue: ParcelableColor = Color(0xff4472cf),
    val yellow: ParcelableColor = Color(0xfffff176),
    val text: ParcelableColor,
    val textSecondary: ParcelableColor,
    val textDisabled: ParcelableColor,
    val isDefault: Boolean,
    val isDark: Boolean
) : Parcelable

private val defaultAccentColor = Color(0xff3e44ce).hsl

val defaultLightPalette = ColorPalette(
    background0 = Color(0xfffdfdfe),
    background1 = Color(0xfff8f8fc),
    background2 = Color(0xffeaeaf5),
    text = Color(0xff212121),
    textSecondary = Color(0xff656566),
    textDisabled = Color(0xff9d9d9d),
    accent = defaultAccentColor.color,
    onAccent = Color.White,
    isDefault = true,
    isDark = false
)

val defaultDarkPalette = ColorPalette(
    background0 = Color(0xff16171d),
    background1 = Color(0xff1f2029),
    background2 = Color(0xff2b2d3b),
    text = Color(0xffe1e1e2),
    textSecondary = Color(0xffa3a4a6),
    textDisabled = Color(0xff6f6f73),
    accent = defaultAccentColor.color,
    onAccent = Color.White,
    isDefault = true,
    isDark = true
)

private fun lightColorPalette(accent: Hsl) = lightColorPalette(
    hue = accent.hue,
    saturation = accent.saturation
)

private fun lightColorPalette(hue: Float, saturation: Float) = ColorPalette(
    background0 = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.1f),
        lightness = 0.925f
    ),
    background1 = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.3f),
        lightness = 0.90f
    ),
    background2 = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.4f),
        lightness = 0.85f
    ),
    text = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.02f),
        lightness = 0.12f
    ),
    textSecondary = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.1f),
        lightness = 0.40f
    ),
    textDisabled = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.2f),
        lightness = 0.65f
    ),
    accent = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.5f),
        lightness = 0.5f
    ),
    onAccent = Color.White,
    isDefault = false,
    isDark = false
)

private fun darkColorPalette(accent: Hsl, darkness: Darkness) = darkColorPalette(
    hue = accent.hue,
    saturation = accent.saturation,
    darkness = darkness
)

private fun darkColorPalette(
    hue: Float,
    saturation: Float,
    darkness: Darkness
) = ColorPalette(
    background0 = if (darkness == Darkness.Normal) Color.hsl(
        hue = hue,
        saturation = if (saturation < 0.12f) saturation else saturation.coerceIn(0.18f, 0.38f),
        lightness = 0.11f
    ) else Color.Black,
    background1 = if (darkness == Darkness.Normal) Color.hsl(
        hue = hue,
        saturation = if (saturation < 0.12f) saturation else saturation.coerceIn(0.24f, 0.48f),
        lightness = 0.16f
    ) else Color.Black,
    background2 = if (darkness == Darkness.Normal) Color.hsl(
        hue = hue,
        saturation = if (saturation < 0.12f) saturation else saturation.coerceIn(0.28f, 0.56f),
        lightness = 0.23f
    ) else Color.Black,
    text = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.06f),
        lightness = 0.92f
    ),
    textSecondary = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.16f),
        lightness = 0.68f
    ),
    textDisabled = Color.hsl(
        hue = hue,
        saturation = saturation.coerceAtMost(0.24f),
        lightness = 0.42f
    ),
    accent = Color.hsl(
        hue = hue,
        saturation = if (saturation < 0.12f) saturation else saturation.coerceIn(0.45f, if (darkness == Darkness.AMOLED) 0.58f else 0.74f),
        lightness = 0.54f
    ),
    onAccent = Color.White,
    isDefault = false,
    isDark = true
)

private fun Bitmap.toSoftwareBitmap(): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
        runCatching { copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: this
    } else {
        this
    }
}

fun accentColorOf(
    source: ColorSource,
    isDark: Boolean,
    materialAccentColor: Color?,
    sampleBitmap: Bitmap?
) = when (source) {
    ColorSource.Default -> defaultAccentColor
    ColorSource.Dynamic -> sampleBitmap?.let { dynamicAccentColorOf(it, isDark) }
        ?: materialAccentColor?.hsl
        ?: defaultAccentColor

    ColorSource.MaterialYou -> materialAccentColor?.hsl ?: defaultAccentColor
}

private val dynamicAccentCache = android.util.LruCache<Int, Hsl>(24)

fun dynamicAccentColorOf(
    bitmap: Bitmap,
    isDark: Boolean
): Hsl? {
    val cacheKey = (System.identityHashCode(bitmap) * 31 + bitmap.generationId) * 31 + isDark.hashCode()
    dynamicAccentCache.get(cacheKey)?.let { return it }

    val softwareBitmap = runCatching { bitmap.toSoftwareBitmap() }.getOrNull() ?: return null
    val palette = runCatching {
        Palette
            .from(softwareBitmap)
            .maximumColorCount(32)
            .resizeBitmapArea(112 * 112)
            .clearFilters()
            .generate()
    }.getOrNull() ?: return null

    val swatches = palette.swatches
    if (swatches.isEmpty()) return null

    val totalPopulation = swatches.sumOf { it.population }.coerceAtLeast(1).toFloat()

    fun hueDistance(h1: Float, h2: Float): Float {
        val diff = kotlin.math.abs(h1 - h2) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    fun swatchChroma(hsl: FloatArray): Float {
        val s = hsl[1].coerceIn(0f, 1f)
        val l = hsl[2].coerceIn(0f, 1f)
        return s * (1f - kotlin.math.abs(2f * l - 1f))
    }

    // Include deep dark chromatic colors (e.g., deep ocean blue/teal with lightness 0.04..0.15)
    // while excluding near-black/near-white quantization noise where true RGB chroma < 0.035.
    val chromaticSwatches = swatches.filter { swatch ->
        val hsl = swatch.hsl
        val s = hsl[1]
        val l = hsl[2]
        val chroma = swatchChroma(hsl)
        s >= 0.10f && chroma >= 0.035f && l in 0.035f..0.95f
    }

    val totalChromaticPop = chromaticSwatches.sumOf { it.population }.toFloat()

    // Pure B&W / monochrome artwork: keep low saturation instead of amplifying compression noise
    if (chromaticSwatches.isEmpty() || (totalChromaticPop / totalPopulation) < 0.02f) {
        val domHsl = (palette.dominantSwatch ?: swatches.maxByOrNull { it.population })?.hsl ?: return null
        return FloatArray(3).apply {
            this[0] = domHsl[0]
            this[1] = domHsl[1].coerceAtMost(0.08f)
            this[2] = domHsl[2].coerceIn(if (isDark) 0.44f else 0.34f, if (isDark) 0.68f else 0.56f)
        }.hsl.also { computed ->
            dynamicAccentCache.put(cacheKey, computed)
        }
    }

    // Score each chromatic swatch by its 360-degree hue-cluster population share (+/- 28 degrees)
    // so a dominant 85% ocean-blue/teal/purple background is never overridden by a 2% skin-tone swatch.
    val winningSwatch = chromaticSwatches.maxByOrNull { candidate ->
        val cHsl = candidate.hsl
        val cHue = cHsl[0]
        val cSat = cHsl[1]
        val cLight = cHsl[2]
        val cChroma = swatchChroma(cHsl)

        var clusterWeightedPop = 0f
        for (other in chromaticSwatches) {
            val oHsl = other.hsl
            val dist = hueDistance(cHue, oHsl[0])
            if (dist <= 28f) {
                val proximityWeight = 1f - (dist / 36f)
                val otherChromaFactor = (swatchChroma(oHsl) / 0.18f).coerceIn(0.35f, 1.35f)
                clusterWeightedPop += other.population.toFloat() * proximityWeight * otherChromaFactor
            }
        }

        val clusterShare = clusterWeightedPop / totalPopulation
        val selfShare = candidate.population.toFloat() / totalPopulation
        val readabilityBonus = 1f - 0.30f * kotlin.math.abs(cLight - 0.42f)
        val chromaScore = (cChroma * 1.4f + cSat * 0.6f).coerceIn(0.18f, 1.5f)

        (clusterShare * 3.4f + selfShare * 1.2f) * chromaScore * readabilityBonus
    } ?: palette.dominantSwatch ?: return null

    val winHsl = winningSwatch.hsl
    val winHue = winHsl[0]
    var sinSum = 0.0
    var cosSum = 0.0
    var satSum = 0f
    var weightSum = 0f

    for (sw in chromaticSwatches) {
        val shsl = sw.hsl
        val dist = hueDistance(winHue, shsl[0])
        if (dist <= 24f) {
            val w = sw.population.toFloat() * swatchChroma(shsl).coerceAtLeast(0.04f)
            val rad = Math.toRadians(shsl[0].toDouble())
            sinSum += kotlin.math.sin(rad) * w
            cosSum += kotlin.math.cos(rad) * w
            satSum += shsl[1] * w
            weightSum += w
        }
    }

    val finalHue = if (weightSum > 0f) {
        ((Math.toDegrees(kotlin.math.atan2(sinSum, cosSum)).toFloat() + 360f) % 360f)
    } else {
        winHue
    }
    val avgSat = if (weightSum > 0f) (satSum / weightSum) else winHsl[1]
    val effectiveSat = maxOf(winHsl[1], avgSat)

    val boostedSaturation = when {
        effectiveSat >= 0.52f -> effectiveSat.coerceAtMost(0.82f)
        effectiveSat >= 0.10f -> (effectiveSat * 1.55f).coerceIn(0.46f, 0.78f)
        else -> effectiveSat.coerceAtMost(0.12f)
    }

    return FloatArray(3).apply {
        this[0] = finalHue
        this[1] = boostedSaturation
        this[2] = winHsl[2].coerceIn(if (isDark) 0.44f else 0.34f, if (isDark) 0.70f else 0.58f)
    }.hsl.also { computed ->
        dynamicAccentCache.put(cacheKey, computed)
    }
}

fun ColorPalette.amoled() = if (isDark) {
    val (hue, saturation) = accent.hsl

    copy(
        background0 = Color.hsl(
            hue = hue,
            saturation = saturation.coerceAtMost(0.1f),
            lightness = 0.10f
        ),
        background1 = Color.hsl(
            hue = hue,
            saturation = saturation.coerceAtMost(0.3f),
            lightness = 0.15f
        ),
        background2 = Color.hsl(
            hue = hue,
            saturation = saturation.coerceAtMost(0.4f),
            lightness = 0.2f
        )
    )
} else this

fun colorPaletteOf(
    source: ColorSource,
    darkness: Darkness,
    isDark: Boolean,
    materialAccentColor: Color?,
    sampleBitmap: Bitmap?
): ColorPalette {
    if (source == ColorSource.Default) {
        return when {
            !isDark -> defaultLightPalette
            darkness == Darkness.PureBlack -> defaultDarkPalette.copy(
                background0 = Color.Black,
                background1 = Color.Black,
                background2 = Color.Black
            )
            darkness == Darkness.AMOLED -> defaultDarkPalette.amoled().copy(
                background0 = Color.Black,
                background1 = Color.Black
            )
            else -> defaultDarkPalette
        }
    }

    val accentColor = accentColorOf(
        source = source,
        isDark = isDark,
        materialAccentColor = materialAccentColor,
        sampleBitmap = sampleBitmap
    )

    return (if (isDark) darkColorPalette(accentColor, darkness) else lightColorPalette(accentColor))
        .copy(isDefault = accentColor == defaultAccentColor)
}

inline val ColorPalette.isPureBlack get() = background0 == Color.Black
inline val ColorPalette.collapsedPlayerProgressBar
    get() = if (isPureBlack) defaultDarkPalette.background0 else background2
inline val ColorPalette.favoritesIcon get() = if (isDefault) red else accent
inline val ColorPalette.shimmer get() = if (isDefault) Color(0xff838383) else accent
inline val ColorPalette.surface get() = if (isPureBlack) Color(0xff272727) else background2

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.overlay get() = Color.Black.copy(alpha = 0.75f)

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.onOverlay get() = defaultDarkPalette.text

@Suppress("UnusedReceiverParameter")
inline val ColorPalette.onOverlayShimmer get() = defaultDarkPalette.shimmer

object ColorParceler : Parceler<Color> {
    override fun Color.write(parcel: Parcel, flags: Int) = parcel.writeLong(value.toLong())
    override fun create(parcel: Parcel) = Color(parcel.readLong())
}

object DpParceler : Parceler<Dp> {
    override fun Dp.write(parcel: Parcel, flags: Int) = parcel.writeFloat(value)
    override fun create(parcel: Parcel) = parcel.readFloat().dp
}
