/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.json.codegen;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.fory.annotation.Internal;
import org.apache.fory.util.ExceptionUtils;

/**
 * Resolver-local lock and completion context for generated code.
 *
 * <p>This class knows nothing about JSON readers, writers, capabilities, generated classes, or
 * resolver metadata. The shared registry owns compilation and returns futures; this context only
 * prevents a duplicate resolver-local publication request and invokes its completion while holding
 * the same lock as root codec execution.
 *
 * <p>{@link JITCallback#id()} identifies one active resolver-local graph request. Generated-class
 * single-flight belongs to the shared registry; resolver-local construction and atomic capability
 * publication belong to the resolver callback.
 */
@Internal
public final class JsonJITContext {
  private final boolean asyncCompilationEnabled;
  private final ReentrantLock jitLock;
  private final Set<Object> activeTasks;

  @Internal
  public JsonJITContext(boolean asyncCompilationEnabled) {
    this.asyncCompilationEnabled = asyncCompilationEnabled;
    jitLock = new ReentrantLock(true);
    activeTasks = new HashSet<>();
  }

  /**
   * Registers one resolver-local publication against independently scheduled class futures.
   *
   * <p>The future supplier must submit every independent compilation before composing its result.
   * No compilation worker is used to wait for another worker. Repeated requests with the same ID
   * reuse the active resolver-local request and return the interpreted capability until the
   * complete result is published.
   */
  public <T> void registerJITFuture(
      Callable<CompletableFuture<T>> futureAction, JITCallback<T> callback) {
    try {
      lock();
      Object id = callback.id();
      if (!activeTasks.add(id)) {
        return;
      }
      if (!asyncCompilationEnabled) {
        try {
          T result = futureAction.call().join();
          callback.onSuccess(result);
        } catch (Throwable t) {
          Throwable failure = unwrapFutureFailure(t);
          callback.onFailure(failure);
          ExceptionUtils.throwException(failure);
        } finally {
          activeTasks.remove(id);
        }
        return;
      }
      CompletableFuture<T> future;
      try {
        future = futureAction.call();
      } catch (Throwable t) {
        activeTasks.remove(id);
        callback.onFailure(t);
        return;
      }
      future.whenComplete(
          (result, failure) -> {
            if (failure == null) {
              completeSuccess(callback, result);
            } else {
              completeFailure(callback, unwrapFutureFailure(failure));
            }
          });
    } catch (Exception e) {
      ExceptionUtils.throwException(e);
    } finally {
      unlock();
    }
  }

  private static Throwable unwrapFutureFailure(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  private <T> void completeSuccess(JITCallback<T> callback, T result) {
    try {
      lock();
      callback.onSuccess(result);
    } finally {
      activeTasks.remove(callback.id());
      unlock();
    }
  }

  private <T> void completeFailure(JITCallback<T> callback, Throwable failure) {
    // The callback owns failure reporting. Resolver callbacks intentionally retain interpreted
    // capability state after an asynchronous compilation failure.
    try {
      lock();
      callback.onFailure(failure);
    } finally {
      activeTasks.remove(callback.id());
      unlock();
    }
  }

  @Internal
  public void lock() {
    if (asyncCompilationEnabled) {
      jitLock.lock();
    }
  }

  @Internal
  public void unlock() {
    if (asyncCompilationEnabled) {
      jitLock.unlock();
    }
  }

  @Internal
  public boolean lockedByCurrentThread() {
    return !asyncCompilationEnabled || jitLock.isHeldByCurrentThread();
  }

  @Internal
  public interface JITCallback<T> {
    void onSuccess(T result);

    default void onFailure(Throwable failure) {
      ExceptionUtils.throwException(failure);
    }

    Object id();
  }
}
