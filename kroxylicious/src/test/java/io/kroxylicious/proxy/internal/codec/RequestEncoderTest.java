/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal.codec;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.kafka.common.message.ApiVersionsRequestData;
import org.apache.kafka.common.message.ProduceRequestData;
import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.OpaqueRequestFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RequestEncoderTest extends AbstractCodecTest {

    public static List<Object[]> testResponseFrames() {
        List<Object[]> cartesianProduct = new ArrayList<>();
        List.of(true, false).forEach(
                requestHasResponse -> Stream.of(true, false)
                        .forEach(decodeResponse -> cartesianProduct.add(new Object[]{ requestHasResponse, decodeResponse })));
        return cartesianProduct;
    }

    // TODO test API_VERSIONS header is v0

    @ParameterizedTest
    @MethodSource("requestApiVersions")
    void testApiVersions(short apiVersion) throws Exception {
        RequestHeaderData exampleHeader = exampleRequestHeader(apiVersion);
        ApiVersionsRequestData exampleBody = exampleApiVersionsRequest();
        short headerVersion = ApiKeys.API_VERSIONS.requestHeaderVersion(apiVersion);
        ByteBuffer expected = serializeUsingKafkaApis(headerVersion, exampleHeader, apiVersion, exampleBody);

        CorrelationManager correlationManager = new CorrelationManager(exampleHeader.correlationId());
        var encoder = new KafkaRequestEncoder(correlationManager);
        testEncode(expected, new DecodedRequestFrame<ApiVersionsRequestData>(apiVersion, exampleHeader.correlationId(), true, exampleHeader, exampleBody), encoder);
        var corr = correlationManager.getBrokerCorrelation(exampleHeader.correlationId());
        assertEquals(ApiKeys.API_VERSIONS.id, corr.apiKey());
        assertEquals(exampleHeader.requestApiKey(), corr.apiKey());
        assertEquals(exampleHeader.requestApiVersion(), corr.apiVersion());
        assertTrue(corr.decodeResponse());
        assertEquals(exampleHeader.correlationId(), corr.downstreamCorrelationId());
    }

    /**
     * Zero-ack produce requests do not have a response, so we do not want to add correlations in memory
     * that will never be used. We determine this in the KafkaRequestDecoder and set a flag on the frame
     * when there is no response expected.
     */
    @ParameterizedTest
    @MethodSource("testResponseFrames")
    void testRequestsWithNoResponseArentStoredInCorrelationManager() throws Exception {

        givenRequestFrame frame = createRequestFrame(false);

        var correlationManager = new CorrelationManager(78);
        whenRequestEncoded(frame, correlationManager);

        assertTrue(correlationManager.brokerRequests.isEmpty(),
                "Expect request with no response to not have a correlation stored");
    }

    @ParameterizedTest
    @MethodSource("testResponseFrames")
    void testRequestsWithResponseAreStoredInCorrelationManager() throws Exception {

        givenRequestFrame frame = createRequestFrame(true);

        var correlationManager = new CorrelationManager(78);
        whenRequestEncoded(frame, correlationManager);

        assertNotNull(correlationManager.brokerRequests.get(78),
                "Expect request with response to have a correlation stored");
        assertEquals(1, correlationManager.brokerRequests.size(),
                "Expect request with response to have a correlation");
    }

    private static void whenRequestEncoded(givenRequestFrame result, CorrelationManager correlationManager) throws Exception {
        ByteBuf out = Unpooled.buffer(result.byteBuffer().capacity() + 4);
        new KafkaRequestEncoder(correlationManager).encode(null, result.frame(), out);
    }

    @NotNull
    private static givenRequestFrame createRequestFrame(boolean hasResponse) {
        short produceVersion = ApiKeys.PRODUCE.latestVersion();
        var header = new RequestHeaderData()
                .setRequestApiKey(ApiKeys.PRODUCE.id)
                .setRequestApiVersion(produceVersion)
                .setCorrelationId(45);
        short headerVersion = ApiKeys.PRODUCE.requestHeaderVersion(produceVersion);
        if (headerVersion >= 1) {
            header.setClientId("323423");
        }
        var body = new ProduceRequestData()
                .setAcks((short) 0);
        if (produceVersion >= 3) {
            body.setTransactionalId("wnedkwjn");
        }

        ByteBuffer byteBuffer = serializeUsingKafkaApis(headerVersion, header, produceVersion, body);
        int frameSize = byteBuffer.getInt();

        ByteBuf buf = Unpooled.copiedBuffer(byteBuffer);

        var frame = new OpaqueRequestFrame(buf, 12, true, frameSize, hasResponse);
        givenRequestFrame result = new givenRequestFrame(byteBuffer, frame);
        return result;
    }

    private record givenRequestFrame(ByteBuffer byteBuffer, OpaqueRequestFrame frame) {
    }
}
