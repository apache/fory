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

package org.apache.fory.util.concurrency;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class ForyJitCompilerThreadFactory implements ThreadFactory {
  private final ThreadFactory backingThreadFactory = Executors.defaultThreadFactory();
  private final AtomicInteger threadNumber = new AtomicInteger(0);

  @Override
  public Thread newThread(Runnable task) {
    Thread thread = backingThreadFactory.newThread(task);
    thread.setName("fory-jit-compiler-" + threadNumber.incrementAndGet());
    thread.setDaemon(true);
    return thread;
  }
}
