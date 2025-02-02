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
package io.aeron;

import io.aeron.exceptions.*;
import io.aeron.status.ChannelEndpointStatus;
import io.aeron.status.HeartbeatTimestamp;
import org.agrona.DirectBuffer;
import org.agrona.ManagedResource;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.*;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersManager;
import org.agrona.concurrent.status.CountersReader;
import org.agrona.concurrent.status.UnsafeBufferPosition;

import java.util.ArrayList;
import java.util.concurrent.locks.Lock;

import static io.aeron.Aeron.Configuration.IDLE_SLEEP_MS;
import static io.aeron.Aeron.Configuration.IDLE_SLEEP_NS;
import static io.aeron.status.HeartbeatTimestamp.CLIENT_HEARTBEAT_TYPE_ID;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Client conductor receives responses and notifications from Media Driver and acts on them in addition to forwarding
 * commands from the Client API to the Media Driver conductor.
 */
class ClientConductor implements Agent, DriverEventsListener
{
    private static final long NO_CORRELATION_ID = Aeron.NULL_VALUE;

    private final long resourceLingerDurationNs;
    private final long keepAliveIntervalNs;
    private final long driverTimeoutMs;
    private final long driverTimeoutNs;
    private final long interServiceTimeoutNs;
    private long timeOfLastKeepAliveNs;
    private long timeOfLastServiceNs;
    private boolean isClosed;
    private boolean isInCallback;
    private boolean isTerminating;
    private String stashedChannel;
    private RegistrationException driverException;

    private final Aeron.Context ctx;
    private final Aeron aeron;
    private final Lock clientLock;
    private final EpochClock epochClock;
    private final NanoClock nanoClock;
    private final IdleStrategy awaitingIdleStrategy;
    private final DriverEventsAdapter driverEventsAdapter;
    private final LogBuffersFactory logBuffersFactory;
    private final Long2ObjectHashMap<LogBuffers> logBuffersByIdMap = new Long2ObjectHashMap<>();
    private final Long2ObjectHashMap<Object> resourceByRegIdMap = new Long2ObjectHashMap<>();
    private final ArrayList<ManagedResource> lingeringResources = new ArrayList<>();
    private final LongHashSet asyncCommandIdSet = new LongHashSet();
    private final AvailableImageHandler defaultAvailableImageHandler;
    private final UnavailableImageHandler defaultUnavailableImageHandler;
    private final ArrayList<AvailableCounterHandler> availableCounterHandlers = new ArrayList<>();
    private final ArrayList<UnavailableCounterHandler> unavailableCounterHandlers = new ArrayList<>();
    private final ArrayList<Runnable> closeHandlers = new ArrayList<>();
    private final DriverProxy driverProxy;
    private final AgentInvoker driverAgentInvoker;
    private final UnsafeBuffer counterValuesBuffer;
    private final CountersReader countersReader;
    private AtomicCounter heartbeatTimestamp;

    ClientConductor(final Aeron.Context ctx, final Aeron aeron)
    {
        this.ctx = ctx;
        this.aeron = aeron;

        clientLock = ctx.clientLock();
        epochClock = ctx.epochClock();
        nanoClock = ctx.nanoClock();
        awaitingIdleStrategy = ctx.awaitingIdleStrategy();
        driverProxy = ctx.driverProxy();
        logBuffersFactory = ctx.logBuffersFactory();
        keepAliveIntervalNs = ctx.keepAliveIntervalNs();
        resourceLingerDurationNs = ctx.resourceLingerDurationNs();
        driverTimeoutMs = ctx.driverTimeoutMs();
        driverTimeoutNs = MILLISECONDS.toNanos(driverTimeoutMs);
        interServiceTimeoutNs = ctx.interServiceTimeoutNs();
        defaultAvailableImageHandler = ctx.availableImageHandler();
        defaultUnavailableImageHandler = ctx.unavailableImageHandler();
        driverEventsAdapter = new DriverEventsAdapter(ctx.toClientBuffer(), ctx.clientId(), this, asyncCommandIdSet);
        driverAgentInvoker = ctx.driverAgentInvoker();
        counterValuesBuffer = ctx.countersValuesBuffer();
        countersReader = new CountersReader(ctx.countersMetaDataBuffer(), ctx.countersValuesBuffer(), US_ASCII);

        if (null != ctx.availableCounterHandler())
        {
            availableCounterHandlers.add(ctx.availableCounterHandler());
        }

        if (null != ctx.unavailableCounterHandler())
        {
            unavailableCounterHandlers.add(ctx.unavailableCounterHandler());
        }

        if (null != ctx.closeHandler())
        {
            closeHandlers.add(ctx.closeHandler());
        }

        final long nowNs = nanoClock.nanoTime();
        timeOfLastKeepAliveNs = nowNs;
        timeOfLastServiceNs = nowNs;
    }

