/*
 * Copyright @ 2015-2017 Atlassian Pty Ltd
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

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * A utility class which handles the on-the-wire Octo format. Octo encapsulates
 * its payload (RTP, RTCP, or anything else) in an 8-byte header:
 * <pre>{@code
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |R| M | S | res |              Conference ID                    |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Endpoint ID                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * }</pre>
 * R: Source-is-a-relay flag. 1 if the source of the packet is a relay, and
 * 0 if it is an endpoint (bridge).
 * <p/>
 * M: media type (audio, video, or data).
 * <p/>
 * S: Simulcast layer ID.
 *
 * @author Boris Grozev
 */
public class OctoPacket
{
    /**
     * The fixed length of the Octo header.
     */
    public static final int OCTO_HEADER_LENGTH = 8;

    /**
     * The integer which identifies the "audio" media type in Octo.
     */
    public static final int OCTO_MEDIA_TYPE_AUDIO = 0;

    /**
     * The integer which identifies the "video" media type in Octo.
     */
    public static final int OCTO_MEDIA_TYPE_VIDEO = 1;

    /**
     * The integer which identifies the "data" media type in Octo.
     */
    public static final int OCTO_MEDIA_TYPE_DATA = 2;

    public static int getMediaTypeId(MediaType mediaType)
    {
        switch (mediaType)
        {
        case AUDIO:
            return OCTO_MEDIA_TYPE_AUDIO;
        case VIDEO:
            return OCTO_MEDIA_TYPE_VIDEO;
        case DATA:
            return OCTO_MEDIA_TYPE_DATA;
        default:
            return -1;
        }
    }

    public static void writeHeaders(byte[] buf, int off,
                                    boolean r, MediaType mediaType, int s,
                                    String conferenceId,
                                    String endpointId)
    {
        buf[off] = 0;
        if (r) buf[off] |= 0x80;
        buf[off] |= (getMediaTypeId(mediaType) & 0x03) << 5;
        buf[off] |= (s & 0x03) << 3;
        writeConferenceId(conferenceId, buf, off, OCTO_HEADER_LENGTH);
        writeEndpointId(endpointId, buf, off, OCTO_HEADER_LENGTH);
    }

    public static String readConferenceId(byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        int cid = RTPUtils.readUint24AsInt(buf, off + 1);
        return Integer.toHexString(cid);
    }

    public static MediaType readMediaType(byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        int mediaType = buf[off] & 0x60 >> 5;
        switch (mediaType)
        {
        case OCTO_MEDIA_TYPE_AUDIO:
            return MediaType.AUDIO;
        case OCTO_MEDIA_TYPE_VIDEO:
            return MediaType.VIDEO;
        case OCTO_MEDIA_TYPE_DATA:
            return MediaType.DATA;
        default:
            return null;
        }
    }

    public static boolean readRflag(byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        return (buf[off] & 0x80) != 0;
    }

    public static String readEndpointId(byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        long eid = RTPUtils.readUint32AsLong(buf, off + 4);
        return Long.toHexString(eid);
    }

    public static void writeConferenceId(
            String conferenceId, byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        int cid = Integer.parseInt(conferenceId, 16);
        RTPUtils.writeUint24(buf, off + 1, cid);
    }

    public static void writeEndpointId(
        String endpointId, byte[] buf, int off, int len)
    {
        assertMinLen(buf, off, len);

        long eid = Long.parseLong(endpointId, 16);
        RTPUtils.writeInt(buf, off + 4, (int) eid);
    }

    private static void assertMinLen(byte[] buf, int off, int len)
    {
        if (!verifyMinLength(buf, off, len, OCTO_HEADER_LENGTH))
        {
            throw new IllegalArgumentException("Invalid Octo packet.");
        }
    }

    // TODO move to a util somewhere?
    private static boolean verifyMinLength(byte[] data, int off, int len, int minLen)
    {
        return data != null && off >= 0 && len >= minLen && minLen >= 0
            && off + len < data.length;
    }
}
