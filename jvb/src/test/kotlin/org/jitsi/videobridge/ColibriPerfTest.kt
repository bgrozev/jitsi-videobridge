package org.jitsi.videobridge

import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.shutdown.ShutdownServiceImpl
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.octo.config.OctoConfig
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension
import org.jitsi.xmpp.extensions.jitsimeet.SSRCInfoPacketExtension
import org.jxmpp.jid.impl.JidCreate
import java.net.URI
import java.time.Clock
import java.time.Duration

class ColibriPerfTest : ConfigTest() {
    private val shutdownService: ShutdownServiceImpl = mockk(relaxed = true)
    private val videobridge = Videobridge(null, shutdownService, mockk())
    private val logger = createLogger()
    private val clock = Clock.systemUTC()

    init {
        withNewConfig("""
            videobridge.octo.enabled=true
            videobridge.octo.bind-address=127.0.0.1
        """.trimMargin(), loadDefaults = true) {
            logger.info("Octo enabled = ${OctoConfig.config.enabled}")

            val numOctoEndpoints = 200
            measure(" create conference with $numOctoEndpoints octo endpoints") {
                val iq = createConference(numOctoEndpoints)
                logger.info("RECV: ${iq.toXML()}")
                videobridge.handleColibriConferenceIQ(iq)
            }
        }
    }

    private fun measure(name: String = "block", block: () -> Unit) {
        val start = clock.instant()
        block()
        val stop = clock.instant()
        val duration = Duration.between(start, stop)
        logger.info("Took $duration to execute: $name")
    }

    private fun createConference(numOctoEndpoints: Int) = ColibriConferenceIQ().apply {
        gid = "1234"
        name = JidCreate.entityBareFrom("test@example.com")
        addContent(createAudioContent(numOctoEndpoints))
        addContent(createVideoContent())
    }

    private fun createVideoContent() = ColibriConferenceIQ.Content("video").apply {
        addChannel(ColibriConferenceIQ.OctoChannel())
    }

    private fun Int.toEndpointId() = this.toString(16).padStart(8, '0')

    private fun createAudioContent(numOctoEndpoints: Int) = ColibriConferenceIQ.Content("audio").apply {
        addChannel(ColibriConferenceIQ.OctoChannel().apply {
            addPayloadType(PayloadTypePacketExtension().apply {
                setId(112)
                name = "red"
                channels = 2
                clockrate = 48000
            })
            addPayloadType(PayloadTypePacketExtension().apply {
                setId(111)
                name = "opus"
                channels = 2
                clockrate = 48000
            })
            addPayloadType(PayloadTypePacketExtension().apply {
                setId(103)
                name = "ISAC"
                clockrate = 16000
            })
            addPayloadType(PayloadTypePacketExtension().apply {
                setId(104)
                name = "ISAC"
                clockrate = 32000
            })
            addPayloadType(PayloadTypePacketExtension().apply {
                setId(126)
                name = "telephone-event"
                clockrate = 8000
            })

            addRtpHeaderExtension(RTPHdrExtPacketExtension().apply {
                id = "1"
                uri = URI.create("urn:ietf:params:rtp-hdrext:ssrc-audio-level")
            })
            addRtpHeaderExtension(RTPHdrExtPacketExtension().apply {
                id = "5"
                uri = URI.create("http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01")
            })

            repeat(numOctoEndpoints) { i ->
                addSource(SourcePacketExtension().apply {
                    ssrc = (i+1).toLong()
                    addChildExtension(SSRCInfoPacketExtension().apply {
                        owner = JidCreate.from("confname@example.com/${(i+1).toEndpointId()}")
                    })
                })
            }
        })
    }
}