package com.agent.common.utils.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Minimal async result for flush/shutdown style operations.
 */
public final class CompletableResultCode {
    private final CompletableFuture<Boolean> future = new CompletableFuture<>();

    public static CompletableResultCode ofSuccess() {
        CompletableResultCode result = new CompletableResultCode();
        result.succeed();
        return result;
    }

    public static CompletableResultCode ofFailure() {
        CompletableResultCode result = new CompletableResultCode();
        result.fail();
        return result;
    }

    public static CompletableResultCode ofAll(List<CompletableResultCode> results) {
        if (results == null || results.isEmpty()) {
            return ofSuccess();
        }
        CompletableResultCode combined = new CompletableResultCode();
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(results.size());
        for (CompletableResultCode result : results) {
            if (result != null) {
                futures.add(result.future);
            }
        }
        CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete(
                        (ignored, throwable) -> {
                            if (throwable != null) {
                                combined.fail();
                                return;
                            }
                            for (CompletableFuture<Boolean> future : futures) {
                                if (!Boolean.TRUE.equals(future.getNow(false))) {
                                    combined.fail();
                                    return;
                                }
                            }
                            combined.succeed();
                        });
        return combined;
    }

    public void succeed() {
        future.complete(true);
    }

    public void fail() {
        future.complete(false);
    }

    public boolean isSuccess() {
        return future.isDone() && Boolean.TRUE.equals(future.getNow(false));
    }

    public boolean join(long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 표준 CompletableFuture<Void>로 변환한다.
     */
    public CompletableFuture<Void> toCompletableFuture() {
        return future.thenApply(success -> (Void) null);
    }
}
