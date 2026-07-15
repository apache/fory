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

package org.apache.fory.android.json;

import androidx.benchmark.junit4.BenchmarkRule;
import androidx.benchmark.BenchmarkState;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AndroidJsonBenchmark {
  private static volatile Object result;

  @Rule public final BenchmarkRule benchmarkRule = new BenchmarkRule();

  @Test
  public void writeGeneratedMetadataModel() {
    Object benchmark = AndroidJsonRuntimeScenarios.newWriteBenchmark();
    AndroidJsonRuntimeScenarios.writeBenchmark(benchmark);
    BenchmarkState state = benchmarkRule.getState();
    while (state.keepRunning()) {
      result = AndroidJsonRuntimeScenarios.writeBenchmark(benchmark);
    }
  }

  @Test
  public void readGeneratedMetadataModel() {
    Object benchmark = AndroidJsonRuntimeScenarios.newReadBenchmark();
    AndroidJsonRuntimeScenarios.readBenchmark(benchmark);
    BenchmarkState state = benchmarkRule.getState();
    while (state.keepRunning()) {
      result = AndroidJsonRuntimeScenarios.readBenchmark(benchmark);
    }
  }
}
