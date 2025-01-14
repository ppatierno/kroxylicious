/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.utils.ByteBufferOutputStream;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A context to allow filters to interact with other filters and the pipeline.
 */
public interface KrpcFilterContext {
    /**
     * A description of this channel.
     * @return A description of this channel (typically used for logging).
     */
    String channelDescriptor();

    /**
     * Create a ByteBufferOutputStream of the given capacity.
     * The backing buffer will be deallocated when the request processing is completed
     * @param initialCapacity The initial capacity of the buffer.
     * @return The allocated ByteBufferOutputStream
     */
    ByteBufferOutputStream createByteBufferOutputStream(int initialCapacity);

    /**
     * The SNI hostname provided by the client, if any.
     * @return the SNI hostname provided by the client.  Will be null if the client is
     * using a non-TLS connection or the TLS client hello didn't provide one.
     */
    @Nullable
    String sniHostname();

    /**
     * Creates a builder for a request filter result objects.  This object encapsulates
     * the request to forward and optionally orders for actions such as closing
     * the connection or dropping the request.
     * <br/>
     * The builder returns either {@link CompletionStage<RequestFilterResult>} object
     * ready to be returned by the request filter, or a {@link ResponseFilterResult} object.
     * The latter facilitates asynchronous programming patterns where requests must be
     * forwarded after other work has completed.
     *
     * @return builder
     */
    RequestFilterResultBuilder requestFilterResultBuilder();

    /**
     * Generates a completed filter results containing the given header and request.  When
     * request filters implementations return this result, the request will be sent towards
     * the broker, invoking upstream filters.
     * <br/>
     * Invoking this method is identical to invoking:
     * {@code requestFilterResultBuilder.forward(header, request).complete()}
     *
     * @param header The header to forward to the broker.
     * @param request The request to forward to the broker.
     * @return completed filter results.
     */
    CompletionStage<RequestFilterResult> forwardRequest(@NonNull RequestHeaderData header, @NonNull ApiMessage request);

    /**
     * Send a message from a filter towards the broker.   The response to the requests will be
     * made available to the filter asynchronously, by way of the {@link CompletionStage}.  The
     * CompletionStage will contain the response object, or null of the request does not have a
     * response.
     * <br>
     * The request will pass through all filters upstream of the filter that invoked the operation,
     * invoking them, but not itself.  Similarly, the response will pass through all filters upstream
     * of the filter that invoked the operation, invoking them, but not itself. The response does not
     * pass through filter downstream.
     * <br/>
     *
     * @param apiVersion The version of the request to use
     * @param request The request to send.
     * @param <T> The type of the response
     * @return CompletionStage that will yield the response.
     */
    @NonNull
    <T extends ApiMessage> CompletionStage<T> sendRequest(short apiVersion, @NonNull ApiMessage request);

    /**
     * Generates a completed filter results containing the given header and response.  When
     * response filters implementations return this result, the response will be sent towards
     * the client, invoking downstream filters.
     * <br/>
     * Invoking this method is identical to invoking:
     * {@code responseFilterResultBuilder.forward(header, response).complete()}
     *
     * @param header The header to forward to the broker.
     * @param response The request to forward to the broker.
     * @return completed filter results.
     */
    CompletionStage<ResponseFilterResult> forwardResponse(@NonNull ResponseHeaderData header, @NonNull ApiMessage response);

    /**
     * Creates a builder for a request filter result objects.  This object encapsulates
     * the response to forward and optionally orders for actions such as closing
     * the connection or dropping the response.
     * <br/>
     * The builder returns either {@link CompletionStage<ResponseFilterResult>} object
     * ready to be returned by the response filter, or a {@link ResponseFilterResult} object.
     * The latter facilitates asynchronous programming patterns where responses must be
     * forwarded after other work has completed.
     *
     * @return builder
     */
    ResponseFilterResultBuilder responseFilterResultBuilder();

    /**
     * Allows the filter to identify which cluster it is processing a request for
     * @return virtual cluster name
     */
    String getVirtualClusterName();
    // TODO an API to allow a filter to add/remove another filter from the pipeline
}
