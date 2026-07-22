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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;

/**
 * Generic resolver-local lock and completion-notification context for generated code.
 *
 * <p>This class deliberately has the same owner model as Fory core's JIT context. It knows nothing
 * about JSON readers, writers, capabilities, generated classes, or resolver metadata. A root graph
 * and its completion callbacks use the same local lock. The JIT action runs outside that lock;
 * success reacquires it before invoking the resolver-owned publication callback and then registered
 * parent notifications. Synchronous mode uses the callback map only to break recursive compilation
 * of the same identifier.
 *
 * <p>{@link JITCallback#id()} correlates active child notifications. Ordinary callback
 * registrations retain independent resolver-local publication callbacks; future registrations use
 * the identifier to reuse one active graph publication request. Generated-class single-flight
 * belongs to the shared registry, while resolver-local construction, publication, and generated
 * parent-field updates remain local.
 */
@Internal
public final class JsonJITContext {
  private final boolean asyncCompilationEnabled;
  private final ExecutorService compilationService;
  private final ReentrantLock jitLock;
  private final Map<Object, List<NotifyCallback>> callbacks;
  private int numRunningTasks;

  @Internal
  public JsonJITContext(boolean asyncCompilationEnabled, ExecutorService compilationService) {
    this.asyncCompilationEnabled = asyncCompilationEnabled;
    this.compilationService = compilationService;
    jitLock = new ReentrantLock(true);
    callbacks = new HashMap<>();
  }

  @Internal
  public <T> T registerJITCallback(
      Callable<T> interpretedAction, Callable<T> jitAction, JITCallback<T> callback) {
    try {
      lock();
      if (!asyncCompilationEnabled) {
        Object id = callback.id();
        if (callbacks.containsKey(id)) {
          return interpretedAction.call();
        }
        callbacks.put(id, new ArrayList<>());
        try {
          T result = jitAction.call();
          callback.onSuccess(result);
          List<NotifyCallback> notifyCallbacks = callbacks.get(id);
          for (int i = 0; i < notifyCallbacks.size(); i++) {
            notifyCallbacks.get(i).onNotifyResult(result);
          }
          return result;
        } catch (Throwable t) {
          callback.onFailure(t);
          ExceptionUtils.throwException(t);
          throw new IllegalStateException("unreachable");
        } finally {
          callbacks.remove(id);
        }
      }
      // Do not skip ordinary registrations when this ID is already present. The shared class owner
      // single-flights compilation, while every local registration retains its own callback.
      callbacks.computeIfAbsent(callback.id(), ignored -> new ArrayList<>());
      numRunningTasks++;
      ExecutorService service = compilationService;
      if (service == null) {
        service = CodeGenerator.getCompilationService();
      }
      try {
        service.execute(() -> runJITAction(jitAction, callback));
      } catch (Throwable t) {
        finishTask();
        callback.onFailure(t);
        ExceptionUtils.throwException(t);
      }
      return interpretedAction.call();
    } catch (Exception e) {
      ExceptionUtils.throwException(e);
      throw new IllegalStateException("unreachable");
    } finally {
      unlock();
    }
  }

  /**
   * Registers one resolver-local publication against independently scheduled class futures.
   *
   * <p>The future supplier must submit every independent compilation before composing its result.
   * No compilation worker is used to wait for another worker. Repeated requests with the same ID
   * reuse the active resolver-local request and return the interpreted capability until the
   * complete result is published.
   */
  public <T> T registerJITFuture(
      Callable<T> interpretedAction,
      Callable<CompletableFuture<T>> futureAction,
      JITCallback<T> callback) {
    try {
      lock();
      Object id = callback.id();
      if (callbacks.containsKey(id)) {
        return interpretedAction.call();
      }
      callbacks.put(id, new ArrayList<>());
      if (!asyncCompilationEnabled) {
        try {
          T result = futureAction.call().join();
          callback.onSuccess(result);
          List<NotifyCallback> notifyCallbacks = callbacks.get(id);
          for (int i = 0; i < notifyCallbacks.size(); i++) {
            notifyCallbacks.get(i).onNotifyResult(result);
          }
          return result;
        } catch (Throwable t) {
          Throwable failure = unwrapFutureFailure(t);
          callback.onFailure(failure);
          ExceptionUtils.throwException(failure);
          throw new IllegalStateException("unreachable");
        } finally {
          callbacks.remove(id);
        }
      }
      CompletableFuture<T> future;
      try {
        future = futureAction.call();
      } catch (Throwable t) {
        callbacks.remove(id);
        callback.onFailure(t);
        ExceptionUtils.throwException(t);
        throw new IllegalStateException("unreachable");
      }
      numRunningTasks++;
      future.whenComplete(
          (result, failure) -> {
            if (failure == null) {
              completeSuccess(callback, result);
            } else {
              completeFailure(callback, unwrapFutureFailure(failure));
            }
          });
      return interpretedAction.call();
    } catch (Exception e) {
      ExceptionUtils.throwException(e);
      throw new IllegalStateException("unreachable");
    } finally {
      unlock();
    }
  }

  private static Throwable unwrapFutureFailure(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  private <T> void runJITAction(Callable<T> jitAction, JITCallback<T> callback) {
    T result;
    try {
      result = jitAction.call();
    } catch (Throwable t) {
      completeFailure(callback, t);
      return;
    }
    completeSuccess(callback, result);
  }

  private <T> void completeSuccess(JITCallback<T> callback, T result) {
    try {
      lock();
      callback.onSuccess(result);
      List<NotifyCallback> notifyCallbacks = callbacks.get(callback.id());
      if (notifyCallbacks != null) {
        for (int i = 0; i < notifyCallbacks.size(); i++) {
          notifyCallbacks.get(i).onNotifyResult(result);
        }
      }
    } finally {
      finishTask();
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
      finishTask();
      unlock();
    }
  }

  private void finishTask() {
    numRunningTasks--;
    if (numRunningTasks == 0) {
      callbacks.clear();
    }
  }

  public void registerJITNotifyCallback(Object id, NotifyCallback callback) {
    Preconditions.checkNotNull(id);
    try {
      lock();
      List<NotifyCallback> notifyCallbacks = callbacks.get(id);
      if (notifyCallbacks == null) {
        callback.onNotifyMissed();
      } else {
        notifyCallbacks.add(callback);
      }
    } finally {
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

  @Internal
  public interface NotifyCallback {
    default void onNotifyResult(Object result) {
      onNotifyMissed();
    }

    void onNotifyMissed();
  }
}
