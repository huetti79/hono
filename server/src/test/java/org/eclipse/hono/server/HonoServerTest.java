/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.server;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.qpid.proton.amqp.transport.Target;
import org.eclipse.hono.authorization.AuthorizationConstants;
import org.eclipse.hono.authorization.Permission;
import org.eclipse.hono.telemetry.TelemetryConstants;
import org.eclipse.hono.util.Constants;
import org.eclipse.hono.util.ResourceIdentifier;
import org.eclipse.hono.util.TestSupport;
import org.junit.Test;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonReceiver;

/**
 * Unit tests for Hono Server.
 *
 */
public class HonoServerTest {

    private static final String BIND_ADDRESS = InetAddress.getLoopbackAddress().getHostAddress();

    private static HonoServer createServer(final Endpoint telemetryEndpoint) {
        HonoServer result = new HonoServer(BIND_ADDRESS, 0, false);
        if (telemetryEndpoint != null) {
            result.addEndpoint(telemetryEndpoint);
        }
        return result;
    }

    @Test
    public void testStartFailsIfTelemetryEndpointIsNotConfigured() {

        // GIVEN a Hono server without a telemetry endpoint configured
        HonoServer server = createServer(null);
        server.init(mock(Vertx.class), mock(Context.class));

        // WHEN starting the server
        Future<Void> startupFuture = Future.future();
        server.start(startupFuture);
        assertTrue(startupFuture.failed());
    }

    @Test
    public void testHandleReceiverOpenForwardsToTelemetryEndpoint() throws InterruptedException {

        // GIVEN a server with a telemetry endpoint
        final String targetAddress = TelemetryConstants.NODE_ADDRESS_TELEMETRY_PREFIX + Constants.DEFAULT_TENANT;
        final EventBus eventBus = mock(EventBus.class);
        final Vertx vertx = mock(Vertx.class);
        when(vertx.eventBus()).thenReturn(eventBus);
        final CountDownLatch linkEstablished = new CountDownLatch(1);
        final Endpoint telemetryEndpoint = new BaseEndpoint(vertx) {

            @Override
            public String getName() {
                return TelemetryConstants.TELEMETRY_ENDPOINT;
            }

            @Override
            public void onLinkAttach(final ProtonReceiver receiver, final ResourceIdentifier targetResource) {
                linkEstablished.countDown();
            }
        };
        HonoServer server = createServer(telemetryEndpoint);
        server.init(vertx, mock(Context.class));
        final JsonObject authMsg = AuthorizationConstants.getAuthorizationMsg(Constants.DEFAULT_SUBJECT, targetAddress, Permission.WRITE.toString());
        TestSupport.expectReplyForMessage(eventBus, server.getAuthServiceAddress(), authMsg, AuthorizationConstants.ALLOWED);

        // WHEN a client connects to the server using a telemetry address
        final Target target = getTarget(targetAddress);
        final ProtonReceiver receiver = mock(ProtonReceiver.class);
        when(receiver.getRemoteTarget()).thenReturn(target);
        server.handleReceiverOpen(mock(ProtonConnection.class), receiver);

        // THEN the server delegates link establishment to the telemetry endpoint 
        assertTrue(linkEstablished.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testHandleReceiverOpenRejectsUnauthorizedClient() throws InterruptedException {

        // GIVEN a server with a telemetry endpoint
        final String restrictedTargetAddress = TelemetryConstants.NODE_ADDRESS_TELEMETRY_PREFIX + "RESTRICTED_TENANT";
        final EventBus eventBus = mock(EventBus.class);
        final Vertx vertx = mock(Vertx.class);
        when(vertx.eventBus()).thenReturn(eventBus);

        final Endpoint telemetryEndpoint = mock(Endpoint.class);
        when(telemetryEndpoint.getName()).thenReturn(TelemetryConstants.TELEMETRY_ENDPOINT);
        HonoServer server = createServer(telemetryEndpoint);
        server.init(vertx, mock(Context.class));
        final JsonObject authMsg = AuthorizationConstants.getAuthorizationMsg(Constants.DEFAULT_SUBJECT, restrictedTargetAddress, Permission.WRITE.toString());
        TestSupport.expectReplyForMessage(eventBus, server.getAuthServiceAddress(), authMsg, AuthorizationConstants.DENIED);

        // WHEN a client connects to the server using a telemetry address for a tenant it is not authorized to write to
        final CountDownLatch linkClosed = new CountDownLatch(1);
        final Target target = getTarget(restrictedTargetAddress);
        final ProtonReceiver receiver = mock(ProtonReceiver.class);
        when(receiver.getRemoteTarget()).thenReturn(target);
        when(receiver.setCondition(any())).thenReturn(receiver);
        when(receiver.close()).thenAnswer(invocation -> {
            linkClosed.countDown();
            return receiver;
        });
        server.handleReceiverOpen(mock(ProtonConnection.class), receiver);

        // THEN the server closes the link with the client
        assertTrue(linkClosed.await(1, TimeUnit.SECONDS));
    }

    private Target getTarget(final String targetAddress) {
        Target result = mock(Target.class);
        when(result.getAddress()).thenReturn(targetAddress);
        return result;
    }
}
