package com.yupi.juziaiagent.config;

import com.yupi.juziaiagent.controller.HealthController;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** 仅启动健康接口，无模型、数据库、MCP 或本地密钥配置。 */
class LocalAccessConfigTest {

    @Test
    void localProfileBindsLoopbackAndOnlyPermitsLocalFrontendOrigins() throws Exception {
        var documents = new YamlPropertySourceLoader().load("public-config", new ClassPathResource("application.yml"));
        var localSettings = documents.stream()
                .filter(source -> "local".equals(source.getProperty("spring.config.activate.on-profile")))
                .findFirst().orElseThrow();
        assertEquals("127.0.0.1", localSettings.getProperty("server.address"));
        assertTrue(documents.stream()
                .filter(source -> source.getProperty("spring.config.activate.on-profile") == null)
                .allMatch(source -> source.getProperty("server.address") == null),
                "Production must not inherit the local-only address");

        // 跳过默认配置搜索，仅注入公开文件中的 local 段；不会加载 application-local.yml。
        try (var application = new SpringApplicationBuilder(TestWebApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("spring.config.location=optional:classpath:/local-access-test/", "server.port=0",
                        "spring.main.banner-mode=off", "logging.level.root=ERROR")
                .initializers(context -> context.getEnvironment().getPropertySources().addFirst(localSettings))
                .run()) {
            var server = ((ServletWebServerApplicationContext) application).getWebServer();
            Connector connector = ((TomcatWebServer) server).getTomcat().getConnector();
            var protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
            assertEquals("127.0.0.1", protocol.getAddress().getHostAddress());
            URI health = URI.create("http://127.0.0.1:" + server.getPort() + "/health");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            assertEquals(200, http.send(HttpRequest.newBuilder(health).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());

            for (String origin : new String[]{"http://127.0.0.1:5173", "http://localhost:5173",
                    "http://127.0.0.1:4173", "http://localhost:4173"}) {
                var response = http.send(HttpRequest.newBuilder(health).header("Origin", origin).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(200, response.statusCode());
                assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
                var preflight = http.send(HttpRequest.newBuilder(health).header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(200, preflight.statusCode());
            }
            for (String origin : new String[]{"https://untrusted.example", "http://localhost.evil.test:5173",
                    "http://192.168.1.2:5173", "http://localhost:9999", "null"}) {
                var response = http.send(HttpRequest.newBuilder(health).header("Origin", origin).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(403, response.statusCode(), origin);
                assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class})
    @Import({CorsConfig.class, HealthController.class})
    static class TestWebApplication { }
}
