package app.vitune.providers.spotify.auth

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object SpotifyTotp {
    private const val TOTP_INTERVAL = 30L
    private const val TOTP_DIGITS = 6
    private val SECRET_CIPHER = intArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74,
        50, 111, 120, 97, 75, 76, 94, 102, 43, 69,
        49, 120, 118, 80, 64, 78,
    )

    fun generate(serverTimeSeconds: Long): String {
        val secret = deriveSecret()
        return generateTotp(secret, serverTimeSeconds)
    }

    private fun deriveSecret(): ByteArray {
        val transformed = SECRET_CIPHER.mapIndexed { i, byte ->
            byte xor ((i % 33) + 9)
        }
        val joined = transformed.joinToString("") { it.toString() }
        val hexStr = joined.toByteArray(Charsets.UTF_8).joinToString("") {
            String.format("%02x", it)
        }
        return hexStringToByteArray(hexStr)
    }

    private fun generateTotp(secret: ByteArray, timeSeconds: Long): String {
        val counter = timeSeconds / TOTP_INTERVAL
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 10.0.pow(TOTP_DIGITS).toInt()
        return otp.toString().padStart(TOTP_DIGITS, '0')
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
