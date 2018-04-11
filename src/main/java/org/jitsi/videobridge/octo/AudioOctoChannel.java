package org.jitsi.videobridge.octo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

import java.net.*;
import java.util.*;

public class AudioOctoChannel
    extends AudioChannel
{
    private final OctoChannel octoChannel;
    /**
     * Initializes a new <tt>AudioChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>AudioChannel</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     * @param transportNamespace the namespace of transport used by this
     * channel. Can be either {@link IceUdpTransportPacketExtension#NAMESPACE}
     * or {@link RawUdpTransportPacketExtension#NAMESPACE}.
     * @param initiator the value to use for the initiator field, or
     * <tt>null</tt> to use the default value.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public AudioOctoChannel(Content content, String id,
                            String channelBundleId, String transportNamespace,
                            Boolean initiator) throws Exception
    {
        super(
            content, id, null /*channelBundleId*/,
            OctoTransportManager.NAMESPACE, false /*initiator*/);

        octoChannel = new OctoChannel(this, id, MediaType.AUDIO);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        super.describe(commonIq);

        commonIq.setType(ColibriConferenceIQ.OctoChannel.TYPE);
    }

    @Override
    public boolean setRtpEncodingParameters(
        List<SourcePacketExtension> sources,
        List<SourceGroupPacketExtension> sourceGroups)
    {
        boolean changed = super.setRtpEncodingParameters(sources, sourceGroups);

        if (changed)
        {
            octoChannel.setRtpEncodingParameters(sources, sourceGroups);
        }

        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RtpChannelDatagramFilter getDatagramFilter(boolean rtcp)
    {
        return octoChannel.getDatagramFilter(rtcp);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void configureStream(MediaStream stream)
    {
        // Prevent things like retransmission requests to be enabled.
    }


    /**
     * {@inheritDoc}
     * <p/>
     * This is called by the {@link MediaStream}'s input thread
     * ({@link org.jitsi.impl.neomedia.RTPConnectorInputStream}, either RTP or
     * RTCP), after if has already {@code receive()}ed a packet from its
     * socket. The flow of this thread is:
     * <pre>
     * 1. Call {@code receive()} on the
     * {@link org.ice4j.socket.MultiplexedDatagramSocket}
     *     1.1. The socket's {@link org.ice4j.socket.DatagramPacketFilter}s are
     *     applied (i.e. the associated {@link OctoDatagramPacketFilter}) to
     *     potential packets to be accepted.
     *     1.2. {@code receive()} only returns when a matching packet is
     *     available.
     * 2. The {@link org.jitsi.impl.neomedia.RTPConnectorInputStream}'s
     * datagram packet filters are applied, and this is where
     * {@link #acceptDataInputStreamDatagramPacket} executes. If any of the
     * filters reject the packet, it is dropped.
     * 3. The packet is converted to a {@link RawPacket} and passed through the
     * {@link MediaStream}'s transform chain.
     * </pre>
     */
    @Override
    protected boolean acceptDataInputStreamDatagramPacket(DatagramPacket p)
    {
        super.acceptDataInputStreamDatagramPacket(p);

        // super touches only if it determined that it should accept.
        touch(ActivityType.PAYLOAD /* data received */);
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AbstractEndpoint getEndpoint(long ssrc)
    {
        return octoChannel.getEndpoint(ssrc);
    }
}
