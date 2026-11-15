package app.vitune.providers.lastfm.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LastFmTokenResponse(
    val token: String? = null,
    val error: Int? = null,
    val message: String? = null
)

@Serializable
data class LastFmSessionResponse(
    val session: LastFmSession? = null,
    val error: Int? = null,
    val message: String? = null
)

@Serializable
data class LastFmMobileSessionResponse(
    val session: LastFmSession? = null,
    val error: Int? = null,
    val message: String? = null
)

@Serializable
data class LastFmSession(
    val name: String,
    val key: String,
    val subscriber: Int = 0
)

@Serializable
data class LastFmStatusResponse(
    val error: Int? = null,
    val message: String? = null
)

@Serializable
data class LastFmScrobbleResponse(
    val scrobbles: LastFmScrobbles? = null,
    val error: Int? = null,
    val message: String? = null
)

@Serializable
data class LastFmScrobbles(
    @SerialName("@attr")
    val attr: LastFmScrobblesAttr? = null
)

@Serializable
data class LastFmScrobblesAttr(
    val accepted: Int = 0,
    val ignored: Int = 0
)
