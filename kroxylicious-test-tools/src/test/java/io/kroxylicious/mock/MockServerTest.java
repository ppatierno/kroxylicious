/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.mock;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.kafka.common.message.ApiMessageType;
import org.apache.kafka.common.protocol.ApiMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.kroxylicious.test.ApiMessageSampleGenerator;
import io.kroxylicious.test.ApiMessageSampleGenerator.ApiAndVersion;
import io.kroxylicious.test.DataClasses;
import io.kroxylicious.test.Request;
import io.kroxylicious.test.Response;
import io.kroxylicious.test.client.KafkaClient;
import io.kroxylicious.test.server.MockServer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockServerTest {
    private static final Map<ApiAndVersion, ApiMessage> responseSamples = ApiMessageSampleGenerator.createResponseSamples();
    private static final Map<ApiAndVersion, ApiMessage> requestSamples = ApiMessageSampleGenerator.createRequestSamples();

    public static Stream<ApiAndVersion> allSupportedApiVersions() {
        return DataClasses.getRequestClasses().keySet().stream().flatMap(apiKeys -> {
            ApiMessageType messageType = apiKeys.messageType;
            IntStream supported = IntStream.range(messageType.lowestSupportedVersion(), apiKeys.messageType.highestSupportedVersion(true) + 1);
            return supported.mapToObj(version -> new ApiAndVersion(apiKeys, (short) version));
        });
    }

    @ParameterizedTest
    @MethodSource("allSupportedApiVersions")
    void testClientCanSendAndReceiveRPCToMock(ApiAndVersion apiKey) throws Exception {
        Response mockResponse = getResponse(apiKey);
        try (var mockServer = MockServer.startOnRandomPort(mockResponse);
                var kafkaClient = new KafkaClient("127.0.0.1", mockServer.port())) {
            CompletableFuture<Response> future = kafkaClient.get(getRequest(apiKey));
            Response clientResponse = future.get(10, TimeUnit.SECONDS);
            assertEquals(mockResponse, clientResponse);
        }
    }

    private Response getResponse(ApiAndVersion apiAndVersion) {
        return new Response(apiAndVersion.keys(), apiAndVersion.apiVersion(), responseSamples.get(apiAndVersion));
    }

    private Request getRequest(ApiAndVersion apiAndVersion) {
        short apiVersion = apiAndVersion.apiVersion();
        ApiMessage message = requestSamples.get(apiAndVersion);
        return new Request(apiAndVersion.keys(), apiVersion, "clientId", message);
    }

}
