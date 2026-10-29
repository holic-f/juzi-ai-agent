package com.yupi.juziaiagent.tools;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/** 测试专用：虚拟公网主机仅连接本机假服务，不解析外网域名。 */
final class LocalToolHttpServer implements AutoCloseable {

    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final InetAddress allowedLoopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});

    LocalToolHttpServer() throws IOException {
        server.start();
    }

    String url(String path) {
        return "http://public.test:" + server.getAddress().getPort() + path;
    }

    SafeHttpClient client() {
        return client(5_000);
    }

    SafeHttpClient client(int timeoutMillis) {
        return new SafeHttpClient(host -> {
            if (host.equals("public.test")) {
                return new InetAddress[]{allowedLoopback};
            }
            if (host.equals("127.0.0.1")) {
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127, 0, 0, 1})};
            }
            throw new UnknownHostException("No external DNS in offline tests");
        }, address -> address == allowedLoopback || SafeHttpClient.isPublicAddress(address), timeoutMillis);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
