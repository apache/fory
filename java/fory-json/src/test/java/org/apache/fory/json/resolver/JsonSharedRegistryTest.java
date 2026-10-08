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

package org.apache.fory.json.resolver;

import static org.testng.Assert.assertTrue;

import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.testng.annotations.Test;

public class JsonSharedRegistryTest {
  @Test
  public void validateGeneratedNonRecordCreator() throws Exception {
    CreatorCodec codec = new CreatorCodec();
    JsonSharedRegistry.validateGeneratedCodec(CreatorValue.class, codec);
    assertTrue(codec.matchesCreator(CreatorValue.class.getConstructor(String.class)));
  }

  public static final class CreatorValue {
    @JsonValue public final String value;

    @JsonCreator
    public CreatorValue(String value) {
      this.value = value;
    }
  }

  private static final class CreatorCodec extends GeneratedJsonCodec<CreatorValue> {
    @Override
    public Class<CreatorValue> type() {
      return CreatorValue.class;
    }

    @Override
    public JsonFieldAccessor[] fieldAccessors() {
      return new JsonFieldAccessor[0];
    }

    @Override
    public String[] creatorParameterNames() {
      return new String[] {"value"};
    }

    @Override
    public Class<?>[] creatorParameterTypes() {
      return new Class<?>[] {String.class};
    }

    @Override
    public CreatorValue newInstance(Object[] arguments) {
      return new CreatorValue((String) arguments[0]);
    }
  }
}
