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

import java.util.List;
import java.util.Map;

public class Example {
  public static class Foo {
    String f1;
    Map<String, Integer> f2;
  }

  public static class Bar {
    Foo f1;
    String f2;
    List<Foo> f3;
    Map<Integer, Foo> f4;
    Integer f5;
    Long f6;
    Float f7;
    Double f8;
    short[] f9;
    List<Long> f10;
  }
}
