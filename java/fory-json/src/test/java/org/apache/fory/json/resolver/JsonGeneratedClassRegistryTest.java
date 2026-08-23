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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.resolver.JsonGeneratedClassRegistry.CompanionKey;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonGeneratedClassRegistryTest {
  @Test
  public void mergeSourceCodecs() {
    TypeRef<?> type = TypeRef.of(String.class);
    CompanionKey key = new CompanionKey(type, null);
    Map<CompanionKey, GeneratedJsonCodec<?>> codecs = new HashMap<>();
    Set<Class<?>> added = new LinkedHashSet<>();
    SourceCodec first = new SourceCodec();
    SourceCodec second = new SourceCodec();
    JsonGeneratedClassRegistry.mergeSourceCodecs(
        Collections.singletonMap(key, first), codecs, added);
    JsonGeneratedClassRegistry.mergeSourceCodecs(
        Collections.singletonMap(key, second), codecs, added);
    assertSame(codecs.get(key), first);
    assertEquals(codecs.get(key).getClass(), SourceCodec.class);
    assertEquals(added, Collections.singleton(SourceCodec.class));
    expectThrows(
        IllegalStateException.class,
        () ->
            JsonGeneratedClassRegistry.mergeSourceCodecs(
                Collections.singletonMap(key, new OtherSourceCodec()), codecs, added));
  }

  @Test
  public void validateGeneratedNonRecordCreator() throws Exception {
    CreatorCodec codec = new CreatorCodec();
    JsonSharedRegistry.validateGeneratedCodec(CreatorValue.class, codec);
    assertTrue(codec.matchesCreator(CreatorValue.class.getConstructor(String.class)));
  }

  private static class SourceCodec extends GeneratedJsonCodec<String> {
    @Override
    public Class<String> type() {
      return String.class;
    }

    @Override
    public JsonFieldAccessor[] fieldAccessors() {
      return new JsonFieldAccessor[0];
    }
  }

  private static final class OtherSourceCodec extends SourceCodec {}

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