    public void onClose()
    {
        clientLock.lock();
        try
        {
            if (!isClosed)
            {
                isClosed = true;
                if (isTerminating)
                {
                    aeron.internalClose();
                }

                forceCloseResources();

                for (int i = closeHandlers.size() - 1; i >= 0; i--)
                {
                    try
                    {
                        closeHandlers.get(i).run();
                    }
                    catch (final Exception ex)
                    {
                        handleError(ex);
                    }
                }

                try
                {
                    if (isTerminating)
                    {
                        Thread.sleep(IDLE_SLEEP_MS);
                    }

                    Thread.sleep(NANOSECONDS.toMillis(ctx.closeLingerDurationNs()));
                }
                catch (final InterruptedException ignore)
                {
                    Thread.currentThread().interrupt();
                }

                for (int i = 0, size = lingeringResources.size(); i < size; i++)
                {
                    lingeringResources.get(i).delete();
                }

                driverProxy.clientClose();
                ctx.close();
            }
        }
        finally
        {
            clientLock.unlock();
        }
    }

    public int doWork()
    {
        int workCount = 0;

        if (clientLock.tryLock())
        {
            try
            {
                if (isTerminating)
                {
                    throw new AgentTerminationException();
                }

                workCount = service(NO_CORRELATION_ID);
            }
            finally
            {
                clientLock.unlock();
            }
        }

        return workCount;
    }

    public String roleName()
    {
        return "aeron-client-conductor";
    }

    boolean isClosed()
    {
        return isClosed;
    }

    boolean isTerminating()
    {
        return isTerminating;
    }

    public void onError(final long correlationId, final int codeValue, final ErrorCode errorCode, final String message)
    {
        driverException = new RegistrationException(correlationId, codeValue, errorCode, message);

        final Object resource = resourceByRegIdMap.get(correlationId);
        if (resource instanceof Subscription)
        {
            final Subscription subscription = (Subscription)resource;
            subscription.internalClose();
            resourceByRegIdMap.remove(correlationId);
        }
    }

    public void onAsyncError(
        final long correlationId, final int codeValue, final ErrorCode errorCode, final String message)
    {
        handleError(new RegistrationException(correlationId, codeValue, errorCode, message));
    }

    public void onChannelEndpointError(final int statusIndicatorId, final String message)
    {
        final Long2ObjectHashMap<Object>.ValueIterator iterator = resourceByRegIdMap.values().iterator();
        while (iterator.hasNext())
        {
            final Object resource = iterator.next();
            if (resource instanceof Subscription)
            {
                final Subscription subscription = (Subscription)resource;

                if (subscription.channelStatusId() == statusIndicatorId)
                {
                    handleError(new ChannelEndpointException(statusIndicatorId, message));
                    subscription.internalClose();
                    iterator.remove();
                }
            }
            else if (resource instanceof Publication)
            {
                final Publication publication = (Publication)resource;

                if (publication.channelStatusId() == statusIndicatorId)
                {
                    handleError(new ChannelEndpointException(statusIndicatorId, message));
                    publication.internalClose();
                    releaseLogBuffers(publication.logBuffers(), publication.originalRegistrationId());
                    iterator.remove();
                }
            }
        }
    }

    public void onNewPublication(
        final long correlationId,
        final long registrationId,
        final int streamId,
        final int sessionId,
        final int publicationLimitId,
        final int statusIndicatorId,
        final String logFileName)
    {
        final ConcurrentPublication publication = new ConcurrentPublication(
            this,
            stashedChannel,
            streamId,
            sessionId,
            new UnsafeBufferPosition(counterValuesBuffer, publicationLimitId),
            statusIndicatorId,
            logBuffers(registrationId, logFileName, stashedChannel),
            registrationId,
            correlationId);

        resourceByRegIdMap.put(correlationId, publication);
    }

    public void onNewExclusivePublication(
        final long correlationId,
        final long registrationId,
        final int streamId,
        final int sessionId,
        final int publicationLimitId,
        final int statusIndicatorId,
        final String logFileName)
    {
        final ExclusivePublication publication = new ExclusivePublication(
            this,
            stashedChannel,
            streamId,
            sessionId,
            new UnsafeBufferPosition(counterValuesBuffer, publicationLimitId),
            statusIndicatorId,
            logBuffers(registrationId, logFileName, stashedChannel),
            registrationId,
            correlationId);

        resourceByRegIdMap.put(correlationId, publication);
    }

