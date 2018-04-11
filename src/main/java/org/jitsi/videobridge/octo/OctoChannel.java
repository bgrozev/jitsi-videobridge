/*
 * Copyright @ 2017 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.octo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.stream.*;

/**
 * Represents an Octo channel, used for bridge-to-bridge communication.
 *
 * @author Boris Grozev
 */
public class OctoChannel
{
    /**
     * The {@link Logger} used by the {@link RtpChannel} class to print debug
     * information. Note that instances should use {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(OctoChannel.class);

    /**
     * The Octo ID of the conference, configured in {@link Conference} as the
     * global ID ({@link Conference#getGid()}).
     */
    private final String conferenceId;

    /**
     * The {@link MediaType} for this channel.
     */
    private final MediaType mediaType;

    /**
     * The {@link TransportManager} if this channel.
     */
    private OctoTransportManager transportManager;

    /**
     * The {@link org.ice4j.socket.DatagramPacketFilter} which (only) accepts
     * RTP packets for this channel.
     */
    private RtpChannelDatagramFilter rtpFilter
        = new OctoDatagramPacketFilter(false /* rtcp */);

    /**
     * The {@link org.ice4j.socket.DatagramPacketFilter} which (only) accepts
     * RTCP packets for this channel.
     */
    private RtpChannelDatagramFilter rtcpFilter
        = new OctoDatagramPacketFilter(true /* rtcp */);

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The {@link OctoEndpoints} instance which manages the conference
     * {@link AbstractEndpoint}s associated with this channel.
     * The audio and video {@code Octo} channels should have the same set of
     * endpoints, but since the endpoints of the audio channel a
     *
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * Whether this channel should handle Octo data packets. We always have
     * the Octo video channel handle data packets, while the Octo audio channel
     * ignores them.
     */
    private final boolean handleData;

    private final RtpChannel channel;

    /**
     * Initializes a new <tt>Channel</tt> instance which is to have a specific
     * ID. The initialization is to be considered requested by a specific
     * <tt>Content</tt>.
     *
     * @param id the ID of the new instance. It is expected to be unique
     * within the
     * list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance
     * is listed there as well.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public OctoChannel(RtpChannel channel, String id, MediaType mediaType)
        throws Exception
    {
        Conference conference = channel.getContent().getConference();
        this.channel = channel;
        conferenceId = conference.getGid();
        this.mediaType = mediaType;

        octoEndpoints = conference.getOctoEndpoints();
        octoEndpoints.setChannel(mediaType, this);

        // We are going to use one of the threads already reading from the
        // socket to handle data packets. Since both the audio and the video
        // channel's filters will be given the packet, we want to accept it only
        // in one of them. We chose to accept in the video channel.
        handleData = MediaType.VIDEO.equals(mediaType);

        logger
            = Logger.getLogger(classLogger, conference.getLogger());
    }

    /**
     * @return The Octo ID of the conference.
     */
    public String getConferenceId()
    {
        return conferenceId;
    }

    /**
     * Sets the list of remote Octo relays that this channel should transmit to.
     * @param relayIds the list of strings which identify a remote relay.
     */
    public void setRelayIds(List<String> relayIds)
    {
        OctoTransportManager transportManager = getOctoTransportManager();

        transportManager.setRelayIds(relayIds);
    }

    void setRtpEncodingParameters(
        List<SourcePacketExtension> sources,
        List<SourceGroupPacketExtension> sourceGroups)
    {
        if (octoEndpoints != null)
        {
            octoEndpoints.updateEndpoints(
                Arrays.stream(
                    channel.getStream().getMediaStreamTrackReceiver()
                        .getMediaStreamTracks())
                    .map(MediaStreamTrackDesc::getOwner)
                    .collect(Collectors.toSet()));
        }
    }

    /**
     * @return The transport manager of this {@link OctoChannel} as a
     * {@link OctoTransportManager} instance.
     */
    private OctoTransportManager getOctoTransportManager()
    {
        if (transportManager == null)
        {
            transportManager = (OctoTransportManager) channel.getTransportManager();
        }
        return transportManager;
    }

