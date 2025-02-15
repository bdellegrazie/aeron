/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver;

import io.aeron.CommonContext;
import io.aeron.driver.buffer.RawLog;
import io.aeron.driver.media.SendChannelEndpoint;
import io.aeron.driver.status.SystemCounters;
import io.aeron.logbuffer.LogBufferDescriptor;
import io.aeron.logbuffer.LogBufferUnblocker;
import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.protocol.RttMeasurementFlyweight;
import io.aeron.protocol.SetupFlyweight;
import io.aeron.protocol.StatusMessageFlyweight;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.Position;
import org.agrona.concurrent.status.ReadablePosition;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static io.aeron.driver.Configuration.PUBLICATION_HEARTBEAT_TIMEOUT_NS;
import static io.aeron.driver.Configuration.PUBLICATION_SETUP_TIMEOUT_NS;
import static io.aeron.driver.status.SystemCounterDescriptor.*;
import static io.aeron.logbuffer.LogBufferDescriptor.*;
import static io.aeron.logbuffer.TermScanner.*;
import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_AND_END_FLAGS;
import static io.aeron.protocol.DataHeaderFlyweight.BEGIN_END_AND_EOS_FLAGS;
import static org.agrona.BitUtil.SIZE_OF_LONG;

class NetworkPublicationPadding1
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7, p8;
}

class NetworkPublicationConductorFields extends NetworkPublicationPadding1
{
    protected static final ReadablePosition[] EMPTY_POSITIONS = new ReadablePosition[0];

    protected long cleanPosition;
    protected long timeOfLastActivityNs;
    protected long lastSenderPosition;
    protected int refCount = 0;
    protected ReadablePosition[] spyPositions = EMPTY_POSITIONS;
    protected final ArrayList<UntetheredSubscription> untetheredSubscriptions = new ArrayList<>();
}

class NetworkPublicationPadding2 extends NetworkPublicationConductorFields
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7, p8;
}

class NetworkPublicationSenderFields extends NetworkPublicationPadding2
{
    protected long timeOfLastSendOrHeartbeatNs;
    protected long timeOfLastSetupNs;
    protected long statusMessageDeadlineNs;
    protected boolean trackSenderLimits = false;
    protected boolean shouldSendSetupFrame = true;
}

class NetworkPublicationPadding3 extends NetworkPublicationSenderFields
{
    @SuppressWarnings("unused")
    protected long p1, p2, p3, p4, p5, p6, p7;
}

/**
 * Publication to be sent to connected subscribers.
 */