    public void onNewSubscription(final long correlationId, final int statusIndicatorId)
    {
        final Subscription subscription = (Subscription)resourceByRegIdMap.get(correlationId);
        subscription.channelStatusId(statusIndicatorId);
    }

    public void onAvailableImage(
        final long correlationId,
        final int sessionId,
        final long subscriptionRegistrationId,
        final int subscriberPositionId,
        final String logFileName,
        final String sourceIdentity)
    {
        final Subscription subscription = (Subscription)resourceByRegIdMap.get(subscriptionRegistrationId);
        if (null != subscription)
        {
            final Image image = new Image(
                subscription,
                sessionId,
                new UnsafeBufferPosition(counterValuesBuffer, subscriberPositionId),
                logBuffers(correlationId, logFileName, subscription.channel()),
                ctx.errorHandler(),
                sourceIdentity,
                correlationId);

            final AvailableImageHandler handler = subscription.availableImageHandler();
            if (null != handler)
            {
                isInCallback = true;
                try
                {
                    handler.onAvailableImage(image);
                }
                catch (final Throwable ex)
                {
                    handleError(ex);
                }
                finally
                {
                    isInCallback = false;
                }
            }

            subscription.addImage(image);
        }
    }

    public void onUnavailableImage(final long correlationId, final long subscriptionRegistrationId)
    {
        final Subscription subscription = (Subscription)resourceByRegIdMap.get(subscriptionRegistrationId);
        if (null != subscription)
        {
            final Image image = subscription.removeImage(correlationId);
            if (null != image)
            {
                final UnavailableImageHandler handler = subscription.unavailableImageHandler();
                if (null != handler)
                {
                    isInCallback = true;
                    try
                    {
                        handler.onUnavailableImage(image);
                    }
                    catch (final Throwable ex)
                    {
                        handleError(ex);
                    }
                    finally
                    {
                        isInCallback = false;
                    }
                }
            }
        }
    }

    public void onNewCounter(final long correlationId, final int counterId)
    {
        resourceByRegIdMap.put(correlationId, new Counter(correlationId, this, counterValuesBuffer, counterId));
        onAvailableCounter(correlationId, counterId);
    }

    public void onAvailableCounter(final long registrationId, final int counterId)
    {
        for (int i = 0, size = availableCounterHandlers.size(); i < size; i++)
        {
            final AvailableCounterHandler handler = availableCounterHandlers.get(i);
            isInCallback = true;
            try
            {
                handler.onAvailableCounter(countersReader, registrationId, counterId);
            }
            catch (final Exception ex)
            {
                handleError(ex);
            }
            finally
            {
                isInCallback = false;
            }
        }
    }

    public void onUnavailableCounter(final long registrationId, final int counterId)
    {
        callUnavailableCounterHandlers(registrationId, counterId);
    }

    public void onClientTimeout()
    {
        if (!isClosed)
        {
            isTerminating = true;
            forceCloseResources();
            handleError(new ClientTimeoutException("client timeout from driver"));
        }
    }

    CountersReader countersReader()
    {
        return countersReader;
    }

    void handleError(final Throwable ex)
    {
        ctx.errorHandler().onError(ex);
    }

    ConcurrentPublication addPublication(final String channel, final int streamId)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            stashedChannel = channel;
            final long registrationId = driverProxy.addPublication(channel, streamId);
            awaitResponse(registrationId);

            return (ConcurrentPublication)resourceByRegIdMap.get(registrationId);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    ExclusivePublication addExclusivePublication(final String channel, final int streamId)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            stashedChannel = channel;
            final long registrationId = driverProxy.addExclusivePublication(channel, streamId);
            awaitResponse(registrationId);

            return (ExclusivePublication)resourceByRegIdMap.get(registrationId);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void releasePublication(final Publication publication)
    {
        clientLock.lock();
        try
        {
            if (!publication.isClosed())
            {
                ensureActive();
                ensureNotReentrant();

                publication.internalClose();
                if (publication == resourceByRegIdMap.remove(publication.registrationId()))
                {
                    releaseLogBuffers(publication.logBuffers(), publication.originalRegistrationId());
                    awaitResponse(driverProxy.removePublication(publication.registrationId()));
                }
            }
        }
        finally
        {
            clientLock.unlock();
        }
    }

