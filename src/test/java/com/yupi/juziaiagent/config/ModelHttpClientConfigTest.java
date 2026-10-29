package com.yupi.juziaiagent.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 仅请求本机假 HTTP 服务，检查等待响应头和响应体时都能超时。 */
class ModelHttpClientConfigTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockingHttpHasABoundedLifetimeEvenIfServerStalls(boolean sendHeadersFirst) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (sendHeadersFirst) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("partial".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                }
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        try {
            ModelLimitsProperties limits = new ModelLimitsProperties();
            limits.setChatTimeout(Duration.ofMillis(200));
            var builder = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            new ModelHttpClientConfig().modelHttpTimeouts(limits).customize(builder);
            RestClient client = builder.build();
            long started = System.nanoTime();
            assertThrows(RestClientException.class, () -> client.get().retrieve().body(String.class));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
