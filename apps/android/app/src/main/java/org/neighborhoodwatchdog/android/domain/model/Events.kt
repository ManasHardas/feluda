package org.neighborhoodwatchdog.android.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** The one JSON configuration used for the API, the queue and the event viewer. */
val WatchdogJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * ISO-8601 UTC timestamps. Written with a trailing "Z" (timezone-aware, as the API requires).
 * Read leniently: an offset is honoured, and a value without an offset is taken as UTC.
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())

    fun parse(text: String): Instant = try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
    }
}

typealias Timestamp = @Serializable(with = InstantSerializer::class) Instant

/** Measurements at the time of an event. A field is null when it was not measured. Never a guess. */
@Serializable
data class Diagnostics(
    @SerialName("wifi_associated") val wifiAssociated: Boolean? = null,
    @SerialName("wifi_validated") val wifiValidated: Boolean? = null,
    @SerialName("wifi_radio_enabled") val wifiRadioEnabled: Boolean? = null,
    @SerialName("airplane_mode") val airplaneMode: Boolean? = null,
    @SerialName("home_network_match") val homeNetworkMatch: Boolean? = null,
    @SerialName("local_gateway_reachable") val localGatewayReachable: Boolean? = null,
    @SerialName("internet_reachable") val internetReachable: Boolean? = null,
    @SerialName("cellular_available") val cellularAvailable: Boolean? = null,
    @SerialName("default_transport") val defaultTransport: Transport? = null,
    @SerialName("network_changed_recently") val networkChangedRecently: Boolean? = null,
    @SerialName("vpn_active") val vpnActive: Boolean? = null,
    @SerialName("last_wifi_rssi_dbm") val lastWifiRssiDbm: Int? = null,
    @SerialName("band_evidence") val bandEvidence: BandEvidence = BandEvidence.BAND_UNKNOWN,
    @SerialName("external_power_lost_recently") val externalPowerLostRecently: Boolean? = null,
    @SerialName("battery_saver") val batterySaver: Boolean? = null,
    @SerialName("user_initiated") val userInitiated: Boolean? = null,
    @SerialName("gap_started_at") val gapStartedAt: Timestamp? = null,
    @SerialName("gap_ended_at") val gapEndedAt: Timestamp? = null,
    @SerialName("gap_reason") val gapReason: GapReason? = null,
)

@Serializable
data class ConnectivityEvent(
    @SerialName("event_id") val eventId: String,
    val sequence: Long,
    @SerialName("event_type") val eventType: EventType,
    @SerialName("connectivity_state") val connectivityState: ConnectivityState,
    @SerialName("occurred_at") val occurredAt: Timestamp,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version") val appVersion: String,
    val confidence: ObservationConfidence = ObservationConfidence.OBSERVED,
    val diagnostics: Diagnostics = Diagnostics(),
)

@Serializable
data class SubmitEventsRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("reported_via") val reportedVia: Transport,
    @SerialName("sent_at") val sentAt: Timestamp,
    val events: List<ConnectivityEvent>,
)

@Serializable
data class EventOutcome(
    @SerialName("event_id") val eventId: String,
    val result: EventResult,
    val reason: String? = null,
)

@Serializable
data class SubmitEventsResponse(
    @SerialName("server_time") val serverTime: Timestamp,
    val outcomes: List<EventOutcome> = emptyList(),
    @SerialName("retry_after_seconds") val retryAfterSeconds: Int? = null,
)

fun interface Clock {
    fun nowMs(): Long
}
