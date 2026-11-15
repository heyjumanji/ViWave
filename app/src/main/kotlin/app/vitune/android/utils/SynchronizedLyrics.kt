package app.vitune.android.utils

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.parcelize.Parcelize
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Parcelize
@Immutable
data class WordTiming(
    val word: String,
    val startTime: Long,
    val endTime: Long,
    val isBackgroundVocal: Boolean = false
) : Parcelable

@Stable
class SynchronizedLyrics(
    val sentences: ImmutableMap<Long, String>,
    val wordTimings: ImmutableMap<Int, ImmutableList<WordTiming>> = persistentMapOf(),
    private val positionProvider: () -> Long
) {
    var index by mutableIntStateOf(0)
        private set

    var currentPositionMs by mutableLongStateOf(0L)
        private set

    private val currentIndex: Int
        get() {
            var index = -1
            val currentPos = positionProvider()
            for ((key) in sentences) {
                if (key > currentPos) break
                index++
            }
            return if (index == -1) 0 else index
        }

    init {
        currentPositionMs = positionProvider()
        index = currentIndex
    }

    fun update(): Boolean {
        val pos = positionProvider()
        val posChanged = currentPositionMs != pos
        if (posChanged) {
            currentPositionMs = pos
        }
        val newIndex = currentIndex
        val indexChanged = newIndex != index
        if (indexChanged) {
            index = newIndex
        }
        return posChanged || indexChanged
    }
}

@Parcelize
@Immutable
data class SynchronizedLyricsState(
    val sentences: Map<Long, String>?,
    val wordTimings: Map<Int, List<WordTiming>>? = null,
    val offset: Long
) : Parcelable

private val LRC_TAG_REGEX = Regex("(?:<|\\(|\\[)(\\d{1,3}):(\\d{2})\\.(\\d{1,3})(?:>|\\)|])")
private val WHITESPACE_REGEX = Regex("\\s+")
private val VOWEL_CLUSTER_REGEX = Regex("[aeiouyAEIOUY]+")

fun cleanLrcText(text: String): String {
    return text.replace(LRC_TAG_REGEX, "").replace(WHITESPACE_REGEX, " ").trim()
}

private fun parseMatchTimestampMs(match: MatchResult): Long {
    val (minutes, seconds, millis) = match.destructured
    val normMillis = when (millis.length) {
        1 -> millis.toInt() * 100
        2 -> millis.toInt() * 10
        else -> millis.take(3).toInt()
    }
    return minutes.toLong() * 60_000L +
        seconds.toLong() * 1_000L +
        normMillis.toLong()
}

private fun estimateWordWeight(word: String): Double {
    val clean = word.trim('(', ')', '[', ']', '"', '\'')
    val vowelCount = VOWEL_CLUSTER_REGEX.findAll(clean).count().coerceAtLeast(1)
    val charWeight = clean.length.coerceIn(1, 14) * 0.35
    val punctuationPause = when {
        word.endsWith(",") || word.endsWith(";") || word.endsWith("—") -> 0.65
        word.endsWith(".") || word.endsWith("!") || word.endsWith("?") -> 0.85
        else -> 0.0
    }
    return vowelCount * 0.75 + charWeight + punctuationPause
}

private fun splitSegmentIntoWords(
    segmentText: String,
    segmentStartMs: Long,
    segmentEndMs: Long,
    inParenthesesInitial: Boolean
): Pair<List<WordTiming>, Boolean> {
    val rawTokens = segmentText.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
    if (rawTokens.isEmpty()) return emptyList<WordTiming>() to inParenthesesInitial

    var inParens = inParenthesesInitial
    val tokenParens = BooleanArray(rawTokens.size)
    for (idx in rawTokens.indices) {
        val tok = rawTokens[idx]
        if (tok.startsWith("(") || tok.startsWith("（")) inParens = true
        tokenParens[idx] = inParens
        if (tok.endsWith(")") || tok.endsWith("）")) inParens = false
    }

    if (rawTokens.size == 1) {
        return listOf(
            WordTiming(
                word = rawTokens[0],
                startTime = segmentStartMs,
                endTime = segmentEndMs.coerceAtLeast(segmentStartMs + 45L),
                isBackgroundVocal = tokenParens[0]
            )
        ) to inParens
    }

    val totalDuration = (segmentEndMs - segmentStartMs).coerceAtLeast(rawTokens.size * 45L)
    val weights = rawTokens.map { estimateWordWeight(it) }
    val totalWeight = weights.sum().coerceAtLeast(0.1)

    var cursor = segmentStartMs
    val result = rawTokens.mapIndexed { idx, token ->
        val dur = if (idx == rawTokens.lastIndex) {
            (segmentStartMs + totalDuration - cursor).coerceAtLeast(40L)
        } else {
            ((totalDuration * weights[idx]) / totalWeight).toLong().coerceAtLeast(40L)
        }
        val wStart = cursor
        val wEnd = (wStart + dur).coerceAtMost(segmentStartMs + totalDuration).coerceAtLeast(wStart + 40L)
        cursor = wEnd
        WordTiming(
            word = token,
            startTime = wStart,
            endTime = wEnd,
            isBackgroundVocal = tokenParens[idx]
        )
    }
    return result to inParens
}

