/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package dev.failsafe.internal;

import dev.failsafe.Fallback;
import dev.failsafe.spi.*;
import dev.failsafe.FallbackConfig;

import java.util.concurrent.*;
import java.util.function.Function;

/**
 * A PolicyExecutor that handles failures according to a {@link Fallback}.
 *
 * @param <R> result type
 */
public class FallbackExecutor<R> extends PolicyExecutor<R> {
  private final FallbackImpl<R> fallback;
  private final FallbackConfig<R> config;
  private final EventHandler<R> failedAttemptHandler;

  public FallbackExecutor(FallbackImpl<R> fallback, int policyIndex) {
    super(fallback, policyIndex);
    this.fallback = fallback;
    this.config = fallback.getConfig();
    this.failedAttemptHandler = EventHandler.ofExecutionAttempted(config.getFailedAttemptListener());
  }

  /**
   * Performs an execution by calling pre-execute else calling the supplier, applying a fallback if it fails, and
   * calling post-execute.
   */
  @Override
  public Function<SyncExecutionInternal<R>, ExecutionResult<R>> apply(
    Function<SyncExecutionInternal<R>, ExecutionResult<R>> innerFn, Scheduler scheduler) {

    return execution -> {
      ExecutionResult<R> result = innerFn.apply(execution);
      if (execution.isCancelled(this))
        return result;

      if (isFailure(result)) {
        if (failedAttemptHandler != null)
          failedAttemptHandler.handle(result, execution);

        try {
          result = fallback == FallbackImpl.NONE ?
            result.withNonResult() :
            result.withResult(fallback.apply(result.getResult(), result.getException(), execution));
        } catch (Throwable t) {
          result = ExecutionResult.exception(t);
        }
      }

      return postExecute(execution, result);
    };
  }

  /**
   * Performs an async execution by calling pre-execute else calling the supplier and doing a post-execute.
   */
  @Override
  public Function<AsyncExecutionInternal<R>, CompletableFuture<ExecutionResult<R>>> applyAsync(
    Function<AsyncExecutionInternal<R>, CompletableFuture<ExecutionResult<R>>> innerFn, Scheduler scheduler,
    FailsafeFuture<R> future) {

    return execution -> innerFn.apply(execution).thenCompose(result -> {
      if (result == null || future.isDone())
        return ExecutionResult.nullFuture();
      if (execution.isCancelled(this))
        return CompletableFuture.completedFuture(result);
      if (!isFailure(result))
        return postExecuteAsync(execution, result, scheduler, future);

      if (failedAttemptHandler != null)
        failedAttemptHandler.handle(result, execution);

      CompletableFuture<ExecutionResult<R>> promise = new CompletableFuture<>();
      Callable<R> callable = () -> {
        try {
          CompletableFuture<R> fallbackFuture = fallback.applyStage(result.getResult(), result.getException(), execution);
          fallbackFuture.whenComplete((innerResult, exception) -> {
            if (exception instanceof CompletionException)
              exception = exception.getCause();
            ExecutionResult<R> r = exception == null ? result.withResult(innerResult) : ExecutionResult.exception(exception);
            promise.complete(r);
          });
        } catch (Throwable t) {
          promise.complete(ExecutionResult.exception(t));
        }
        return null;
      };

      try {
        if (!config.isAsync())
          callable.call();
        else {
          Future<?> scheduledFallback = scheduler.schedule(callable, 0, TimeUnit.NANOSECONDS);

          // Propagate outer cancellations to the Fallback future and its promise
          future.setCancelFn(this, (mayInterrupt, cancelResult) -> {
            scheduledFallback.cancel(mayInterrupt);
            promise.complete(cancelResult);
          });
        }
      } catch (Throwable t) {
        // Hard scheduling failure
        promise.completeExceptionally(t);
      }

      return promise.thenCompose(ss -> postExecuteAsync(execution, ss, scheduler, future));
    });
  }
}
