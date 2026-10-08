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

package org.apache.fory.json;

import static org.testng.Assert.assertSame;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.fory.json.resolver.JsonGeneratedClassRegistry;
import org.graalvm.nativeimage.hosted.Feature.BeforeAnalysisAccess;
import org.testng.annotations.Test;

public class ForyJsonGraalVMFeatureTest {
  @Test
  public void registersCompanionEntriesInImageHeap() {
    AtomicReference<Class<?>> registeredType = new AtomicReference<>();
    BeforeAnalysisAccess access =
        (BeforeAnalysisAccess)
            Proxy.newProxyInstance(
                BeforeAnalysisAccess.class.getClassLoader(),
                new Class<?>[] {BeforeAnalysisAccess.class},
                (proxy, method, arguments) -> {
                  if (method.getName().equals("registerAsInHeap")) {
                    registeredType.set((Class<?>) arguments[0]);
                  }
                  return null;
                });

    new ForyJsonGraalVMFeature().beforeAnalysis(access);

    assertSame(registeredType.get(), JsonGeneratedClassRegistry.CompanionEntry.class);
  }
}
