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
import java.util.function.Function;
import org.apache.fory.annotation.Internal;
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.json.resolver.JsonSharedRegistry;
import org.apache.fory.util.ExceptionUtils;
import org.apache.fory.util.Preconditions;

/** Coordinates asynchronous JSON object-codec compilation and generated-result publication. */
public final class JsonJITContext {
  private final boolean asyncCompilationEnabled;
  private final ReentrantLock jitLock;
  private int jsonVisitState;
  private final Map<Object, List<NotifyCallback>> hasJITResult;

  public JsonJITContext(boolean asyncCompilationEnabled) {
    this.asyncCompilationEnabled = asyncCompilationEnabled;
    jitLock = new ReentrantLock(true);
    hasJITResult = new HashMap<>();
  }

  public boolean asyncCompilationEnabled() {
    return asyncCompilationEnabled;
  }

  @Internal
  public <T> T registerObjectJITCallback(
      Callable<T> interpreterModeAction, Callable<T> jitAction, ObjectJITCallback<T> callback) {
    try {
      lock();
      Object id = callback.id();
      if (asyncCompilationEnabled && !isAsyncVisitingJson()) {
        List<NotifyCallback> callbacks = hasJITResult.get(id);
        if (callbacks != null) {
          callbacks.add(
              new NotifyCallback() {
                @Override
                @SuppressWarnings("unchecked")
                public void onNotifyResult(Object result) {
                  callback.onSuccess((T) result);
                }

                @Override
                public void onNotifyMissed() {}
              });
          return interpreterModeAction.call();
        }
        hasJITResult.put(id, new ArrayList<>());
        ExecutorService compilationService = CodeGenerator.getCompilationService();
        compilationService.execute(
            () -> {
              try {
                T result = jitAction.call();
                try {
                  lock();
                  // Keep this entry visible while the owner callback installs generated codecs.
                  // Recursive codec construction can subscribe nested fields for the same type.
                  List<NotifyCallback> notifyCallbacks = hasJITResult.get(id);
                  callback.onSuccess(result);
                  if (notifyCallbacks != null) {
                    for (int i = 0; i < notifyCallbacks.size(); i++) {
                      notifyCallbacks.get(i).onNotifyResult(result);
                    }
                  }
                  hasJITResult.remove(id);
                } finally {
                  unlock();
                }
              } catch (Throwable t) {
                try {
                  lock();
                  hasJITResult.remove(id);
                  callback.onFailure(t);
                } finally {
                  unlock();
                }
              }
            });
        return interpreterModeAction.call();
      }
      return jitAction.call();
    } catch (Exception e) {
      ExceptionUtils.throwException(e);
      throw new IllegalStateException("unreachable");
    } finally {
      unlock();
    }
  }

  public void registerJITNotifyCallback(Object id, NotifyCallback notifyCallback) {
    Preconditions.checkNotNull(id);
    try {
      lock();
      List<NotifyCallback> notifyCallbacks = hasJITResult.get(id);
      if (notifyCallbacks == null) {
        notifyCallback.onNotifyMissed();
      } else {
        notifyCallbacks.add(notifyCallback);
      }
    } finally {
      unlock();
    }
  }

  @Internal
  public <T> T asyncVisitJson(
      JsonSharedRegistry sharedRegistry, Function<JsonSharedRegistry, T> function) {
    try {
      lock();
      jsonVisitState++;
      return function.apply(sharedRegistry);
    } finally {
      jsonVisitState--;
      unlock();
    }
  }

  private boolean isAsyncVisitingJson() {
    if (asyncCompilationEnabled) {
      try {
        lock();
        return jsonVisitState != 0;
      } finally {
        unlock();
      }
    }
    return false;
  }

  public boolean hasJITResult(Object key) {
    try {
      lock();
      return hasJITResult.get(key) != null;
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
  public boolean lockedByCurrentThread() {
    return !asyncCompilationEnabled || jitLock.isHeldByCurrentThread();
  }

  @Internal
  public void unlock() {
    if (asyncCompilationEnabled) {
      jitLock.unlock();
    }
  }

  @Internal
  public interface ObjectJITCallback<T> {
    void onSuccess(T result);

    default void onFailure(Throwable e) {
      e.printStackTrace();
      ExceptionUtils.throwException(e);
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