fun buildWordTimingsForSentences(sentences: Map<Long, String>): Map<Int, List<WordTiming>> {
    val entries = sentences.entries.toList()
    val wordTimingsMap = mutableMapOf<Int, List<WordTiming>>()

    for (i in entries.indices) {
        val (lineStartTime, rawLineText) = entries[i]
        val lineEndTime = if (i < entries.size - 1) {
            entries[i + 1].key
        } else {
            lineStartTime + 4500L
        }

        val rawLineDuration = (lineEndTime - lineStartTime).coerceAtLeast(120L)
        val matches = LRC_TAG_REGEX.findAll(rawLineText).toList()

        if (matches.isNotEmpty()) {
            val words = mutableListOf<WordTiming>()
            var inParens = false

            // Handle any leading text before the first inline <mm:ss.xx> tag
            val firstMatch = matches.first()
            if (firstMatch.range.first > 0) {
                val leadingText = rawLineText.substring(0, firstMatch.range.first).trim()
                if (leadingText.isNotEmpty()) {
                    val firstTagMs = parseMatchTimestampMs(firstMatch).coerceAtLeast(lineStartTime)
                    val leadEnd = if (firstTagMs > lineStartTime) firstTagMs else lineStartTime + 250L
                    val (leadWords, nextParens) = splitSegmentIntoWords(
                        segmentText = leadingText,
                        segmentStartMs = lineStartTime,
                        segmentEndMs = leadEnd,
                        inParenthesesInitial = inParens
                    )
                    words.addAll(leadWords)
                    inParens = nextParens
                }
            }

            for (mIdx in matches.indices) {
                val match = matches[mIdx]
                val wordStartTime = parseMatchTimestampMs(match).coerceAtLeast(lineStartTime)

                val textStart = match.range.last + 1
                val textEnd = if (mIdx < matches.size - 1) matches[mIdx + 1].range.first else rawLineText.length
                val wordText = rawLineText.substring(textStart, textEnd).trim()

                // If wordText is empty, this tag is an explicit end-timestamp marker for the previous word
                if (wordText.isEmpty()) continue

                val wordEndTime = if (mIdx < matches.size - 1) {
                    parseMatchTimestampMs(matches[mIdx + 1]).coerceAtLeast(wordStartTime + 45L)
                } else {
                    val estimatedWordDuration = maxOf(380L, (wordText.length * 115L).coerceAtMost(1800L))
                    minOf(lineEndTime, wordStartTime + estimatedWordDuration).coerceAtLeast(wordStartTime + 45L)
                }

                val (segmentWords, nextParens) = splitSegmentIntoWords(
                    segmentText = wordText,
                    segmentStartMs = wordStartTime,
                    segmentEndMs = wordEndTime,
                    inParenthesesInitial = inParens
                )
                words.addAll(segmentWords)
                inParens = nextParens
            }

            if (words.isNotEmpty()) {
                wordTimingsMap[i] = words
                continue
            }
        }

        // Prosody-weighted syllable interpolation when only line-level LRC timestamps are available
        val cleanText = cleanLrcText(rawLineText)
        if (cleanText.isEmpty()) {
            wordTimingsMap[i] = emptyList()
            continue
        }

        val rawWords = cleanText.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val totalChars = rawWords.sumOf { it.length }
        val estimatedVocalDuration = maxOf(1400L, (totalChars * 135L + 380L).coerceAtMost(7500L))
        // Leave a slight natural breathing gap (12%) before the next line starts
        val maxSingableDuration = (rawLineDuration * 0.88).toLong().coerceAtLeast(120L)
        val activeLineDuration = minOf(maxSingableDuration, estimatedVocalDuration)

        val (interpolatedWords, _) = splitSegmentIntoWords(
            segmentText = cleanText,
            segmentStartMs = lineStartTime,
            segmentEndMs = lineStartTime + activeLineDuration,
            inParenthesesInitial = false
        )
        wordTimingsMap[i] = interpolatedWords
    }

    return wordTimingsMap
}