    Subscription addSubscription(final String channel, final int streamId)
    {
        return addSubscription(channel, streamId, defaultAvailableImageHandler, defaultUnavailableImageHandler);
    }

    Subscription addSubscription(
        final String channel,
        final int streamId,
        final AvailableImageHandler availableImageHandler,
        final UnavailableImageHandler unavailableImageHandler)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            final long correlationId = driverProxy.addSubscription(channel, streamId);
            final Subscription subscription = new Subscription(
                this,
                channel,
                streamId,
                correlationId,
                availableImageHandler,
                unavailableImageHandler);

            resourceByRegIdMap.put(correlationId, subscription);
            awaitResponse(correlationId);

            return subscription;
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void releaseSubscription(final Subscription subscription)
    {
        clientLock.lock();
        try
        {
            if (!subscription.isClosed())
            {
                ensureActive();
                ensureNotReentrant();

                subscription.internalClose();
                final long registrationId = subscription.registrationId();
                resourceByRegIdMap.remove(registrationId);
                awaitResponse(driverProxy.removeSubscription(registrationId));
            }
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void addDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            awaitResponse(driverProxy.addDestination(registrationId, endpointChannel));
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void removeDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            awaitResponse(driverProxy.removeDestination(registrationId, endpointChannel));
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void addRcvDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            awaitResponse(driverProxy.addRcvDestination(registrationId, endpointChannel));
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void removeRcvDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            awaitResponse(driverProxy.removeRcvDestination(registrationId, endpointChannel));
        }
        finally
        {
            clientLock.unlock();
        }
    }

