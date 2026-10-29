package com.yupi.juziaiagent.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ModelHttpClientConfig {
    /** Spring AI 使用自动配置的 RestClient.Builder；为阻塞 HTTP 同时设置底层超时。 */
    @Bean
    public RestClientCustomizer modelHttpTimeouts(ModelLimitsProperties limits) {
        return builder -> {
            Duration timeout = limits.getChatTimeout();
            Duration connectTimeout = timeout.compareTo(Duration.ofSeconds(10)) < 0 ? timeout : Duration.ofSeconds(10);
            var http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
            var factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(timeout);
            builder.requestFactory(factory);
        };
    }
}
