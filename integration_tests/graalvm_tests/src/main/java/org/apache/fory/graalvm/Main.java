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

package org.apache.fory.graalvm;

import org.apache.fory.graalvm.record.CompatibleRecordExample;
import org.apache.fory.graalvm.record.RecordExample;
import org.apache.fory.graalvm.record.RecordExample2;

public class Main {
  public static void main(String[] args) throws Throwable {
    Example.main(args);
    XlangExample.main(args);
    CompatibleExample.main(args);
    ScopedCompatibleExample.main(args);
    RecordExample.main(args);
    CompatibleRecordExample.main(args);
    RecordExample2.main(args);

    ThreadSafeExample.main(args);
    CompatibleThreadSafeExample.main(args);
    ProxyExample.main(args);
    ObjectStreamExample.main(args);
    EnsureSerializerExample.main(args);
    Benchmark.main(args);
    CollectionExample.main(args);
    ArrayExample.main(args);
    AbstractClassExample.main(args);
    FeatureTestExample.main(args);
  }
}
