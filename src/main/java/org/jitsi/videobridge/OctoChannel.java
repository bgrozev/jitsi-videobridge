package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import org.ice4j.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.octo.*;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Created by boris on 23/06/2017.
 */
public class OctoChannel
    extends RtpChannel
{
    // Currently we only need to distinguish between audio and video, so we define
    // these as constants instead of bothering with extra signaling.
    public static int CHANNEL_OCTO_ID_AUDIO = 0;
    public static int CHANNEL_OCTO_ID_VIDEO = 1;

    /**
     * The {@link Logger} used by the {@link RtpChannel} class to print debug
     * information. Note that instances should use {@link #logger} instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(OctoChannel.class);

    public String getConferenceId()
    {
        return conferenceId;
    }

    /**
     * The Octo ID of the conference, configured in {@link Conference} as the
     * global ID.
     */
    private final String conferenceId;

    private final MediaType mediaType;

    private OctoTransportManager transportManager;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new <tt>Channel</tt> instance which is to have a specific
     * ID. The initialization is to be considered requested by a specific
     * <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique
     * within the
     * list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance
     * is listed there as well.
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>RtpChannel</tt>
     * is to be a part of (or <tt>null</tt> if no it is not to be a part of a
     * channel-bundle).
     * @param transportNamespace the namespace of the transport to be used by
     * the
     * new instance. Can be either
     * {@link IceUdpTransportPacketExtension#NAMESPACE}
     * or {@link RawUdpTransportPacketExtension#NAMESPACE}.
     * @param initiator the value to use for the initiator field, or
     * <tt>null</tt>
     * to use the default value.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public OctoChannel(Content content, String id, String channelBundleId,
                       String transportNamespace, Boolean initiator)
        throws Exception
    {
        super(content, id, channelBundleId, transportNamespace, initiator);

        conferenceId = content.getConference().getGid();
        mediaType = content.getMediaType();
        logger
            = Logger.getLogger(
            classLogger,
            content.getConference().getLogger());
        logger.error("xxx octo conf id: "+conferenceId);
    }

    @Override
    void initialize(RTPLevelRelayType rtpLevelRelayType)
        throws IOException
    {
        logger.error("xxx initialize octo channel "+rtpLevelRelayType );
        super.initialize(rtpLevelRelayType);
    }

    public void setRelayIds(List<String> relayIds)
    {
        OctoTransportManager transportManager = getTransportManager0();

        transportManager.setRelayIds(relayIds);
    }

    private OctoTransportManager getTransportManager0()
    {
        TransportManager transportManager = getTransportManager();
        if (transportManager instanceof OctoTransportManager)
        {
            this.transportManager = (OctoTransportManager) transportManager;
        }
        else
        {
            logger.error("XXX transport manager not octo?");
        }
        return this.transportManager;
    }

    private RtpChannelDatagramFilter rtpFilter
        = new OctoDatagramPacketFilter(false);
    private RtpChannelDatagramFilter rtcpFilter
        = new OctoDatagramPacketFilter(true);

    @Override
    public RtpChannelDatagramFilter getDatagramFilter(boolean rtcp)
    {
        return rtcp ? rtcpFilter : rtpFilter;
    }

    private class OctoDatagramPacketFilter
       extends RtpChannelDatagramFilter
    {
        private boolean rtcp;
        private OctoDatagramPacketFilter(
            boolean rtcp)
        {
            super(OctoChannel.this, rtcp);
            this.rtcp = rtcp;
        }

        /**
         * This filter accepts only packets for its associated
         * {@link OctoChannel}. A packet is accepted if the conference id from
         * the Octo header matches the id of the channel's conference, and the
         * channel's filter itself accepts it (based on the configured Payload
         * Type numbers and SSRCs). The second part is needed in order to
         * @param p the <tt>DatagramPacket</tt> which is to be checked whether it is
         * accepted by this filter
         * </p>
         * Note that this is meant to work on Octo packets, not RTP packets.
         * @return {@code true} if the packet should be accepted, and
         * {@code false} otherwise.
         */
        @Override
        public boolean accept(DatagramPacket p)
        {
            String packetCid
                = OctoPacket.readConferenceId(p.getData(), p.getOffset(), p.getLength());
            if (!packetCid.equals(conferenceId))
            {
                logger.error("xxx reject packet conferenceId");
                return false;
            }

            MediaType packetMediaType
                = OctoPacket.readMediaType(
                        p.getData(), p.getOffset(), p.getLength());
            if (packetMediaType != mediaType)
            {
                logger.error("xxx reject packet octo media type");
                return false;
            }

           boolean packetIsRtcp
               = isRTCP(
                       p.getData(),
                       p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
                       p.getLength() - OctoPacket.OCTO_HEADER_LENGTH);
            return rtcp == packetIsRtcp; // the rtp socket gets all non-rtcp
        }
    }
    private boolean isRTCP(byte[] data, int off, int len)
    {
        // The shortest RTCP packet (an empty RR) is 8 bytes long, RTP packets
        // are at least 12 bytes long (due to the fixed header).
        if (len >= 8)
        {
            if (((data[off] & 0xc0) >> 6) == 2) // RTP/RTCP version field
            {
                int pt = data[off + 1] & 0xff;

                if (200 <= pt && pt <= 211)
                {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    protected void configureStream(MediaStream stream)
    {

    }
    @Override
    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p)
    {
        super.acceptDataInputStreamDatagramPacket(p);
        touch(ActivityType.PAYLOAD /* data received */);
        return true;
    }
    @Override
    protected boolean acceptControlInputStreamDatagramPacket(DatagramPacket p)
    {
        super.acceptControlInputStreamDatagramPacket(p);
        touch(ActivityType.PAYLOAD /* data received */);
        return true;
    }

    public MediaType getMediaType()
    {
        return mediaType;
    }
}
