package com.yupi.juziaiagent.tools;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.DnsResolver;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** 抓取和下载共用的公网 HTTP 客户端，不使用系统代理或自动重定向。 */
final class SafeHttpClient {

    private static final int MAX_REDIRECTS = 5;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final ThreadPoolExecutor DNS = createDnsExecutor();
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "tool-http-timeout");
        thread.setDaemon(true);
        return thread;
    });

    private final DnsResolver resolver;
    private final Predicate<InetAddress> allowedAddress;
    private final int timeoutMillis;

    SafeHttpClient() {
        this(InetAddress::getAllByName, SafeHttpClient::isPublicAddress, 30_000);
    }

    // 仅供同包离线测试注入本地假服务，应用配置不提供放行内网的开关。
    SafeHttpClient(DnsResolver resolver, Predicate<InetAddress> allowedAddress, int timeoutMillis) {
        this.resolver = resolver;
        this.allowedAddress = allowedAddress;
        this.timeoutMillis = timeoutMillis;
    }

    Response get(String url, int maxBytes) throws IOException {
        URI uri = parseUri(url);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            String host = validateUri(uri);
            InetAddress[] addresses = resolve(host, deadline);
            if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> !allowedAddress.test(address))) {
                throw new IOException("只允许访问公网地址。");
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                throw new IOException("网络请求超时。");
            }
            int ioTimeout = (int) Math.min(10_000, remaining);
            RequestConfig config = RequestConfig.custom().setConnectTimeout(ioTimeout)
                    .setSocketTimeout(ioTimeout).setConnectionRequestTimeout(ioTimeout).build();
            // 固定本次已校验的 DNS 结果，连接时不再解析，防止校验后地址切换到内网。
            try (CloseableHttpClient client = HttpClients.custom()
                    .setDnsResolver(requestHost -> pinnedAddresses(requestHost, host, addresses))
                    .setDefaultRequestConfig(config).disableRedirectHandling()
                    .disableAutomaticRetries().disableCookieManagement().build()) {
                HttpGet request = new HttpGet(uri);
                var timeout = TIMEOUTS.schedule(request::abort, remaining, TimeUnit.MILLISECONDS);
                try (CloseableHttpResponse response = client.execute(request)) {
                    int status = response.getStatusLine().getStatusCode();
                    if (REDIRECT_STATUSES.contains(status)) {
                        Header location = response.getFirstHeader("Location");
                        if (location == null || redirects == MAX_REDIRECTS) {
                            throw new IOException("重定向无效或次数过多。");
                        }
                        try {
                            uri = uri.resolve(location.getValue());
                        } catch (IllegalArgumentException e) {
                            throw new IOException("重定向地址无效。");
                        }
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        throw new IOException("远端服务未成功返回资源。");
                    }
                    HttpEntity entity = response.getEntity();
                    if (entity == null) {
                        return new Response(new byte[0], uri, null);
                    }
                    if (entity.getContentLength() > maxBytes) {
                        throw new IOException("资源超过大小限制。");
                    }
                    try (InputStream input = entity.getContent()) {
                        byte[] bytes = input.readNBytes(maxBytes + 1);
                        if (bytes.length > maxBytes) {
                            throw new IOException("资源超过大小限制。");
                        }
                        Header contentType = entity.getContentType();
                        return new Response(bytes, uri, contentType == null ? null : contentType.getValue());
                    }
                } finally {
                    timeout.cancel(false);
                }
            }
        }
        throw new IOException("重定向次数过多。");
    }

    private static ThreadPoolExecutor createDnsExecutor() {
        // 系统 DNS 不一定响应中断：限制线程和队列，避免慢解析耗尽请求线程。
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "tool-dns");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private InetAddress[] resolve(String host, long deadline) throws IOException {
        Future<InetAddress[]> lookup;
        try {
            lookup = DNS.submit(() -> resolver.resolve(host));
        } catch (RejectedExecutionException e) {
            throw new IOException("域名解析繁忙，请稍后重试。");
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("网络请求超时。");
            }
            return lookup.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("网络请求已取消。");
        } catch (TimeoutException | ExecutionException e) {
            throw new IOException("域名解析失败或超时。");
        } finally {
            lookup.cancel(true);
            DNS.remove((Runnable) lookup);
        }
    }

    private static URI parseUri(String url) throws IOException {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("请提供有效的 HTTP 或 HTTPS 地址。");
        }
    }

    private static String validateUri(URI uri) throws IOException {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getHost().contains("%") || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IOException("请提供不含用户名和密码的有效 HTTP 或 HTTPS 地址。");
        }
        return uri.getHost();
    }

    private static InetAddress[] pinnedAddresses(String requestHost, String host, InetAddress[] addresses)
            throws UnknownHostException {
        // HttpClient 在处理 IPv6 时可能去掉主机名外层的方括号。
        if (!requestHost.replace("[", "").replace("]", "")
                .equalsIgnoreCase(host.replace("[", "").replace("]", ""))) {
            throw new UnknownHostException("请求主机与已校验主机不一致。");
        }
        return addresses.clone();
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int third = Byte.toUnsignedInt(bytes[2]);
            return !(first == 0 || first == 10 || first == 127 || first >= 224
                    || first == 100 && second >= 64 && second <= 127
                    || first == 169 && second == 254 || first == 172 && second >= 16 && second <= 31
                    || first == 192 && (second == 168 || second == 0 && (third == 0 || third == 2)
                    || second == 88 && third == 99)
                    || first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)
                    || first == 203 && second == 0 && third == 113);
        }
        // IPv6 只允许全球单播，排除文档、隧道和可嵌入 IPv4 的特殊范围。
        return (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x01
                && (Byte.toUnsignedInt(bytes[2]) <= 1
                || bytes[2] == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8))
                && !(first == 0x20 && second == 0x02)
                && !(first == 0x3f && (second == 0xfe || second == 0xff));
    }

    record Response(byte[] body, URI uri, String contentType) { }
}
