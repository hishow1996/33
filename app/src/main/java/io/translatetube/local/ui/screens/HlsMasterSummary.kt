package io.translatetube.local.ui.screens

import java.net.URI
import java.util.Locale

/** One `#EXT-X-STREAM-INF` entry of an HLS master playlist. */
internal data class HlsVariantSummary(
    /** The URI exactly as written in the master (possibly relative). */
    val uri: String,
    /** AVERAGE-BANDWIDTH when declared, otherwise BANDWIDTH. */
    val bandwidth: Long,
    val width: Int?,
    val height: Int?,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioGroup: String?,
) {
    /** A rendition that carries no picture: it declares only an audio codec and no resolution. */
    val isAudioOnly: Boolean
        get() = width == null && videoCodec == null && audioCodec != null
}

/** One `#EXT-X-MEDIA:TYPE=AUDIO` entry. A missing [uri] means the audio is muxed into the variant. */
internal data class HlsAudioRenditionSummary(
    val groupId: String?,
    val name: String?,
    val language: String?,
    val channels: Int?,
    val uri: String?,
    val isDefault: Boolean,
)

internal data class HlsMasterSummary(
    val variants: List<HlsVariantSummary>,
    val audioRenditions: List<HlsAudioRenditionSummary>,
)

/**
 * Describes the tracks of an HLS master playlist for display. It only reads; which rendition the
 * app downloads is still decided by `HlsAudioDownloader`. The parser is tolerant on purpose:
 * a line it does not understand is skipped instead of failing the whole source check.
 */
internal object HlsMasterSummaryParser {