public class NetworkPublication
    extends NetworkPublicationPadding3
    implements RetransmitSender, DriverManagedResource, Subscribable
{
    enum State
    {
        ACTIVE, DRAINING, LINGER, CLOSING
    }

    private final long registrationId;
    private final long unblockTimeoutNs;
    private final long connectionTimeoutNs;
    private final long lingerTimeoutNs;
    private final long untetheredWindowLimitTimeoutNs;
    private final long untetheredRestingTimeoutNs;
    private final long tag;
    private final int positionBitsToShift;
    private final int initialTermId;
    private final int termBufferLength;
    private final int termLengthMask;
    private final int mtuLength;
    private final int termWindowLength;
    private final int sessionId;
    private final int streamId;
    private final boolean isExclusive;
    private final boolean spiesSimulateConnection;
    private final boolean signalEos;
    private volatile boolean hasReceivers;
    private volatile boolean hasSpies;
    private volatile boolean isConnected;
    private volatile boolean isEndOfStream;
    private volatile boolean hasSenderReleased;
    private State state = State.ACTIVE;

    private final UnsafeBuffer[] termBuffers;
    private final ByteBuffer[] sendBuffers;
    private final Position publisherPos;
    private final Position publisherLimit;
    private final Position senderPosition;
    private final Position senderLimit;
    private final SendChannelEndpoint channelEndpoint;
    private final ByteBuffer heartbeatBuffer;
    private final DataHeaderFlyweight heartbeatDataHeader;
    private final ByteBuffer setupBuffer;
    private final SetupFlyweight setupHeader;
    private final ByteBuffer rttMeasurementBuffer;
    private final RttMeasurementFlyweight rttMeasurementHeader;
    private final FlowControl flowControl;
    private final NanoClock nanoClock;
    private final RetransmitHandler retransmitHandler;
    private final UnsafeBuffer metaDataBuffer;
    private final RawLog rawLog;
    private final AtomicCounter heartbeatsSent;
    private final AtomicCounter retransmitsSent;
    private final AtomicCounter senderFlowControlLimits;
    private final AtomicCounter senderBpe;
    private final AtomicCounter shortSends;
    private final AtomicCounter unblockedPublications;

    public NetworkPublication(
        final long registrationId,
        final PublicationParams params,
        final SendChannelEndpoint channelEndpoint,
        final NanoClock nanoClock,
        final RawLog rawLog,
        final int termWindowLength,
        final Position publisherPos,
        final Position publisherLimit,
        final Position senderPosition,
        final Position senderLimit,
        final AtomicCounter senderBpe,
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final SystemCounters systemCounters,
        final FlowControl flowControl,
        final RetransmitHandler retransmitHandler,
        final NetworkPublicationThreadLocals threadLocals,
        final long unblockTimeoutNs,
        final long connectionTimeoutNs,
        final long untetheredWindowLimitTimeoutNs,
        final long untetheredRestingTimeoutNs,
        final boolean spiesSimulateConnection,
        final boolean isExclusive)
    {
        this.registrationId = registrationId;
        this.unblockTimeoutNs = unblockTimeoutNs;
        this.connectionTimeoutNs = connectionTimeoutNs;
        this.lingerTimeoutNs = params.lingerTimeoutNs;
        this.untetheredWindowLimitTimeoutNs = untetheredWindowLimitTimeoutNs;
        this.untetheredRestingTimeoutNs = untetheredRestingTimeoutNs;
        this.tag = params.entityTag;
        this.channelEndpoint = channelEndpoint;
        this.rawLog = rawLog;
        this.nanoClock = nanoClock;
        this.senderPosition = senderPosition;
        this.senderLimit = senderLimit;
        this.flowControl = flowControl;
        this.retransmitHandler = retransmitHandler;
        this.publisherPos = publisherPos;
        this.publisherLimit = publisherLimit;
        this.mtuLength = params.mtuLength;
        this.initialTermId = initialTermId;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.spiesSimulateConnection = spiesSimulateConnection;
        this.isExclusive = isExclusive;
        this.signalEos = params.signalEos;

        metaDataBuffer = rawLog.metaData();
        setupBuffer = threadLocals.setupBuffer();
        setupHeader = threadLocals.setupHeader();
        heartbeatBuffer = threadLocals.heartbeatBuffer();
        heartbeatDataHeader = threadLocals.heartbeatDataHeader();
        rttMeasurementBuffer = threadLocals.rttMeasurementBuffer();
        rttMeasurementHeader = threadLocals.rttMeasurementHeader();

        heartbeatsSent = systemCounters.get(HEARTBEATS_SENT);
        shortSends = systemCounters.get(SHORT_SENDS);
        retransmitsSent = systemCounters.get(RETRANSMITS_SENT);
        senderFlowControlLimits = systemCounters.get(SENDER_FLOW_CONTROL_LIMITS);
        unblockedPublications = systemCounters.get(UNBLOCKED_PUBLICATIONS);
        this.senderBpe = senderBpe;

        termBuffers = rawLog.termBuffers();
        sendBuffers = rawLog.sliceTerms();

        final int termLength = rawLog.termLength();
        termBufferLength = termLength;
        termLengthMask = termLength - 1;
        flowControl.initialize(initialTermId, termLength);

        final long nowNs = nanoClock.nanoTime();
        timeOfLastSendOrHeartbeatNs = nowNs - PUBLICATION_HEARTBEAT_TIMEOUT_NS - 1;
        timeOfLastSetupNs = nowNs - PUBLICATION_SETUP_TIMEOUT_NS - 1;
        statusMessageDeadlineNs = spiesSimulateConnection ? nowNs : (nowNs + connectionTimeoutNs);

        positionBitsToShift = LogBufferDescriptor.positionBitsToShift(termLength);
        this.termWindowLength = termWindowLength;

        lastSenderPosition = senderPosition.get();
        cleanPosition = lastSenderPosition;
        timeOfLastActivityNs = nowNs;
    }

    public boolean free()
    {
        return rawLog.free();
    }

    public void close()
    {
        publisherPos.close();
        publisherLimit.close();
        senderPosition.close();
        senderLimit.close();
        senderBpe.close();
        for (final ReadablePosition position : spyPositions)
        {
            position.close();
        }

        for (int i = 0, size = untetheredSubscriptions.size(); i < size; i++)
        {
            final UntetheredSubscription untetheredSubscription = untetheredSubscriptions.get(i);
            if (UntetheredSubscription.RESTING == untetheredSubscription.state)
            {
                untetheredSubscription.position.close();
            }
        }

        rawLog.close();
    }

    public long tag()
    {
        return tag;
    }

    public int termBufferLength()
    {
        return termBufferLength;
    }

    public int mtuLength()
    {
        return mtuLength;
    }

    public long registrationId()
    {
        return registrationId;
    }

    public boolean isExclusive()
    {
        return isExclusive;
    }

    public final int send(final long nowNs)
    {
        final long senderPosition = this.senderPosition.get();
        final int activeTermId = computeTermIdFromPosition(senderPosition, positionBitsToShift, initialTermId);
        final int termOffset = (int)senderPosition & termLengthMask;

        if (shouldSendSetupFrame)
        {
            setupMessageCheck(nowNs, activeTermId, termOffset);
        }

        int bytesSent = sendData(nowNs, senderPosition, termOffset);

        if (0 == bytesSent)
        {
            bytesSent = heartbeatMessageCheck(nowNs, activeTermId, termOffset, signalEos && isEndOfStream);

            if (spiesSimulateConnection && hasSpies && !hasReceivers)
            {
                final long newSenderPosition = maxSpyPosition(senderPosition);
                this.senderPosition.setOrdered(newSenderPosition);
                senderLimit.setOrdered(flowControl.onIdle(nowNs, newSenderPosition, newSenderPosition, isEndOfStream));
            }
            else
            {
                senderLimit.setOrdered(flowControl.onIdle(nowNs, senderLimit.get(), senderPosition, isEndOfStream));
            }
        }

        updateHasReceivers(nowNs);
        retransmitHandler.processTimeouts(nowNs, this);

        return bytesSent;
    }

    public SendChannelEndpoint channelEndpoint()
    {
        return channelEndpoint;
    }

    public String channel()
    {
        return channelEndpoint.originalUriString();
    }

    public int sessionId()
    {
        return sessionId;
    }

    public int streamId()
    {
        return streamId;
    }

    public void resend(final int termId, final int termOffset, final int length)
    {
        final long senderPosition = this.senderPosition.get();
        final long resendPosition = computePosition(termId, termOffset, positionBitsToShift, initialTermId);
        final long bottomResendWindow = senderPosition - (termBufferLength >> 1);

        if (bottomResendWindow <= resendPosition && resendPosition < senderPosition)
        {
            final int activeIndex = indexByPosition(resendPosition, positionBitsToShift);
            final UnsafeBuffer termBuffer = termBuffers[activeIndex];
            final ByteBuffer sendBuffer = sendBuffers[activeIndex];

            int remainingBytes = length;
            int bytesSent = 0;
            int offset = termOffset;
            do
            {
                offset += bytesSent;

                final long scanOutcome = scanForAvailability(termBuffer, offset, Math.min(mtuLength, remainingBytes));
                final int available = available(scanOutcome);
                if (available <= 0)
                {
                    break;
                }

                sendBuffer.limit(offset + available).position(offset);

                if (available != channelEndpoint.send(sendBuffer))
                {
                    shortSends.increment();
                    break;
                }

                bytesSent = available + padding(scanOutcome);
                remainingBytes -= bytesSent;
            }
            while (remainingBytes > 0);

            retransmitsSent.incrementOrdered();
        }
    }

    public void triggerSendSetupFrame()
    {
        if (!isEndOfStream)
        {
            shouldSendSetupFrame = true;
        }
    }

    public void addSubscriber(final SubscriptionLink subscriptionLink, final ReadablePosition position)
    {
        spyPositions = ArrayUtil.add(spyPositions, position);
        hasSpies = true;

        if (!subscriptionLink.isTether())
        {
            untetheredSubscriptions.add(new UntetheredSubscription(subscriptionLink, position, nanoClock.nanoTime()));
        }

        if (spiesSimulateConnection)
        {
            LogBufferDescriptor.isConnected(metaDataBuffer, true);
            isConnected = true;
        }
    }

    public void removeSubscriber(final SubscriptionLink subscriptionLink, final ReadablePosition position)
    {
        spyPositions = ArrayUtil.remove(spyPositions, position);
        hasSpies = spyPositions.length > 0;
        position.close();

        if (!subscriptionLink.isTether())
        {
            for (int lastIndex = untetheredSubscriptions.size() - 1, i = lastIndex; i >= 0; i--)
            {
                if (untetheredSubscriptions.get(i).subscriptionLink == subscriptionLink)
                {
                    ArrayListUtil.fastUnorderedRemove(untetheredSubscriptions, i, lastIndex);
                    break;
                }
            }
        }
    }

    public void onNak(final int termId, final int termOffset, final int length)
    {
        retransmitHandler.onNak(termId, termOffset, length, termBufferLength, this);
    }

    public void onStatusMessage(final StatusMessageFlyweight msg, final InetSocketAddress srcAddress)
    {
        if (!hasReceivers)
        {
            hasReceivers = true;
        }

        final long timeNs = nanoClock.nanoTime();
        statusMessageDeadlineNs = timeNs + connectionTimeoutNs;

        final long limit = flowControl.onStatusMessage(
            msg,
            srcAddress,
            senderLimit.get(),
            initialTermId,
            positionBitsToShift,
            timeNs);

        senderLimit.setOrdered(limit);

        if (!isConnected)
        {
            LogBufferDescriptor.isConnected(metaDataBuffer, true);
            isConnected = true;
        }
    }

    public void onRttMeasurement(
        final RttMeasurementFlyweight msg, @SuppressWarnings("unused") final InetSocketAddress srcAddress)
    {
        if (RttMeasurementFlyweight.REPLY_FLAG == (msg.flags() & RttMeasurementFlyweight.REPLY_FLAG))
        {
            rttMeasurementBuffer.clear();
            rttMeasurementHeader
                .receiverId(msg.receiverId())
                .echoTimestampNs(msg.echoTimestampNs())
                .receptionDelta(0)
                .sessionId(sessionId)
                .streamId(streamId)
                .flags((short)0x0);

            final int bytesSent = channelEndpoint.send(rttMeasurementBuffer);
            if (RttMeasurementFlyweight.HEADER_LENGTH != bytesSent)
            {
                shortSends.increment();
            }
        }

        // handling of RTT measurements would be done in an else clause here.
    }

    RawLog rawLog()
    {
        return rawLog;
    }

    int publisherLimitId()
    {
        return publisherLimit.id();
    }

    /**
     * Update the publishers limit for flow control as part of the conductor duty cycle.
     *
     * @return 1 if the limit has been updated otherwise 0.
     */
    final int updatePublisherLimit()
    {
        int workCount = 0;

        final long senderPosition = this.senderPosition.getVolatile();
        if (hasReceivers || (spiesSimulateConnection && spyPositions.length > 0))
        {
            long minConsumerPosition = senderPosition;
            for (final ReadablePosition spyPosition : spyPositions)
            {
                minConsumerPosition = Math.min(minConsumerPosition, spyPosition.getVolatile());
            }

            final long proposedPublisherLimit = minConsumerPosition + termWindowLength;
            final long publisherLimit = this.publisherLimit.get();
            if (proposedPublisherLimit > publisherLimit)
            {
                cleanBufferTo(minConsumerPosition - termBufferLength);
                this.publisherLimit.setOrdered(proposedPublisherLimit);
                workCount = 1;
            }
        }
        else if (publisherLimit.get() > senderPosition)
        {
            publisherLimit.setOrdered(senderPosition);
            workCount = 1;
        }

        return workCount;
    }

    boolean hasSpies()
    {
        return hasSpies;
    }

    final void updateHasReceivers(final long timeNs)
    {
        if ((statusMessageDeadlineNs - timeNs < 0) && hasReceivers)
        {
            hasReceivers = false;
        }
    }

    private int sendData(final long nowNs, final long senderPosition, final int termOffset)
    {
        int bytesSent = 0;
        final int availableWindow = (int)(senderLimit.get() - senderPosition);
        if (availableWindow > 0)
        {
            final int scanLimit = Math.min(availableWindow, mtuLength);
            final int activeIndex = indexByPosition(senderPosition, positionBitsToShift);

            final long scanOutcome = scanForAvailability(termBuffers[activeIndex], termOffset, scanLimit);
            final int available = available(scanOutcome);
            if (available > 0)
            {
                final ByteBuffer sendBuffer = sendBuffers[activeIndex];
                sendBuffer.limit(termOffset + available).position(termOffset);

                if (available == channelEndpoint.send(sendBuffer))
                {
                    timeOfLastSendOrHeartbeatNs = nowNs;
                    trackSenderLimits = true;

                    bytesSent = available;
                    this.senderPosition.setOrdered(senderPosition + bytesSent + padding(scanOutcome));
                }
                else
                {
                    shortSends.increment();
                }
            }
        }
        else if (trackSenderLimits)
        {
            trackSenderLimits = false;
            senderBpe.incrementOrdered();
            senderFlowControlLimits.incrementOrdered();
        }

        return bytesSent;
    }

    private void setupMessageCheck(final long nowNs, final int activeTermId, final int termOffset)
    {
        if ((timeOfLastSetupNs + PUBLICATION_SETUP_TIMEOUT_NS) - nowNs < 0)
        {
            timeOfLastSetupNs = nowNs;
            timeOfLastSendOrHeartbeatNs = nowNs;

            setupBuffer.clear();
            setupHeader
                .activeTermId(activeTermId)
                .termOffset(termOffset)
                .sessionId(sessionId)
                .streamId(streamId)
                .initialTermId(initialTermId)
                .termLength(termBufferLength)
                .mtuLength(mtuLength)
                .ttl(channelEndpoint.multicastTtl());

            if (SetupFlyweight.HEADER_LENGTH != channelEndpoint.send(setupBuffer))
            {
                shortSends.increment();
            }

            if (hasReceivers)
            {
                shouldSendSetupFrame = false;
            }
        }
    }

    private int heartbeatMessageCheck(
        final long nowNs, final int activeTermId, final int termOffset, final boolean signalEos)
    {
        int bytesSent = 0;

        if ((timeOfLastSendOrHeartbeatNs + PUBLICATION_HEARTBEAT_TIMEOUT_NS) - nowNs < 0)
        {
            heartbeatBuffer.clear();
            heartbeatDataHeader
                .sessionId(sessionId)
                .streamId(streamId)
                .termId(activeTermId)
                .termOffset(termOffset)
                .flags((byte)(signalEos ? BEGIN_END_AND_EOS_FLAGS : BEGIN_AND_END_FLAGS));

            bytesSent = channelEndpoint.send(heartbeatBuffer);
            if (DataHeaderFlyweight.HEADER_LENGTH != bytesSent)
            {
                shortSends.increment();
            }

            timeOfLastSendOrHeartbeatNs = nowNs;
            heartbeatsSent.incrementOrdered();
        }

        return bytesSent;
    }

    private void cleanBufferTo(final long position)
    {
        final long cleanPosition = this.cleanPosition;
        if (position > cleanPosition)
        {
            final UnsafeBuffer dirtyTerm = termBuffers[indexByPosition(cleanPosition, positionBitsToShift)];
            final int bytesForCleaning = (int)(position - cleanPosition);
            final int termOffset = (int)cleanPosition & termLengthMask;
            final int length = Math.min(bytesForCleaning, termBufferLength - termOffset);

            dirtyTerm.setMemory(termOffset + SIZE_OF_LONG, length - SIZE_OF_LONG, (byte)0);
            dirtyTerm.putLongOrdered(termOffset, 0);
            this.cleanPosition = cleanPosition + length;
        }
    }

    private void checkForBlockedPublisher(final long producerPosition, final long senderPosition, final long nowNs)
    {
        if (senderPosition == lastSenderPosition && isPossiblyBlocked(producerPosition, senderPosition))
        {
            if ((timeOfLastActivityNs + unblockTimeoutNs) - nowNs < 0)
            {
                if (LogBufferUnblocker.unblock(termBuffers, metaDataBuffer, senderPosition, termBufferLength))
                {
                    unblockedPublications.incrementOrdered();
                }
            }
        }
        else
        {
            timeOfLastActivityNs = nowNs;
            lastSenderPosition = senderPosition;
        }
    }

    private boolean isPossiblyBlocked(final long producerPosition, final long consumerPosition)
    {
        final int producerTermCount = activeTermCount(metaDataBuffer);
        final int expectedTermCount = (int)(consumerPosition >> positionBitsToShift);

        if (producerTermCount != expectedTermCount)
        {
            return true;
        }

        return producerPosition > consumerPosition;
    }

    private boolean spiesFinishedConsuming(final DriverConductor conductor, final long eosPosition)
    {
        if (spyPositions.length > 0)
        {
            for (final ReadablePosition spyPosition : spyPositions)
            {
                if (spyPosition.getVolatile() < eosPosition)
                {
                    return false;
                }
            }

            hasSpies = false;
            conductor.cleanupSpies(this);
        }

        return true;
    }

    private long maxSpyPosition(final long senderPosition)
    {
        long position = senderPosition;

        for (final ReadablePosition spyPosition : spyPositions)
        {
            position = Math.max(position, spyPosition.getVolatile());
        }

        return position;
    }

    private void updateConnectedStatus()
    {
        final boolean currentConnectedState = hasReceivers || (spiesSimulateConnection && spyPositions.length > 0);

        if (currentConnectedState != isConnected)
        {
            LogBufferDescriptor.isConnected(metaDataBuffer, currentConnectedState);
            isConnected = currentConnectedState;
        }
    }

    private void checkUntetheredSubscriptions(final long nowNs, final DriverConductor conductor)
    {
        final ArrayList<UntetheredSubscription> untetheredSubscriptions = this.untetheredSubscriptions;
        final int untetheredSubscriptionsSize = untetheredSubscriptions.size();
        if (0 == untetheredSubscriptionsSize)
        {
            return;
        }

        final long senderPosition = this.senderPosition.getVolatile();
        final long untetheredWindowLimit = (senderPosition - termWindowLength) + (termWindowLength >> 3);

        for (int lastIndex = untetheredSubscriptionsSize - 1, i = lastIndex; i >= 0; i--)
        {
            final UntetheredSubscription untethered = untetheredSubscriptions.get(i);
            switch (untethered.state)
            {
                case UntetheredSubscription.ACTIVE:
                    if (untethered.position.getVolatile() > untetheredWindowLimit)
                    {
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    else if ((untethered.timeOfLastUpdateNs + untetheredWindowLimitTimeoutNs) - nowNs <= 0)
                    {
                        conductor.notifyUnavailableImageLink(registrationId, untethered.subscriptionLink);
                        untethered.state = UntetheredSubscription.LINGER;
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    break;

                case UntetheredSubscription.LINGER:
                    if ((untethered.timeOfLastUpdateNs + untetheredWindowLimitTimeoutNs) - nowNs <= 0)
                    {
                        spyPositions = ArrayUtil.remove(spyPositions, untethered.position);
                        untethered.state = UntetheredSubscription.RESTING;
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    break;

                case UntetheredSubscription.RESTING:
                    if ((untethered.timeOfLastUpdateNs + untetheredRestingTimeoutNs) - nowNs <= 0)
                    {
                        spyPositions = ArrayUtil.add(spyPositions, untethered.position);
                        conductor.notifyAvailableImageLink(
                            registrationId,
                            sessionId,
                            untethered.subscriptionLink,
                            untethered.position.id(),
                            senderPosition,
                            rawLog.fileName(),
                            CommonContext.IPC_CHANNEL);
                        LogBufferDescriptor.isConnected(metaDataBuffer, true);
                        untethered.state = UntetheredSubscription.ACTIVE;
                        untethered.timeOfLastUpdateNs = nowNs;
                    }
                    break;
            }
        }
    }

    public void onTimeEvent(final long timeNs, final long timeMs, final DriverConductor conductor)
    {
        switch (state)
        {
            case ACTIVE:
            {
                checkUntetheredSubscriptions(timeNs, conductor);
                updateConnectedStatus();
                final long producerPosition = producerPosition();
                publisherPos.setOrdered(producerPosition);
                if (!isExclusive)
                {
                    checkForBlockedPublisher(producerPosition, senderPosition.getVolatile(), timeNs);
                }
                break;
            }

            case DRAINING:
            {
                final long producerPosition = producerPosition();
                publisherPos.setOrdered(producerPosition);
                final long senderPosition = this.senderPosition.getVolatile();
                if (producerPosition > senderPosition)
                {
                    if (LogBufferUnblocker.unblock(termBuffers, metaDataBuffer, senderPosition, termBufferLength))
                    {
                        unblockedPublications.incrementOrdered();
                        break;
                    }

                    if (hasReceivers)
                    {
                        break;
                    }
                }
                else
                {
                    isEndOfStream = true;
                }

                if (spiesFinishedConsuming(conductor, producerPosition))
                {
                    timeOfLastActivityNs = timeNs;
                    state = State.LINGER;
                }
                break;
            }

            case LINGER:
                if ((timeOfLastActivityNs + lingerTimeoutNs) - timeNs < 0)
                {
                    channelEndpoint.decRef();
                    conductor.cleanupPublication(this);
                    state = State.CLOSING;
                }
                break;
        }
    }

    public boolean hasReachedEndOfLife()
    {
        return hasSenderReleased;
    }

    public void decRef()
    {
        if (0 == --refCount)
        {
            state = State.DRAINING;
            timeOfLastActivityNs = nanoClock.nanoTime();

            final long producerPosition = producerPosition();
            publisherLimit.setOrdered(producerPosition);
            endOfStreamPosition(metaDataBuffer, producerPosition);

            if (senderPosition.getVolatile() >= producerPosition)
            {
                isEndOfStream = true;
            }
        }
    }

    public void incRef()
    {
        ++refCount;
    }

    final State state()
    {
        return state;
    }

    void senderRelease()
    {
        hasSenderReleased = true;
    }

    long producerPosition()
    {
        final long rawTail = rawTailVolatile(metaDataBuffer);
        final int termOffset = termOffset(rawTail, termBufferLength);

        return computePosition(termId(rawTail), termOffset, positionBitsToShift, initialTermId);
    }

    long consumerPosition()
    {
        return senderPosition.getVolatile();
    }
}