    /**
     * @return the {@link MediaType} of this channel.
     */
    public MediaType getMediaType()
    {
        return mediaType;
    }

    RtpChannelDatagramFilter getDatagramFilter(boolean rtcp)
    {
        return rtcp ? rtcpFilter : rtpFilter;
    }

    /**
     * Implements a {@link org.ice4j.socket.DatagramPacketFilter} which accepts
     * only RTP (or only RTCP) packets for this specific {@link OctoChannel}.
     */
    private class OctoDatagramPacketFilter
        extends RtpChannelDatagramFilter
    {
        /**
         * Whether to accept RTCP or RTP.
         */
        private boolean rtcp;

        /**
         * Initializes a new {@link OctoDatagramPacketFilter} instance.
         * @param rtcp whether to accept RTCP or RTP.
         */
        private OctoDatagramPacketFilter(boolean rtcp)
        {
            super(OctoChannel.this.channel, rtcp);
            this.rtcp = rtcp;
        }

        /**
         * This filter accepts only packets for its associated
         * {@link OctoChannel}. A packet is accepted if the conference ID from
         * the Octo header matches the ID of the channel's conference, and the
         * channel's filter itself accepts it (based on the configured Payload
         * Type numbers and SSRCs). The second part is needed because we expect
         * a conference to have two Octo channels -- one for audio and one for
         * video.
         * @param p the <tt>DatagramPacket</tt> which is assumed to contain an
         * Octo packet and is to be accepted (or not) by this filter.
         * </p>
         * Note that this is meant to work on Octo packets, not RTP packets.
         * @return {@code true} if the packet should be accepted, and
         * {@code false} otherwise.
         */
        @Override
        public boolean accept(DatagramPacket p)
        {
            String packetCid
                = OctoPacket.readConferenceId(
                        p.getData(), p.getOffset(), p.getLength());
            if (!packetCid.equals(conferenceId))
            {
                return false;
            }

            MediaType packetMediaType
                = OctoPacket.readMediaType(
                        p.getData(), p.getOffset(), p.getLength());

            if (mediaType.equals(packetMediaType))
            {
                // The RTP/RTCP packet is preceded by the fixed length Octo
                // header.
                boolean packetIsRtcp
                    = RTCPUtils.isRtcp(
                        p.getData(),
                        p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
                        p.getLength() - OctoPacket.OCTO_HEADER_LENGTH);

                // Note that the rtp socket gets all non-rtcp packets.
                return rtcp == packetIsRtcp;
            }

            if (MediaType.DATA.equals(packetMediaType) && handleData)
            {
                // In this case we're going to return 'false' anyway, because
                // we already read the contents and we don't want the packet to
                // be accepted by libjitsi.
                handleDataPacket(p);
            }

            return false;
        }
    }

    /**
     * Handles an incoming Octo packet of type {@code data}.
     */
    private void handleDataPacket(DatagramPacket p)
    {
        byte[] msgBytes = new byte[p.getLength() - OctoPacket.OCTO_HEADER_LENGTH];
        System.arraycopy(
            p.getData(), p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
            msgBytes, 0,
            msgBytes.length);
        String msg = new String(msgBytes, StandardCharsets.UTF_8);

        if (logger.isDebugEnabled())
        {
            logger.debug("Received a message in an Octo data packet: " + msg);
        }

        octoEndpoints.messageTransport.onMessage(this, msg);
    }

    AbstractEndpoint getEndpoint(long ssrc)
    {
        return octoEndpoints == null ? null : octoEndpoints.findEndpoint(ssrc);
    }

    /**
     * Sends a string message through the Octo transport.
     * @param msg the message to send
     * @param sourceEndpointId the ID of the source endpoint.
     */
    void sendMessage(String msg, String sourceEndpointId)
    {
        getOctoTransportManager()
            .sendMessage(msg, sourceEndpointId, getConferenceId());
    }
}
