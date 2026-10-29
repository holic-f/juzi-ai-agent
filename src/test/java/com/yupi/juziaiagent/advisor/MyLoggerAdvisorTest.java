package com.yupi.juziaiagent.advisor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅使用假调用链和内存日志，不访问模型或外网。 */
class MyLoggerAdvisorTest {

    private static final String INPUT = "PRIVATE_USER_INPUT";
    private static final String OUTPUT = "PRIVATE_AI_ANSWER";
    private final Logger logger = (Logger) LoggerFactory.getLogger(MyLoggerAdvisor.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ChatClientRequest request = new ChatClientRequest(new Prompt(INPUT), Map.of());
    private Level previousLevel;

    @BeforeEach
    void captureLogsEvenAtDebugLevel() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void defaultSyncLogsOnlyMetricsAndCallsModelOnce() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = response(OUTPUT);
        when(chain.nextCall(request)).thenReturn(response);

        assertSame(response, new MyLoggerAdvisor().adviseCall(request, chain));

        verify(chain, times(1)).nextCall(request);
        assertMetricsOnly("mode=call status=complete", OUTPUT.length());
    }

    @Test
    void defaultStreamPreservesChunksAndCountsEachSubscriptionSeparately() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        List<ChatClientResponse> chunks = List.of(response(OUTPUT), response(" tail"));
        when(chain.nextStream(request)).thenReturn(Flux.fromIterable(chunks));
        Flux<ChatClientResponse> stream = new MyLoggerAdvisor().adviseStream(request, chain);
        verifyNoInteractions(chain);

        assertEquals(chunks, stream.collectList().block(Duration.ofSeconds(2)));
        assertEquals(chunks, stream.collectList().block(Duration.ofSeconds(2)));

        verify(chain, times(2)).nextStream(request);
        assertEquals(2, appender.list.size());
        assertMetricsOnly("mode=stream status=complete", OUTPUT.length() + 5);
    }

    @Test
    void syncErrorIsPropagatedWithoutLoggingItsSensitiveMessage() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        RuntimeException failure = new IllegalStateException(INPUT + OUTPUT);
        when(chain.nextCall(request)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new MyLoggerAdvisor().adviseCall(request, chain)));

        assertMetricsOnly("mode=call status=error", 0);
    }

    @Test
    void streamErrorIsPropagatedWithoutLoggingItsSensitiveMessage() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        RuntimeException failure = new IllegalStateException(INPUT + OUTPUT);
        when(chain.nextStream(request)).thenReturn(Flux.concat(Flux.just(response(OUTPUT)), Flux.error(failure)));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new MyLoggerAdvisor().adviseStream(request, chain).blockLast(Duration.ofSeconds(2))));

        assertMetricsOnly("mode=stream status=error", OUTPUT.length());
    }

    @Test
    void synchronousStreamSetupFailureStillLogsMetrics() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        RuntimeException failure = new IllegalStateException(INPUT);
        when(chain.nextStream(request)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new MyLoggerAdvisor().adviseStream(request, chain).blockLast(Duration.ofSeconds(2))));

        assertMetricsOnly("mode=stream status=error", 0);
    }

    @Test
    void cancellationStopsUpstreamWithoutWaitingForTheFullAnswer() {
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatClientResponse first = response(OUTPUT);
        when(chain.nextStream(request)).thenReturn(Flux.concat(Flux.just(first), Flux.never())
                .doOnCancel(() -> cancelled.set(true)));

        assertSame(first, new MyLoggerAdvisor().adviseStream(request, chain)
                .take(1).blockLast(Duration.ofSeconds(2)));

        assertTrue(cancelled.get());
        assertMetricsOnly("mode=stream status=cancel", OUTPUT.length());
    }

    @Test
    void emptyResponseDoesNotBreakLogging() {
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse response = new ChatClientResponse(null, Map.of());
        when(chain.nextCall(request)).thenReturn(response);

        assertSame(response, new MyLoggerAdvisor().adviseCall(request, chain));

        assertMetricsOnly("mode=call status=complete", 0);
    }

    @ParameterizedTest
    @CsvSource({"false, INFO, false", "false, DEBUG, false", "true, INFO, false", "true, DEBUG, true"})
    void bodyRequiresBothExplicitOptInAndDebug(boolean enabled, String level, boolean expectBody) {
        logger.setLevel(Level.toLevel(level));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(response(OUTPUT));
        new MyLoggerAdvisor(enabled).adviseCall(request, chain);

        assertEquals(expectBody, logs().stream().anyMatch(line -> line.contains(INPUT)));
        assertEquals(expectBody, logs().stream().anyMatch(line -> line.contains(OUTPUT)));
    }

    private void assertMetricsOnly(String expectedStatus, int outputChars) {
        assertFalse(appender.list.isEmpty());
        for (ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage();
            assertTrue(line.contains(expectedStatus), line);
            assertTrue(line.contains("durationMs="), line);
            assertTrue(line.contains("inputChars=" + INPUT.length()), line);
            assertTrue(line.endsWith("outputChars=" + outputChars), line);
            assertFalse(line.contains(INPUT), line);
            assertFalse(line.contains(OUTPUT), line);
            assertNull(event.getThrowableProxy(), "异常堆栈也可能包含正文");
        }
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ChatClientResponse response(String text) {
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))), Map.of());
    }
}
