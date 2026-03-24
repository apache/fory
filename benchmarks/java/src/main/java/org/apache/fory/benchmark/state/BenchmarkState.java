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

package org.apache.fory.benchmark.state;

import org.apache.fory.util.StringUtils;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class BenchmarkState {
  private static final boolean DEFAULT_REGISTER_CLASS;

  static {
    String registerClassStr = System.getProperty("REGISTER_CLASS", System.getenv("REGISTER_CLASS"));
    if (StringUtils.isBlank(registerClassStr)) {
      registerClassStr = "true";
    }
    DEFAULT_REGISTER_CLASS = "true".equals(registerClassStr);
  }

  @Param() public BufferType bufferType;

  @Param({"false", "true"})
  public boolean references;

  // for compare with jsonb, jsonb doesn't support register classes.
  public boolean registerClass = DEFAULT_REGISTER_CLASS;
}
