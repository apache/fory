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

package org.apache.fory.integration_tests;

import org.apache.fory.Fory;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.test.bean.Foo;

/**
 * A test class that simply references classes from the various Fory artifacts to check whether they
 * specify the module names referenced in the module-info descriptor.
 */
public class Test {

  public static void main(String[] args) {
    final Fory fory = Fory.builder().withCompatible(true).build();
    fory.serialize(Foo.create());

    Encoders.bean(Foo.class, fory);
  }
}
