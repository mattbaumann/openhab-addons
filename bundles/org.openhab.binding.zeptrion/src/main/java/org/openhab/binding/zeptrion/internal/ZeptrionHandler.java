/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.zeptrion.internal;

import static org.openhab.binding.zeptrion.internal.ZeptrionBindingConstants.*;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.openhab.core.cache.ExpiringCache;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link ZeptrionHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Matthias Baumann - Initial contribution
 */
@NonNullByDefault
public final class ZeptrionHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(ZeptrionHandler.class);

    private ZeptrionConfiguration config = new ZeptrionConfiguration();
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    private final ExpiringCache<ChannelScan> cache = new ExpiringCache<>(Duration.of(5, ChronoUnit.SECONDS),
            this::getReport);

    public ZeptrionHandler(Thing thing, HttpClient client) {
        super(thing);
        httpClient = client;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            pollDevice();
            return;
        }
        try {
            if (CHANNEL_1.equals(channelUID.getId())) {
                sendApiRequest(HttpMethod.POST, "", "cmd=" + (command == OnOffType.ON ? "on" : "off"), null);
                scheduler.schedule(this::pollDevice, 500, TimeUnit.MILLISECONDS);
            }
            if (CHANNEL_2.equals(channelUID.getId())) {
                sendApiRequest(HttpMethod.POST, "", "cmd=" + (command == OnOffType.ON ? "on" : "off"), null);
                scheduler.schedule(this::pollDevice, 500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            updateStatus(ThingStatus.OFFLINE);
        }
    }

    @Override
    public void initialize() {
        config = getConfigAs(ZeptrionConfiguration.class);

        // TODO: Initialize the handler.
        // The framework requires you to return from this method quickly, i.e. any network access must be done in
        // the background initialization below.
        // Also, before leaving this method a thing status from one of ONLINE, OFFLINE or UNKNOWN must be set. This
        // might already be the real thing status in case you can decide it directly.
        // In case you can not decide the thing status directly (e.g. for long running connection handshake using WAN
        // access or similar) you should set status UNKNOWN here and then decide the real status asynchronously in the
        // background.

        // set the thing status to UNKNOWN temporarily and let the background task decide for the real status.
        // the framework is then able to reuse the resources from the thing handler initialization.
        // we set this upfront to reliably check status updates in unit tests.
        updateStatus(ThingStatus.UNKNOWN);

        // Example for background initialization:
        scheduler.execute(() -> {
            boolean thingReachable = true; // <background task with long running initialization here>
            // when done do:
            if (thingReachable) {
                updateStatus(ThingStatus.ONLINE);
            } else {
                updateStatus(ThingStatus.OFFLINE);
            }
        });

        // These logging types should be primarily used by bindings
        // logger.trace("Example trace message");
        // logger.debug("Example debug message");
        // logger.warn("Example warn message");
        //
        // Logging to INFO should be avoided normally.
        // See https://www.openhab.org/docs/developer/guidelines.html#f-logging

        // Note: When initialization can NOT be done set the status with more details for further
        // analysis. See also class ThingStatusDetail for all available status details.
        // Add a description to give user information to understand why thing does not work as expected. E.g.
        // updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
        // "Can not access device as username and/or password are invalid");
    }

    private void pollDevice() {
        ChannelScan scan = cache.getValue();
        if (scan != null) {
            updateState(CHANNEL_1, scan.ch1.val > 0 ? OnOffType.ON : OnOffType.OFF);
            updateState(CHANNEL_2, scan.ch2.val > 0 ? OnOffType.ON : OnOffType.OFF);
        }
    }

    private @Nullable ChannelScan getReport() {
        try {
            var report = sendApiRequest(HttpMethod.GET, "", null,
                    (@Nullable Class<@Nullable ChannelScan>) ChannelScan.class);
            updateStatus(ThingStatus.ONLINE);
            return report;
        } catch (InterruptedException | ExecutionException | TimeoutException ex) {
            updateStatus(ThingStatus.OFFLINE);
            logger.error("Get Report set the device to offline", ex);
            return null;
        }
    }

    private <@Nullable T> T sendApiRequest(HttpMethod method, String path, @Nullable String data,
            @Nullable Class<T> clazz) throws InterruptedException, ExecutionException, TimeoutException {
        String url = config.hostname + path;
        Request request = httpClient.newRequest(url).timeout(10, TimeUnit.SECONDS).method(method);
        if (data != null) {
            request = request.content(new StringContentProvider(data)).header(HttpHeader.CONTENT_TYPE,
                    "application/x-www-form-urlencoded");
        }
        ContentResponse response = request.send();
        if (response.getStatus() != 200) {
            throw new IllegalStateException("Cannot process response");
        }
        if (clazz != null) {
            return gson.fromJson(response.getContentAsString(), clazz);
        }
        return null;
    }

    private static class ChannelScan {

        public Channel ch1;
        public Channel ch2;

        private ChannelScan() {
            ch1 = new Channel();
            ch2 = new Channel();
        }
    }

    private static class Channel {
        public int val;
    }
}
