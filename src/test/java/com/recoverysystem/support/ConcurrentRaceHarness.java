package com.recoverysystem.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ConcurrentRaceHarness {

    private ConcurrentRaceHarness() {
    }

    public static <T> RaceResult<T> race(
            Callable<T> first,
            Callable<T> second,
            Duration readySyncTimeout,
            Duration completionTimeout) {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<T> firstFuture = executor.submit(
                    () -> waitForStart(first, ready, start));
            Future<T> secondFuture = executor.submit(
                    () -> waitForStart(second, ready, start));

            try {
                if (!ready.await(readySyncTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError(
                            "Concurrent race callables did not both become ready within "
                                    + readySyncTimeout);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while waiting for concurrent race callables to become ready",
                        exception);
            }

            start.countDown();

            T firstResult = null;
            T secondResult = null;
            Throwable firstFailure = null;
            Throwable secondFailure = null;

            try {
                firstResult = firstFuture.get(
                        completionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException exception) {
                firstFailure = exception.getCause();
            } catch (TimeoutException exception) {
                firstFailure = exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                firstFailure = exception;
            }

            try {
                secondResult = secondFuture.get(
                        completionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException exception) {
                secondFailure = exception.getCause();
            } catch (TimeoutException exception) {
                secondFailure = exception;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                secondFailure = exception;
            }

            if (firstFailure != null || secondFailure != null) {
                Throwable cause = firstFailure != null ? firstFailure : secondFailure;
                String failedSide = firstFailure != null ? "first" : "second";
                AssertionError error = new AssertionError(
                        "The " + failedSide + " concurrent race callable failed", cause);
                if (firstFailure != null && secondFailure != null) {
                    error.addSuppressed(new AssertionError(
                            "The second concurrent race callable also failed", secondFailure));
                }
                throw error;
            }

            return new RaceResult<>(firstResult, secondResult);
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T waitForStart(
            Callable<T> task,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return task.call();
    }

    public record RaceResult<T>(T first, T second) {

        public List<T> asList() {
            return List.of(first, second);
        }
    }
}