    long asyncAddDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            final long correlationId = driverProxy.addDestination(registrationId, endpointChannel);
            asyncCommandIdSet.add(correlationId);
            return correlationId;
        }
        finally
        {
            clientLock.unlock();
        }
    }

    long asyncRemoveDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            final long correlationId = driverProxy.removeDestination(registrationId, endpointChannel);
            asyncCommandIdSet.add(correlationId);
            return correlationId;
        }
        finally
        {
            clientLock.unlock();
        }
    }

    long asyncAddRcvDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            final long correlationId = driverProxy.addRcvDestination(registrationId, endpointChannel);
            asyncCommandIdSet.add(correlationId);
            return correlationId;
        }
        finally
        {
            clientLock.unlock();
        }
    }

    long asyncRemoveRcvDestination(final long registrationId, final String endpointChannel)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            final long correlationId = driverProxy.removeRcvDestination(registrationId, endpointChannel);
            asyncCommandIdSet.add(correlationId);
            return correlationId;
        }
        finally
        {
            clientLock.unlock();
        }
    }

    boolean isCommandActive(final long correlationId)
    {
        clientLock.lock();
        try
        {
            ensureActive();

            return asyncCommandIdSet.contains(correlationId);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    Counter addCounter(
        final int typeId,
        final DirectBuffer keyBuffer,
        final int keyOffset,
        final int keyLength,
        final DirectBuffer labelBuffer,
        final int labelOffset,
        final int labelLength)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            if (keyLength < 0 || keyLength > CountersManager.MAX_KEY_LENGTH)
            {
                throw new IllegalArgumentException("key length out of bounds: " + keyLength);
            }

            if (labelLength < 0 || labelLength > CountersManager.MAX_LABEL_LENGTH)
            {
                throw new IllegalArgumentException("label length out of bounds: " + labelLength);
            }

            final long registrationId = driverProxy.addCounter(
                typeId, keyBuffer, keyOffset, keyLength, labelBuffer, labelOffset, labelLength);

            awaitResponse(registrationId);

            return (Counter)resourceByRegIdMap.get(registrationId);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    Counter addCounter(final int typeId, final String label)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            if (label.length() > CountersManager.MAX_LABEL_LENGTH)
            {
                throw new IllegalArgumentException("label length exceeds MAX_LABEL_LENGTH: " + label.length());
            }

            final long registrationId = driverProxy.addCounter(typeId, label);
            awaitResponse(registrationId);

            return (Counter)resourceByRegIdMap.get(registrationId);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void addAvailableCounterHandler(final AvailableCounterHandler handler)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();
            availableCounterHandlers.add(handler);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    boolean removeAvailableCounterHandler(final AvailableCounterHandler handler)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();
            return availableCounterHandlers.remove(handler);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void addUnavailableCounterHandler(final UnavailableCounterHandler handler)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();
            unavailableCounterHandlers.add(handler);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    boolean removeUnavailableCounterHandler(final UnavailableCounterHandler handler)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();
            return unavailableCounterHandlers.remove(handler);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void addCloseHandler(final Runnable handler)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();
            closeHandlers.add(handler);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    boolean removeCloserHandler(final Runnable handler)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();
            return closeHandlers.remove(handler);
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void releaseCounter(final Counter counter)
    {
        clientLock.lock();
        try
        {
            ensureActive();
            ensureNotReentrant();

            final long registrationId = counter.registrationId();
            if (null != resourceByRegIdMap.remove(registrationId))
            {
                awaitResponse(driverProxy.removeCounter(registrationId));
            }
        }
        finally
        {
            clientLock.unlock();
        }
    }

    void releaseLogBuffers(final LogBuffers logBuffers, final long registrationId)
    {
        if (logBuffers.decRef() == 0)
        {
            logBuffers.timeOfLastStateChange(nanoClock.nanoTime());
            logBuffersByIdMap.remove(registrationId);
            lingeringResources.add(logBuffers);
        }
    }

    DriverEventsAdapter driverListenerAdapter()
    {
        return driverEventsAdapter;
    }

    long channelStatus(final int channelStatusId)
    {
        switch (channelStatusId)
        {
            case 0:
                return ChannelEndpointStatus.INITIALIZING;

            case ChannelEndpointStatus.NO_ID_ALLOCATED:
                return ChannelEndpointStatus.ACTIVE;

            default:
                return countersReader.getCounterValue(channelStatusId);
        }
    }

    void closeImages(final Image[] images, final UnavailableImageHandler unavailableImageHandler)
    {
        for (final Image image : images)
        {
            image.close();
            releaseLogBuffers(image.logBuffers(), image.correlationId());
        }

        if (null != unavailableImageHandler)
        {
            for (final Image image : images)
            {
                isInCallback = true;
                try
                {
                    unavailableImageHandler.onUnavailableImage(image);
                }
                catch (final Throwable ex)
                {
                    handleError(ex);
                }
                finally
                {
                    isInCallback = false;
                }
            }
        }
    }

    private void ensureActive()
    {
        if (isClosed)
        {
            throw new AeronException("Aeron client is closed");
        }

        if (isTerminating)
        {
            throw new AeronException("Aeron client is terminating");
        }
    }

    private void ensureNotReentrant()
    {
        if (isInCallback)
        {
            throw new AeronException("reentrant calls not permitted during callbacks");
        }
    }

    private LogBuffers logBuffers(final long registrationId, final String logFileName, final String channel)
    {
        LogBuffers logBuffers = logBuffersByIdMap.get(registrationId);
        if (null == logBuffers)
        {
            logBuffers = logBuffersFactory.map(logFileName);

            if (ctx.preTouchMappedMemory() && !channel.contains("sparse=true"))
            {
                logBuffers.preTouch();
            }

            logBuffersByIdMap.put(registrationId, logBuffers);
        }

        logBuffers.incRef();

        return logBuffers;
    }

    private int service(final long correlationId)
    {
        int workCount = 0;

        try
        {
            workCount += onCheckTimeouts();
            workCount += driverEventsAdapter.receive(correlationId);
        }
        catch (final Throwable throwable)
        {
            handleError(throwable);

            if (driverEventsAdapter.isInvalid())
            {
                onClose();
            }

            if (isClientApiCall(correlationId))
            {
                throw throwable;
            }
        }

        return workCount;
    }

    private static boolean isClientApiCall(final long correlationId)
    {
        return correlationId != NO_CORRELATION_ID;
    }

    private void awaitResponse(final long correlationId)
    {
        final long deadlineNs = nanoClock.nanoTime() + driverTimeoutNs;

        awaitingIdleStrategy.reset();
        do
        {
            if (null == driverAgentInvoker)
            {
                awaitingIdleStrategy.idle();
            }
            else
            {
                driverAgentInvoker.invoke();
            }

            service(correlationId);

            if (driverEventsAdapter.receivedCorrelationId() == correlationId)
            {
                final RegistrationException ex = driverException;
                if (null != ex)
                {
                    driverException = null;
                    throw ex;
                }

                return;
            }

            if (Thread.currentThread().isInterrupted())
            {
                isTerminating = true;
                throw new AgentTerminationException("thread interrupted");
            }
        }
        while (deadlineNs - nanoClock.nanoTime() > 0);

        throw new DriverTimeoutException("no response from MediaDriver within (ns): " + driverTimeoutNs);
    }

    private int onCheckTimeouts()
    {
        int workCount = 0;
        final long nowNs = nanoClock.nanoTime();

        if ((timeOfLastServiceNs + IDLE_SLEEP_NS) - nowNs < 0)
        {
            checkServiceInterval(nowNs);
            timeOfLastServiceNs = nowNs;

            workCount += checkLiveness(nowNs);
            workCount += checkLingeringResources(nowNs);
        }

        return workCount;
    }

    private void checkServiceInterval(final long nowNs)
    {
        if ((timeOfLastServiceNs + interServiceTimeoutNs) - nowNs < 0)
        {
            isTerminating = true;
            forceCloseResources();

            final long serviceIntervalNs = nowNs - timeOfLastServiceNs;

            throw new ConductorServiceTimeoutException(
                "service interval exceeded (ns): timeout=" + interServiceTimeoutNs + ", actual=" + serviceIntervalNs);
        }
    }

    private int checkLiveness(final long nowNs)
    {
        if ((timeOfLastKeepAliveNs + keepAliveIntervalNs) - nowNs < 0)
        {
            final long lastKeepAliveMs = driverProxy.timeOfLastDriverKeepaliveMs();
            final long nowMs = epochClock.time();

            if (nowMs > (lastKeepAliveMs + driverTimeoutMs))
            {
                isTerminating = true;
                forceCloseResources();

                final long keepAliveAgeMs = nowMs - lastKeepAliveMs;

                throw new DriverTimeoutException(
                    "MediaDriver keepalive age exceeded (ms): timeout= " +
                     driverTimeoutMs + ", actual=" + keepAliveAgeMs);
            }

            final long clientId = driverProxy.clientId();
            if (null == heartbeatTimestamp)
            {
                final int counterId = HeartbeatTimestamp.findCounterIdByRegistrationId(
                    countersReader, CLIENT_HEARTBEAT_TYPE_ID, clientId);

                if (counterId != CountersReader.NULL_COUNTER_ID)
                {
                    heartbeatTimestamp = new AtomicCounter(counterValuesBuffer, counterId);
                    heartbeatTimestamp.setOrdered(nowMs);
                    timeOfLastKeepAliveNs = nowNs;
                }
            }
            else
            {
                final int counterId = heartbeatTimestamp.id();
                if (!HeartbeatTimestamp.isActive(countersReader, counterId, CLIENT_HEARTBEAT_TYPE_ID, clientId))
                {
                    isTerminating = true;
                    forceCloseResources();

                    throw new AeronException("unexpected close of heartbeat timestamp counter: " + counterId);
                }

                heartbeatTimestamp.setOrdered(nowMs);
                timeOfLastKeepAliveNs = nowNs;
            }

            return 1;
        }

        return 0;
    }

    private int checkLingeringResources(final long nowNs)
    {
        int workCount = 0;

        final ArrayList<ManagedResource> lingeringResources = this.lingeringResources;
        for (int lastIndex = lingeringResources.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ManagedResource resource = lingeringResources.get(i);
            if ((resource.timeOfLastStateChange() + resourceLingerDurationNs) - nowNs < 0)
            {
                ArrayListUtil.fastUnorderedRemove(lingeringResources, i, lastIndex--);
                resource.delete();
                workCount += 1;
            }
        }

        return workCount;
    }

    private void forceCloseResources()
    {
        for (final Object resource : resourceByRegIdMap.values())
        {
            if (resource instanceof Subscription)
            {
                final Subscription subscription = (Subscription)resource;
                subscription.internalClose();
            }
            else if (resource instanceof Publication)
            {
                final Publication publication = (Publication)resource;
                publication.internalClose();
                releaseLogBuffers(publication.logBuffers(), publication.originalRegistrationId());
            }
            else if (resource instanceof Counter)
            {
                final Counter counter = (Counter)resource;
                counter.internalClose();
                callUnavailableCounterHandlers(counter.registrationId(), counter.id());
            }
        }

        resourceByRegIdMap.clear();
    }

    private void callUnavailableCounterHandlers(final long registrationId, final int counterId)
    {
        for (int i = 0, size = unavailableCounterHandlers.size(); i < size; i++)
        {
            final UnavailableCounterHandler handler = unavailableCounterHandlers.get(i);
            isInCallback = true;
            try
            {
                handler.onUnavailableCounter(countersReader, registrationId, counterId);
            }
            catch (final Exception ex)
            {
                handleError(ex);
            }
            finally
            {
                isInCallback = false;
            }
        }
    }
}
