/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.test.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;
import org.hamcrest.TypeSafeMatcher;

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import io.kroxylicious.test.Request;
import io.kroxylicious.test.codec.DecodedRequestFrame;
import io.kroxylicious.test.codec.DecodedResponseFrame;

/**
 * MockHandler is responsible for:
 * <ol>
 *     <li>Serves a single response for any requests it receives. The response can be modified
 *  * using setResponse.</li>
 *     <li>Records requests it receives so they can be retrieved and verified</li>
 *     <li>Can be cleared, making it forget received requests</li>
 * </ol>
 */
@Sharable
public class MockHandler extends ChannelInboundHandlerAdapter {

    private record ConditionalMockResponse(Matcher<Request> matcher, ApiMessage message, AtomicLong invocations) {

    }

    private final List<ConditionalMockResponse> conditionalMockResponses = new ArrayList<>();

    private final List<DecodedRequestFrame<?>> requests = new ArrayList<>();

    /**
     * Create mockhandler with initial message to serve
     * @param message message to respond with, nullable
     */
    public MockHandler(ApiMessage message) {
        if (message != null) {
            setMockResponseForApiKey(ApiKeys.forId(message.apiKey()), message);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        DecodedRequestFrame<?> msg1 = (DecodedRequestFrame<?>) msg;
        respond(ctx, msg1);
    }

    private void respond(ChannelHandlerContext ctx, DecodedRequestFrame<?> frame) {
        requests.add(frame);
        ConditionalMockResponse message = conditionalMockResponses.stream().filter(r -> r.matcher.matches(MockServer.toRequest(frame))).findFirst().orElseThrow();
        DecodedResponseFrame<?> responseFrame = new DecodedResponseFrame<>(frame.apiVersion(),
                frame.correlationId(), new ResponseHeaderData().setCorrelationId(frame.correlationId()), message.message());
        message.invocations.incrementAndGet();
        ctx.write(responseFrame);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Close the connection when an exception is raised.
        cause.printStackTrace();
        ctx.close();
    }

    /**
     * Set the response
     * @param response response
     */
    public void setMockResponseForApiKey(ApiKeys keys, ApiMessage response) {
        addMockResponse(new TypeSafeMatcher<>() {
            @Override
            protected boolean matchesSafely(Request request) {
                return request.apiKeys() == keys;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("has key " + keys);
            }
        }, response);
    }

    public void addMockResponse(Matcher<Request> matcher, ApiMessage response) {
        conditionalMockResponses.add(new ConditionalMockResponse(matcher, response, new AtomicLong(0)));
    }

    /**
     * Get requests
     * @return get received requests
     */
    public List<DecodedRequestFrame<?>> getRequests() {
        return Collections.unmodifiableList(requests);
    }

    public void assertAllMockInteractionsInvoked() {
        List<ConditionalMockResponse> anyUninvoked = conditionalMockResponses.stream().filter(r -> r.invocations.get() <= 0).toList();
        if (!anyUninvoked.isEmpty()) {
            String collect = anyUninvoked.stream().map(conditionalMockResponse -> {
                StringDescription stringDescription = new StringDescription();
                conditionalMockResponse.matcher.describeTo(stringDescription);
                return "mock response was never invoked: " + stringDescription;
            }).collect(Collectors.joining(","));
            throw new AssertionError(collect);
        }
    }

    /**
     * Clear recorded requests
     */
    public void clear() {
        requests.clear();
        conditionalMockResponses.clear();
    }
}
