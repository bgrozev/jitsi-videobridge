/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;
import org.ice4j.socket.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.Logger;
import org.jitsi.videobridge.*;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * A {@link TransportManager} implementation for communication with other
 * jitsi-videobridge instances and/or relays.
 *
 * @author Boris Grozev
 */
public class OctoTransportManager
    extends TransportManager
{
    /**
     * The {@link Logger} used by the {@link OctoTransportManager} class to
     * print debug information. Note that instances should use {@link #logger}
     * instead.
     */
    private static final Logger classLogger
        = Logger.getLogger(OctoTransportManager.class);

    /**
     * A namespace which identifies the Octo transport manager.
     */
    public static final String NAMESPACE = "http://jitsi.org/octo";

    /**
     * The single {@link OctoChannel} that this {@link TransportManager} is
     * associated with.
     */
    private OctoChannel channel;

    /**
     * The underlying {@link OctoRelay} which manages the socket through which
     * packets will be sent, and from which packets will be received.
     */
    private OctoRelay octoRelay;

    /**
     * The {@link MultiplexedDatagramSocket} derived from the {@link OctoRelay}'s
     * socket, which receives only RTP packets destined for this transport
     * manager (or rather its {@link OctoChannel}.
     */
    private DatagramSocket rtpSocket;

    /**
     * The {@link MultiplexedDatagramSocket} derived from the {@link OctoRelay}'s
     * socket, which receives only RTCP packets destined for this transport
     * manager (or rather its {@link OctoChannel}.
     */
    private DatagramSocket rtcpSocket;

    /**
     * The (non-functional) {@link SrtpControl} maintained by this instance,
     * which is used as the {@link SrtpControl} of the {@link MediaStream} of
     * the channel. It is necessary to use a {@link NullSrtpControl}, because
     * {@link MediaStream} defaults to a ZRTP implementation otherwise.
     */
    private final SrtpControl srtpControl = new NullSrtpControl();

    /**
     * The list of addresses of remote relays to which outgoing packets should
     * be sent. This list is maintained by the conference organizer based on
     * the videobridges participating in the conference, and configured to this
     * {@link TransportManager} through Colibri signaling.
     */
    private List<SocketAddress> remoteRelays;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Synchronizes access to {@link #rtpSocket} and {@link #rtcpSocket}.
     */
    private final Object socketsSyncRoot = new Object();

    /**
     * Initializes a new {@link OctoTransportManager} instance.
     * @param channel the associated {@link OctoChannel}
     */
    public OctoTransportManager(Channel channel)
    {
        if (!(channel instanceof OctoChannel))
        {
            throw new IllegalArgumentException("channel");
        }

        this.channel = (OctoChannel) channel;

        logger
            = Logger.getLogger(
                classLogger,
                channel.getContent().getConference().getLogger());

        OctoRelayService relayService
            = ServiceUtils.getService(
                    channel.getBundleContext(),
                    OctoRelayService.class);
        octoRelay = Objects.requireNonNull(relayService.getRelay());
        // init the list of remote relays

    }

    @Override
    public void close()
    {
        super.close();

        synchronized (socketsSyncRoot)
        {
            rtpSocket.close();
            rtcpSocket.close();
        }
    }

    @Override
    public StreamConnector getStreamConnector(Channel channel)
    {
        try
        {
            initializeSockets();
        }
        catch (IOException ioe)
        {
            logger.error("Failed to initialize sockets: ", ioe);
            return null;
        }
        return new DefaultStreamConnector(
            rtpSocket,
            rtcpSocket,
            true /* rtcpmux: ??? */);
    }

    /**
     * Initializes the filtered sockets derived from the socket maintained
     * by the {@link OctoRelay}, which are to receive RTP (or RTCP) packets
     * from the {@link OctoChannel} associated with this transport manager.
     * @throws IOException
     */
    private void initializeSockets()
        throws IOException
    {
        synchronized (socketsSyncRoot)
        {
            if (rtpSocket != null)
            {
                //rtpSocket and rtcpSocket are always set together
                return;
            }

            MultiplexingDatagramSocket relaySocket = octoRelay.getSocket();

            rtpSocket
                = createOctoSocket(relaySocket.getSocket(channel.getDatagramFilter(false)));
            rtcpSocket
                = createOctoSocket(relaySocket.getSocket(channel.getDatagramFilter(true)));
        }
    }

    private DatagramSocket createOctoSocket(DatagramSocket socket)
        throws SocketException
    {
        return new DelegatingDatagramSocket(socket)
        {
            @Override
            public void receive(DatagramPacket p)
                throws IOException
            {
                super.receive(p);
                logger.error("xxx received "+channel.getMediaType().toString()+" "+p.getLength());

                //printPacket(p.getData(), p.getOffset(), p.getLength(), "xxx receive before strip: ");
                // Strip the Octo header
                try
                {
                    p.setData(
                            p.getData(),
                            p.getOffset() + OctoPacket.OCTO_HEADER_LENGTH,
                            p.getLength() - OctoPacket.OCTO_HEADER_LENGTH);
                }
                catch (Exception e)
                {
                    logger.error("xxx failed to strip header off="+p.getOffset()+" len="+p.getLength()+" data.len="+p.getData().length);
                }
                //printPacket(p.getData(), p.getOffset(), p.getLength(), "xxx receive after strip: ");
            }


            @Override
            public void send(DatagramPacket p)
                throws IOException
            {
                logger.error("xxx sent "+channel.getMediaType().toString()+" "+p.getLength());
                doSend(p);
            }
        };
    }

    @Override
    public MediaStreamTarget getStreamTarget(Channel channel)
    {
        // We'll just hack in the local address so that we have something
        // non-null. It should never be used anyway.
        DatagramSocket socket = octoRelay.getSocket();
        InetAddress inetAddress = socket.getLocalAddress();
        int port = socket.getLocalPort();
        //logger.error("xxx "+inetAddress);
        return new MediaStreamTarget(inetAddress, port, inetAddress, port);
    }

    @Override
    protected void describe(IceUdpTransportPacketExtension pe)
    {
        // No need to describe anything
    }

    @Override
    public SrtpControl getSrtpControl(Channel channel)
    {
        // We don't want SRTP with Octo. But returning null makes the
        // MediaStream initialize ZRTP.
        return srtpControl;
    }

    @Override
    public String getXmlNamespace()
    {
        // Do we care?
        return null;
    }

    @Override
    public void startConnectivityEstablishment(
        IceUdpTransportPacketExtension transport)
    {
        // No-op. We're always connected.
    }

    @Override
    public boolean isConnected()
    {
        return true;
    }

    public void setRelayIds(List<String> relayIds)
    {
        List<SocketAddress> remoteRelays = new ArrayList<>(relayIds.size());

        for (String relayId : relayIds)
        {
            SocketAddress socketAddress = relayIdToSocketAddress(relayId);
            if (socketAddress == null)
            {
                logger.error("xxx socket address null");
            }
            else if (remoteRelays.contains(socketAddress))
            {
                logger.error("xxx socket address already exists");
            }
            else
            {
                logger.error("xxx add relay socket address: "+ socketAddress);
                remoteRelays.add(socketAddress);
            }
        }

        this.remoteRelays = remoteRelays;
    }

    // Assume the ID is in the form of "address:port"
    private static SocketAddress relayIdToSocketAddress(String relayId)
    {
        if (relayId == null || !relayId.contains(":"))
        {
            classLogger.error("xxx invalid relay id: "+relayId);
            return null;
        }

        String[] addressAndPort = relayId.split(":");
        return new InetSocketAddress(addressAndPort[0], Integer.valueOf(addressAndPort[1]));
    }

    private void doSend(DatagramPacket p)
        throws IOException
    {
        //logger.error("xxx sending a packet, relays="+remoteRelays);
        SocketAddress originalAddress = p.getSocketAddress();
        //printPacket(p.getData(), p.getOffset(), p.getLength(), "xxx send before add: ");
        p = addOctoHeaders(p);
        //printPacket(p.getData(), p.getOffset(), p.getLength(), "xxx send after add: ");
        DatagramSocket relaySocket = octoRelay.getSocket();
        for (SocketAddress remoteAddress : remoteRelays)
        {
            //logger.error("xxx sending a packet to "+remoteAddress +" len="+p.getLength());
            p.setSocketAddress(remoteAddress);

            relaySocket.send(p);
        }

        p.setSocketAddress(originalAddress);
    }

    private DatagramPacket addOctoHeaders(DatagramPacket p)
    {
        byte[] buf = p.getData();
        int off = p.getOffset();
        int len = p.getLength();


        if (off >= OctoPacket.OCTO_HEADER_LENGTH)
        {
            off -= OctoPacket.OCTO_HEADER_LENGTH;
        }
        else if (buf.length >= len + OctoPacket.OCTO_HEADER_LENGTH)
        {
            System.arraycopy(buf, off, buf, OctoPacket.OCTO_HEADER_LENGTH, len);
            off = 0;
        }
        else
        {
            byte[] newBuf = new byte[len + OctoPacket.OCTO_HEADER_LENGTH];
            System.arraycopy(buf, off, newBuf, OctoPacket.OCTO_HEADER_LENGTH, len);

            buf = newBuf;
            off = 0;
        }


        len += OctoPacket.OCTO_HEADER_LENGTH;
        p.setData(buf, off, len);

        OctoPacket.writeHeaders(
            buf, off,
            true /* source is a relay */,
            channel.getMediaType(),
            0 /* simulcast layers info */,
            channel.getConferenceId(),
            /* todo: add source endpoint id */ "ffffffff");
        return p;
    }

    private void printPacket(byte[] buf, int off, int len, String x)
    {
        String s = x+" len="+len +" off="+off+": ";
        for (int i = off; i < off + len; i++)
        {
            s+= Integer.toHexString(buf[i] & 0xff);
        }
        logger.error(s);
    }
}
