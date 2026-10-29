package com.yupi.juziaiagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SafeHttpClientTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.0.0.1", "127.0.0.1", "100.64.0.1", "169.254.169.254",
            "172.16.0.1", "192.168.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "240.0.0.1", "::", "::1", "fc00::1", "fe80::1", "ff02::1",
            "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2001::1", "2001:db8::1", "2002:7f00:1::1", "3fff::1"})
    void rejectsNonPublicAddresses(String address) throws Exception {
        assertFalse(SafeHttpClient.isPublicAddress(InetAddress.getByName(address)), address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "2606:4700:4700::1111"})
    void acceptsPublicAddresses(String address) throws Exception {
        assertTrue(SafeHttpClient.isPublicAddress(InetAddress.getByName(address)), address);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://public.test/file", "http://user:pass@public.test/",
            "http://public.test:0/", "http://public.test:65536/", "http://[fe80::1%25eth0]/", "http:///missing-host", "bad url"})
    void invalidUrlsAreRejectedBeforeDns(String url) {
        SafeHttpClient client = new SafeHttpClient(host -> {
            fail("Invalid URL must not reach DNS");
            return new InetAddress[0];
        }, SafeHttpClient::isPublicAddress, 1_000);
        assertThrows(IOException.class, () -> client.get(url, 100));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/", "http://2130706433/", "http://127.1/", "http://[::1]/",
            "http://[::ffff:127.0.0.1]/"})
    void productionClientRejectsLocalAddressVariants(String url) {
        assertThrows(IOException.class, () -> new SafeHttpClient().get(url, 100));
    }

    @Test
    void rejectsDomainWithMixedPublicAndPrivateAnswers() throws Exception {
        InetAddress[] addresses = {InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")};
        SafeHttpClient client = new SafeHttpClient(host -> addresses, SafeHttpClient::isPublicAddress, 1_000);
        assertThrows(IOException.class, () -> client.get("http://public.test/", 100));
    }

    @Test
    void followsRelativeRedirectAndLimitsRedirectLoops() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            fixture.server.createContext("/start", exchange -> {
                exchange.getResponseHeaders().set("Location", "/ok");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            fixture.server.createContext("/ok", exchange -> {
                byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            fixture.server.createContext("/loop", exchange -> {
                exchange.getResponseHeaders().set("Location", "/loop");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            SafeHttpClient.Response result = fixture.client().get(fixture.url("/start"), 100);
            assertEquals("hello", new String(result.body(), StandardCharsets.UTF_8));
            assertEquals("/ok", result.uri().getPath());
            assertThrows(IOException.class, () -> fixture.client().get(fixture.url("/loop"), 100));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1", "file:///etc/passwd"})
    void rejectsUnsafeRedirectBeforeSendingTheNextRequest(String target) throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            AtomicInteger privateHits = new AtomicInteger();
            fixture.server.createContext("/start", exchange -> {
                exchange.getResponseHeaders().set("Location", target.startsWith("http")
                        ? target + ":" + fixture.server.getAddress().getPort() + "/private" : target);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            fixture.server.createContext("/private", exchange -> {
                privateHits.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            assertThrows(IOException.class, () -> fixture.client().get(fixture.url("/start"), 100));
            assertEquals(0, privateHits.get());
        }
    }

    @Test
    void pinsDnsResultsInsteadOfResolvingAgainWhenConnecting() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            fixture.server.createContext("/ok", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            AtomicInteger lookups = new AtomicInteger();
            SafeHttpClient client = new SafeHttpClient(host -> {
                if (lookups.incrementAndGet() > 1) {
                    throw new java.net.UnknownHostException("DNS changed after validation");
                }
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
            }, address -> true, 2_000);
            assertEquals(0, client.get(fixture.url("/ok"), 100).body().length);
            assertEquals(1, lookups.get());
        }
    }

    @Test
    void enforcesLimitForChunkedAndDecompressedBodies() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            fixture.server.createContext("/chunked", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) { output.write(new byte[101]); }
            });
            fixture.server.createContext("/gzip", exchange -> {
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, 0);
                try (var output = new GZIPOutputStream(exchange.getResponseBody())) {
                    output.write(new byte[101]);
                }
            });
            assertThrows(IOException.class, () -> fixture.client().get(fixture.url("/chunked"), 100));
            assertThrows(IOException.class, () -> fixture.client().get(fixture.url("/gzip"), 100));
        }
    }

    @Test
    void dnsResolutionSharesTheTotalTimeoutBudget() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SafeHttpClient client = new SafeHttpClient(host -> {
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new java.net.UnknownHostException("Simulated slow DNS");
        }, SafeHttpClient::isPublicAddress, 100);
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> assertThrows(IOException.class, () -> client.get("http://public.test/", 100)));
        } finally {
            release.countDown();
        }
    }

    @Test
    void timesOutWhileWaitingForResponseBody() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            fixture.server.createContext("/slow", exchange -> {
                try {
                    exchange.sendResponseHeaders(200, 0);
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            try {
                assertThrows(IOException.class, () -> fixture.client(200).get(fixture.url("/slow"), 100));
            } finally {
                release.countDown();
            }
        }
    }
}
