package com.recoverysystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.recoverysystem.support.ConcurrentRaceHarness;
import com.recoverysystem.support.ConcurrentRaceHarness.RaceResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcurrentRaceHarnessTest {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration COMPLETION_TIMEOUT = Duration.ofSeconds(2);

    @Test
    void returnsEachCallableResultOnTheCorrectSide() {
        RaceResult<String> result = ConcurrentRaceHarness.race(
                () -> returnAfterShortPause("first marker"),
                () -> returnAfterShortPause("second marker"),
                READY_TIMEOUT,
                COMPLETION_TIMEOUT);

        assertEquals("first marker", result.first());
        assertEquals("second marker", result.second());
        assertEquals(List.of("first marker", "second marker"), result.asList());
    }

    @Test
    void unexpectedCallableExceptionIsWrappedInAssertionError() {
        RuntimeException unexpectedFailure = new RuntimeException("unexpected failure");

        AssertionError error = assertThrows(AssertionError.class, () ->
                ConcurrentRaceHarness.race(
                        () -> {
                            throw unexpectedFailure;
                        },
                        () -> "second marker",
                        READY_TIMEOUT,
                        COMPLETION_TIMEOUT));

        assertSame(unexpectedFailure, error.getCause());
    }

    @Test
    void callableCanCatchExpectedExceptionAndReturnSentinel() {
        RaceResult<String> result = ConcurrentRaceHarness.race(
                () -> {
                    try {
                        throw new IllegalStateException("expected failure");
                    } catch (IllegalStateException exception) {
                        return "expected exception caught";
                    }
                },
                () -> "second marker",
                READY_TIMEOUT,
                COMPLETION_TIMEOUT);

        assertEquals("expected exception caught", result.first());
        assertEquals("second marker", result.second());
    }

    private String returnAfterShortPause(String value) throws InterruptedException {
        Thread.sleep(25);
        return value;
    }
}
