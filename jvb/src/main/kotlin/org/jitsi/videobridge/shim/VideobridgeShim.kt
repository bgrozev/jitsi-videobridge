/*
 * Copyright @ 2021 - Present, 8x8 Inc
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
package org.jitsi.videobridge.shim

import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.Videobridge.InGracefulShutdownException
import org.jitsi.videobridge.xmpp.XmppConnection.ColibriRequest
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.util.IQUtils
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.XMPPError

class VideobridgeShim(val videobridge: Videobridge) {

    /**
     * Handles a COLIBRI request asynchronously.
     */
    fun handleColibriRequest(request: ColibriRequest) {
        val conferenceIq = request.request
        val conference: Conference
        try {
            conference = videobridge.getOrCreateConference(conferenceIq)
        } catch (e: Videobridge.ConferenceNotFoundException) {
            request.callback.invoke(
                IQUtils.createError(
                    conferenceIq,
                    XMPPError.Condition.bad_request,
                    "Conference not found for ID: " + conferenceIq.id
                )
            )
            return
        } catch (e: InGracefulShutdownException) {
            request.callback.invoke(ColibriConferenceIQ.createGracefulShutdownErrorResponse(conferenceIq))
            return
        }

        // It is now the responsibility of Conference to send a response.
        conference.shim.enqueueColibriRequest(request)
    }

    fun handleColibriConferenceIQ(conferenceIq: ColibriConferenceIQ): IQ? {
        val conference: Conference
        try {
            conference = videobridge.getOrCreateConference(conferenceIq)
        } catch (e: Videobridge.ConferenceNotFoundException) {
            return IQUtils.createError(
                conferenceIq,
                XMPPError.Condition.bad_request,
                "Conference not found for ID: " + conferenceIq.id
            )
        } catch (e: InGracefulShutdownException) {
            return ColibriConferenceIQ.createGracefulShutdownErrorResponse(conferenceIq)
        }
        return conference.shim.handleColibriConferenceIQ(conferenceIq)
    }
}
