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
 * <p>{@link JITCallback#id()} correlates active child notifications; it is not a task-request or
 * deduplication key. Every asynchronous registration submits its action and owns its completion
 * callback. Duplicate actions converge on {@link JsonCodegen}'s shared generated-class cache, then
 * may construct and install equivalent resolver-local instances. This is intentional: generated
 * class compilation is single-flight, while resolver-local subscribers, construction, publication,
 * and generated parent-field updates are not deduplicated.
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
      // Do not skip registration when this ID is already present. JsonCodegen single-flights class
      // compilation, while every local registration must retain its own publication callback.
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