    fun parse(manifest: String): HlsMasterSummary {
        val lines = manifest.removePrefix("﻿").lineSequence().map(String::trim).filter(String::isNotEmpty)
        val variants = mutableListOf<HlsVariantSummary>()
        val audio = mutableListOf<HlsAudioRenditionSummary>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    pending = parseAttributeList(line.substringAfter(':'))
                }
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) -> {
                    val attrs = parseAttributeList(line.substringAfter(':'))
                    if (attrs["TYPE"].equals("AUDIO", ignoreCase = true)) {
                        audio += HlsAudioRenditionSummary(
                            groupId = attrs["GROUP-ID"],
                            name = attrs["NAME"]?.takeIf { it.isNotBlank() },
                            language = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() },
                            channels = attrs["CHANNELS"]?.substringBefore('/')?.trim()?.toIntOrNull()
                                ?.takeIf { it > 0 },
                            uri = attrs["URI"]?.takeIf { it.isNotBlank() },
                            isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                        )
                    }
                }
                !line.startsWith("#") -> {
                    val attrs = pending
                    pending = null
                    if (attrs != null) variantFrom(line, attrs)?.let { variants += it }
                }
            }
        }
        return HlsMasterSummary(variants, audio)
    }

    private fun variantFrom(uri: String, attrs: Map<String, String>): HlsVariantSummary? {
        val bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: attrs["BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: return null
        val resolution = attrs["RESOLUTION"]?.let { RESOLUTION.matchEntire(it.trim()) }
        val codecs = attrs["CODECS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return HlsVariantSummary(
            uri = uri,
            bandwidth = bandwidth,
            width = resolution?.groupValues?.get(1)?.toIntOrNull(),
            height = resolution?.groupValues?.get(2)?.toIntOrNull(),
            videoCodec = codecs.firstOrNull { codecFamily(it) in VIDEO_FAMILIES }
                ?.let { WebMediaFormat.codecName(it) },
            audioCodec = codecs.firstOrNull { codecFamily(it) in AUDIO_FAMILIES }
                ?.let { WebMediaFormat.codecName(it) },
            audioGroup = attrs["AUDIO"],
        )
    }

    private val RESOLUTION = Regex("""(\d{2,5})x(\d{2,5})""")
    private val VIDEO_FAMILIES = setOf("avc1", "avc3", "hvc1", "hev1", "dvh1", "dvhe", "av01", "vp09", "vp08", "mp4v")
    private val AUDIO_FAMILIES = setOf("mp4a", "ac-3", "ec-3", "opus", "flac", "alac", "mp3")

    private fun codecFamily(codec: String): String = codec.substringBefore('.').lowercase(Locale.ROOT)

    /** Splits an HLS attribute list; commas inside a quoted value (CODECS="a,b") do not split. */
    fun parseAttributeList(input: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val n = input.length
        var i = 0
        while (i < n) {
            while (i < n && (input[i] == ',' || input[i].isWhitespace())) i++
            val keyStart = i
            while (i < n && input[i] != '=' && input[i] != ',') i++
            if (i >= n || input[i] == ',') continue // a key without a value: skip it
            val key = input.substring(keyStart, i).trim().uppercase(Locale.ROOT)
            i++ // '='
            val value: String
            if (i < n && input[i] == '"') {
                i++
                val valueStart = i
                while (i < n && input[i] != '"') i++
                value = input.substring(valueStart, i)
                if (i < n) i++ // closing quote
            } else {
                val valueStart = i
                while (i < n && input[i] != ',') i++
                value = input.substring(valueStart, i).trim()
            }
            if (key.isNotEmpty() && key !in result) result[key] = value
        }
        return result
    }

    /**
     * Track details for the rendition the app picked from this master.
     *
     * @param selectedUrl the media playlist chosen by `HlsAudioDownloader` (absolute URL)
     * @param independentAudio whether that choice is a separate AUDIO rendition
     * @param declaredBandwidth / [declaredResolution] the values the downloader reported for a
     *        muxed choice; used to find the variant when the URL text differs after normalisation
     */
    fun describe(
        summary: HlsMasterSummary,
        masterUrl: String,
        selectedUrl: String,
        independentAudio: Boolean,
        declaredBandwidth: Long? = null,
        declaredResolution: String? = null,
    ): HlsTrackDescription {
        val videoVariants = summary.variants.filter { !it.isAudioOnly }.sortedBy { it.bandwidth }
        val selectedVariant = summary.variants.firstOrNull { sameUrl(masterUrl, it.uri, selectedUrl) }
            ?: if (!independentAudio && declaredBandwidth != null) {
                summary.variants.firstOrNull {
                    it.bandwidth == declaredBandwidth &&
                        (declaredResolution == null || declaredResolution == "${it.width}x${it.height}")
                }
            } else {
                null
            }

        // A "muxed" pick that is really an audio-only variant carries no picture at all.
        val audioOnlyVariant = !independentAudio && selectedVariant?.isAudioOnly == true
        val treatAsIndependent = independentAudio || audioOnlyVariant

        val rendition = if (independentAudio) {
            summary.audioRenditions.firstOrNull { it.uri != null && sameUrl(masterUrl, it.uri, selectedUrl) }
                ?: summary.audioRenditions.firstOrNull { it.uri != null && it.isDefault }
                ?: summary.audioRenditions.firstOrNull { it.uri != null }
        } else {
            null
        }
        val audioCodec = when {
            rendition != null -> summary.variants.firstOrNull {
                it.audioGroup == rendition.groupId && it.audioCodec != null
            }?.audioCodec
            else -> selectedVariant?.audioCodec
        }
        val groupTracks = if (rendition?.groupId != null) {
            summary.audioRenditions.count { it.groupId == rendition.groupId && it.uri != null }
        } else {
            0
        }

        val videoPrimary = if (treatAsIndependent) videoVariants.lastOrNull() else selectedVariant
        val sides = videoVariants.mapNotNull { WebMediaFormat.resolutionSide(it.width, it.height) }
        val showRange = treatAsIndependent && videoVariants.size > 1
        val info = WebCandidateMediaInfo(
            container = "HLS",
            hasVideo = when {
                audioOnlyVariant -> false
                videoVariants.isNotEmpty() -> true
                else -> null
            },
            hasAudio = true,
            audioTrackCount = groupTracks,
            videoCodec = videoPrimary?.videoCodec,
            videoWidth = videoPrimary?.width,
            videoHeight = videoPrimary?.height,
            videoBitrateBps = videoPrimary?.bandwidth,
            videoBitrateBasis = WebBitrateBasis.DECLARED,
            videoBitrateMinBps = if (showRange) videoVariants.first().bandwidth else null,
            videoBitrateMaxBps = if (showRange) videoVariants.last().bandwidth else null,
            videoHeightMin = if (showRange) sides.minOrNull() else null,
            videoHeightMax = if (showRange) sides.maxOrNull() else null,
            videoVariantCount = videoVariants.size,
            audioCodec = audioCodec,
            audioChannels = rendition?.channels,
            audioLanguage = rendition?.language ?: rendition?.name,
        )
        return HlsTrackDescription(info = info, audioOnlyVariant = audioOnlyVariant)
    }

    private fun sameUrl(base: String, reference: String?, target: String): Boolean {
        if (reference == null) return false
        val resolved = runCatching { URI(base).resolve(URI(reference)).normalize() }.getOrNull() ?: return false
        val wanted = runCatching { URI(target).normalize() }.getOrNull() ?: return false
        return resolved == wanted
    }
}

internal data class HlsTrackDescription(
    val info: WebCandidateMediaInfo,
    /** The downloader's "muxed" pick is actually an audio-only variant. */
    val audioOnlyVariant: Boolean,
)
