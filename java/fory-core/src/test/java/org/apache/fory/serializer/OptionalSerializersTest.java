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

package org.apache.fory.serializer;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.testng.annotations.Test;

public class OptionalSerializersTest extends ForyTestBase {

  @Test
  void testOptionals() {
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    serDeCheckSerializerAndEqual(fory, Optional.empty(), "Optional");
    serDeCheckSerializerAndEqual(fory, Optional.of("abc"), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalInt.empty(), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalInt.of(Integer.MIN_VALUE), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalInt.of(Integer.MAX_VALUE), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalLong.empty(), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalLong.of(Long.MIN_VALUE), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalLong.of(Long.MAX_VALUE), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalDouble.empty(), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalDouble.of(Double.MIN_VALUE), "Optional");
    serDeCheckSerializerAndEqual(fory, OptionalDouble.of(Double.MAX_VALUE), "Optional");
  }

  @Test(dataProvider = "foryCopyConfig")
  void testOptionals(Fory fory) {
    copyCheckWithoutSame(fory, Optional.empty());
    copyCheck(fory, Optional.of("abc"));
    copyCheckWithoutSame(fory, OptionalInt.empty());
    copyCheckWithoutSame(fory, OptionalInt.of(Integer.MIN_VALUE));
    copyCheckWithoutSame(fory, OptionalInt.of(Integer.MAX_VALUE));
    copyCheckWithoutSame(fory, OptionalLong.empty());
    copyCheckWithoutSame(fory, OptionalLong.of(Long.MIN_VALUE));
    copyCheckWithoutSame(fory, OptionalLong.of(Long.MAX_VALUE));
    copyCheckWithoutSame(fory, OptionalDouble.empty());
    copyCheckWithoutSame(fory, OptionalDouble.of(Double.MIN_VALUE));
    copyCheckWithoutSame(fory, OptionalDouble.of(Double.MAX_VALUE));
  }
}
