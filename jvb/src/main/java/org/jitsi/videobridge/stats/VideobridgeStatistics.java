/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.stats;

import org.jetbrains.annotations.*;
import org.jitsi.metrics.*;
import org.jitsi.nlj.rtcp.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.health.*;
import org.jitsi.videobridge.load_management.*;
import org.jitsi.videobridge.metrics.*;
import org.jitsi.videobridge.relay.*;
import org.jitsi.videobridge.shutdown.*;
import org.jitsi.videobridge.transport.ice.*;
import org.jitsi.videobridge.xmpp.*;

import java.text.*;
import java.util.*;
import java.util.concurrent.locks.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

/**
 * Implements statistics that are collected by the Videobridge.
 *
 * @author Hristo Terezov
 * @author Lyubomir Marinov
 */
public class VideobridgeStatistics
    extends Statistics
{
    /**
     * The <tt>DateFormat</tt> to be utilized by <tt>VideobridgeStatistics</tt>
     * in order to represent time and date as <tt>String</tt>.
     */
    private final DateFormat timestampFormat;

    /**
     * The currently configured region.
     */
    private static final InfoMetric regionInfo = RelayConfig.config.getRegion() != null ?
            VideobridgeMetricsContainer.getInstance()
                .registerInfo(REGION, "The currently configured region.", RelayConfig.config.getRegion()) : null;

    private static final String relayId = RelayConfig.config.getEnabled() ? RelayConfig.config.getRelayId() : null;

    public static final String EPS_NO_MSG_TRANSPORT_AFTER_DELAY = "num_eps_no_msg_transport_after_delay";
    public static final String TOTAL_ICE_SUCCEEDED_RELAYED = "total_ice_succeeded_relayed";

    /**
     * Number of configured MUC clients.
     */
    public static final String MUC_CLIENTS_CONFIGURED = "muc_clients_configured";

    /**
     * Number of configured MUC clients that are connected to XMPP.
     */
    public static final String MUC_CLIENTS_CONNECTED = "muc_clients_connected";

    /**
     * Number of MUCs that are configured
     */
    public static final String MUCS_CONFIGURED = "mucs_configured";

    /**
     * Number of MUCs that are joined.
     */
    public static final String MUCS_JOINED = "mucs_joined";

    /**
     * Fraction of incoming packets that were lost.
     */
    public static final String INCOMING_LOSS = "incoming_loss";

    /**
     * Fraction of outgoing packets that were lost.
     */
    public static final String OUTGOING_LOSS = "outgoing_loss";

    /**
     * The name of the stat that tracks the total number of times our AIMDs have
     * expired the incoming bitrate (and which would otherwise result in video
     * suspension).
     */
    private static final String TOTAL_AIMD_BWE_EXPIRATIONS = "total_aimd_bwe_expirations";

    /**
     * Fraction of incoming and outgoing packets that were lost.
     */
    public static final String OVERALL_LOSS = "overall_loss";

    /**
     * The indicator which determines whether {@link #generate()} is executing
     * on this <tt>VideobridgeStatistics</tt>. If <tt>true</tt>, invocations of
     * <tt>generate()</tt> will do nothing. Introduced in order to mitigate an
     * issue in which a blocking in <tt>generate()</tt> will cause a multiple of
     * threads to be initialized and blocked.
     */
    private boolean inGenerate = false;

    private final @NotNull Videobridge videobridge;

    /**
     * Creates instance of <tt>VideobridgeStatistics</tt>.
     */
    public VideobridgeStatistics(@NotNull Videobridge videobridge)
    {
        this.videobridge = videobridge;

        timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        timestampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        // Is it necessary to set initial values for all of these?
        unlockedSetStat(TIMESTAMP, timestampFormat.format(new Date()));
        unlockedSetStat("healthy", JvbHealthChecker.Companion.getHealthyMetric().get());

        // Set these once, they won't change.
        unlockedSetStat(VERSION, videobridge.getVersion().toString());

        String releaseId = videobridge.getReleaseId();
        if (releaseId != null)
        {
            unlockedSetStat(RELEASE, releaseId);
        }
        if (regionInfo != null)
        {
            unlockedSetStat(REGION, regionInfo.get());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void generate()
    {
        // If a thread is already executing generate and has potentially
        // blocked, do not allow other threads to fall into the same trap.
        Lock lock = this.lock.writeLock();
        boolean inGenerate;

        lock.lock();
        try
        {
            if (this.inGenerate)
            {
                inGenerate = true;
            }
            else
            {
                // Enter the generate method.
                inGenerate = false;
                this.inGenerate = true;
            }
        }
        finally
        {
            lock.unlock();
        }
        if (!inGenerate)
        {
            try
            {
                generate0();
            }
            finally
            {
                // Exit the generate method.
                lock.lock();
                try
                {
                    this.inGenerate = false;
                }
                finally
                {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Generates/updates the statistics represented by this instance outside a
     * synchronized block.
     */
    @SuppressWarnings("unchecked")
    private void generate0()
    {
        Videobridge.Statistics jvbStats = videobridge.getStatistics();

        // Now that (the new values of) the statistics have been calculated and
        // the risks of the current thread hanging have been reduced as much as
        // possible, commit (the new values of) the statistics.
        Lock lock = this.lock.writeLock();

        lock.lock();
        try
        {
            unlockedSetStat(INCOMING_LOSS, VideobridgePeriodicMetrics.INSTANCE.getIncomingLoss().get());
            unlockedSetStat(OUTGOING_LOSS, VideobridgePeriodicMetrics.INSTANCE.getOutgoingLoss().get());
            unlockedSetStat(OVERALL_LOSS, VideobridgePeriodicMetrics.INSTANCE.getLoss().get());

            unlockedSetStat(
                    "endpoints_with_high_outgoing_loss",
                    VideobridgePeriodicMetrics.INSTANCE.getEndpointsWithHighOutgoingLoss().get());
            unlockedSetStat("local_active_endpoints", VideobridgePeriodicMetrics.INSTANCE.getActiveEndpoints().get());
            unlockedSetStat(BITRATE_DOWNLOAD, VideobridgePeriodicMetrics.INSTANCE.getIncomingBitrate().get() / 1000);
            unlockedSetStat(BITRATE_UPLOAD, VideobridgePeriodicMetrics.INSTANCE.getOutgoingBitrate().get() / 1000);
            unlockedSetStat(PACKET_RATE_DOWNLOAD, VideobridgePeriodicMetrics.INSTANCE.getIncomingPacketRate().get());
            unlockedSetStat(PACKET_RATE_UPLOAD, VideobridgePeriodicMetrics.INSTANCE.getOutgoingPacketRate().get());
            unlockedSetStat(RTT_AGGREGATE, VideobridgePeriodicMetrics.INSTANCE.getAverageRtt());
            unlockedSetStat(TOTAL_CONFERENCES_CREATED, jvbStats.conferencesCreated.get());
            unlockedSetStat(TOTAL_CONFERENCES_COMPLETED, jvbStats.conferencesCompleted.get());
            unlockedSetStat(TOTAL_ICE_FAILED, IceTransport.Companion.getIceFailed().get());
            unlockedSetStat(TOTAL_ICE_SUCCEEDED, IceTransport.Companion.getIceSucceeded().get());
            unlockedSetStat(TOTAL_ICE_SUCCEEDED_TCP, IceTransport.Companion.getIceSucceededTcp().get());
            unlockedSetStat(TOTAL_ICE_SUCCEEDED_RELAYED, IceTransport.Companion.getIceSucceededRelayed().get());
            unlockedSetStat(TOTAL_CONFERENCE_SECONDS, jvbStats.totalConferenceSeconds.get());
            unlockedSetStat(
                    TOTAL_LOSS_CONTROLLED_PARTICIPANT_SECONDS,
                    jvbStats.totalLossControlledParticipantMs.get() / 1000);
            unlockedSetStat(
                    TOTAL_LOSS_LIMITED_PARTICIPANT_SECONDS,
                    jvbStats.totalLossLimitedParticipantMs.get() / 1000);
            unlockedSetStat(
                    TOTAL_LOSS_DEGRADED_PARTICIPANT_SECONDS,
                   jvbStats.totalLossDegradedParticipantMs.get() / 1000);
            unlockedSetStat(TOTAL_PARTICIPANTS, jvbStats.totalEndpoints.get());
            unlockedSetStat("total_visitors", jvbStats.totalVisitors.get());
            unlockedSetStat(EPS_NO_MSG_TRANSPORT_AFTER_DELAY, jvbStats.numEndpointsNoMessageTransportAfterDelay.get());
            unlockedSetStat("total_relays", jvbStats.totalRelays.get());
            unlockedSetStat(
                "num_relays_no_msg_transport_after_delay",
                jvbStats.numRelaysNoMessageTransportAfterDelay.get()
            );
            unlockedSetStat("total_keyframes_received", jvbStats.keyframesReceived.get());
            unlockedSetStat("total_layering_changes_received", jvbStats.layeringChangesReceived.get());
            unlockedSetStat(
                "total_video_stream_milliseconds_received",
                jvbStats.totalVideoStreamMillisecondsReceived.get());
            unlockedSetStat("stress_level", jvbStats.stressLevel.get());
            unlockedSetStat("average_participant_stress", JvbLoadManager.Companion.getAverageParticipantStress());
            unlockedSetStat("num_eps_oversending", VideobridgePeriodicMetrics.INSTANCE.getEndpointsOversending().get());
            unlockedSetStat(CONFERENCES, jvbStats.currentConferences.get());
            unlockedSetStat(OCTO_CONFERENCES, VideobridgePeriodicMetrics.INSTANCE.getConferencesWithRelay().get());
            unlockedSetStat(INACTIVE_CONFERENCES, VideobridgePeriodicMetrics.INSTANCE.getConferencesInactive().get());
            unlockedSetStat(P2P_CONFERENCES, VideobridgePeriodicMetrics.INSTANCE.getConferencesP2p().get());
            unlockedSetStat("endpoints", VideobridgePeriodicMetrics.INSTANCE.getEndpoints().get());
            unlockedSetStat(PARTICIPANTS, VideobridgePeriodicMetrics.INSTANCE.getEndpoints().get());
            unlockedSetStat("visitors", jvbStats.currentVisitors.get());
            unlockedSetStat("local_endpoints", jvbStats.currentLocalEndpoints.get());
            unlockedSetStat(
                    RECEIVE_ONLY_ENDPOINTS,
                    VideobridgePeriodicMetrics.INSTANCE.getEndpointsReceiveOnly().get());
            unlockedSetStat(INACTIVE_ENDPOINTS, VideobridgePeriodicMetrics.INSTANCE.getEndpointsInactive().get());
            unlockedSetStat(OCTO_ENDPOINTS, VideobridgePeriodicMetrics.INSTANCE.getEndpointsRelayed().get());
            unlockedSetStat(
                    ENDPOINTS_SENDING_AUDIO,
                    VideobridgePeriodicMetrics.INSTANCE.getEndpointsSendingAudio().get());
            unlockedSetStat(
                    ENDPOINTS_SENDING_VIDEO,
                    VideobridgePeriodicMetrics.INSTANCE.getEndpointsSendingVideo().get());
            unlockedSetStat(VIDEO_CHANNELS, VideobridgePeriodicMetrics.INSTANCE.getEndpointsSendingVideo().get());
            unlockedSetStat(LARGEST_CONFERENCE, VideobridgePeriodicMetrics.INSTANCE.getLargestConference().get());
            unlockedSetStat(THREADS, ThreadsMetric.INSTANCE.getThreadCount().get());
            unlockedSetStat(SHUTDOWN_IN_PROGRESS, VideobridgeMetrics.INSTANCE.getGracefulShutdown().get());
            if (videobridge.getShutdownState() == ShutdownState.SHUTTING_DOWN)
            {
                unlockedSetStat("shutting_down", true);
            }
            unlockedSetStat(DRAIN, VideobridgeMetrics.INSTANCE.getDrainMode().get());
            unlockedSetStat(TOTAL_DATA_CHANNEL_MESSAGES_RECEIVED,
                            jvbStats.dataChannelMessagesReceived.get());
            unlockedSetStat(TOTAL_DATA_CHANNEL_MESSAGES_SENT,
                            jvbStats.dataChannelMessagesSent.get());
            unlockedSetStat(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_RECEIVED,
                            jvbStats.colibriWebSocketMessagesReceived.get());
            unlockedSetStat(TOTAL_COLIBRI_WEB_SOCKET_MESSAGES_SENT, jvbStats.colibriWebSocketMessagesSent.get());
            unlockedSetStat(TOTAL_BYTES_RECEIVED, jvbStats.totalBytesReceived.get());
            unlockedSetStat("dtls_failed_endpoints", jvbStats.endpointsDtlsFailed.get());
            unlockedSetStat(TOTAL_BYTES_SENT, jvbStats.totalBytesSent.get());
            unlockedSetStat(TOTAL_PACKETS_RECEIVED, jvbStats.packetsReceived.get());
            unlockedSetStat(TOTAL_PACKETS_SENT, jvbStats.packetsSent.get());
            unlockedSetStat("colibri2", true);
            unlockedSetStat(TOTAL_BYTES_RECEIVED_OCTO, jvbStats.totalRelayBytesReceived.get());
            unlockedSetStat(TOTAL_BYTES_SENT_OCTO, jvbStats.totalRelayBytesSent.get());
            unlockedSetStat(TOTAL_PACKETS_RECEIVED_OCTO, jvbStats.relayPacketsReceived.get());
            unlockedSetStat(TOTAL_PACKETS_SENT_OCTO, jvbStats.relayPacketsSent.get());
            unlockedSetStat(OCTO_RECEIVE_BITRATE, VideobridgePeriodicMetrics.INSTANCE.getRelayIncomingBitrate().get());
            unlockedSetStat(
                    OCTO_RECEIVE_PACKET_RATE,
                    VideobridgePeriodicMetrics.INSTANCE.getRelayIncomingPacketRate().get());
            unlockedSetStat(OCTO_SEND_BITRATE, VideobridgePeriodicMetrics.INSTANCE.getRelayOutgoingBitrate().get());
            unlockedSetStat(
                    OCTO_SEND_PACKET_RATE,
                    VideobridgePeriodicMetrics.INSTANCE.getRelayOutgoingPacketRate().get());
            unlockedSetStat(TOTAL_DOMINANT_SPEAKER_CHANGES, jvbStats.dominantSpeakerChanges.get());
            unlockedSetStat(
                    "endpoints_with_suspended_sources",
                    VideobridgePeriodicMetrics.INSTANCE.getEndpointsWithSuspendedSources().get());
            unlockedSetStat(TIMESTAMP, timestampFormat.format(new Date()));
            if (relayId != null)
            {
                unlockedSetStat(RELAY_ID, relayId);
            }
            unlockedSetStat(MUC_CLIENTS_CONFIGURED, XmppConnection.Companion.getMucClientsConfigured().get());
            unlockedSetStat(MUC_CLIENTS_CONNECTED, XmppConnection.Companion.getMucClientsConnected().get());
            unlockedSetStat(MUCS_CONFIGURED, XmppConnection.Companion.getMucsConfigured().get());
            unlockedSetStat(MUCS_JOINED, XmppConnection.Companion.getMucsJoined().get());
            unlockedSetStat("preemptive_kfr_sent", jvbStats.preemptiveKeyframeRequestsSent.get());
            unlockedSetStat("preemptive_kfr_suppressed", jvbStats.preemptiveKeyframeRequestsSuppressed.get());
            unlockedSetStat("endpoints_with_spurious_remb", RembHandler.Companion.endpointsWithSpuriousRemb());
            unlockedSetStat("healthy", JvbHealthChecker.Companion.getHealthyMetric().get());
            unlockedSetStat("endpoints_disconnected", EndpointConnectionStatusMonitor.endpointsDisconnected.get());
            unlockedSetStat("endpoints_reconnected", EndpointConnectionStatusMonitor.endpointsReconnected.get());
        }
        finally
        {
            lock.unlock();
        }
    }
}
