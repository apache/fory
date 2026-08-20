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
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonValue;
import org.apache.fory.json.codec.GeneratedJsonCodec;
import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.meta.TypeExtMeta;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.Types;
import org.testng.annotations.Test;

public class JsonGeneratedClassRegistryTest {
  @Test
  public void mergeSourceCodecs() {
    TypeRef<?> type = TypeRef.of(String.class);
    Map<TypeRef<?>, GeneratedJsonCodec<?>> codecs = new HashMap<>();
    Set<Class<?>> added = new LinkedHashSet<>();
    SourceCodec first = new SourceCodec();
    SourceCodec second = new SourceCodec();
    JsonGeneratedClassRegistry.mergeSourceCodecs(
        Collections.singletonMap(type, first), codecs, added);
    JsonGeneratedClassRegistry.mergeSourceCodecs(
        Collections.singletonMap(type, second), codecs, added);
    assertSame(codecs.get(type), first);
    assertEquals(codecs.get(type).getClass(), SourceCodec.class);
    assertEquals(added, Collections.singleton(SourceCodec.class));
    expectThrows(
        IllegalStateException.class,
        () ->
            JsonGeneratedClassRegistry.mergeSourceCodecs(
                Collections.singletonMap(type, new OtherSourceCodec()), codecs, added));
  }

  @Test
  public void validateGeneratedNonRecordCreator() throws Exception {
    CreatorCodec codec = new CreatorCodec();
    JsonSharedRegistry.validateGeneratedCodec(CreatorValue.class, codec);
    assertTrue(codec.matchesCreator(CreatorValue.class.getConstructor(String.class)));
  }

  @Test
  public void generatedCapabilityType() {
    TypeRef<?> raw = TypeRef.of(String.class);
    TypeRef<?> nonNull = TypeRef.of(String.class, ordinary(false));
    TypeRef<?> nullable = TypeRef.of(String.class, ordinary(true));
    assertEquals(JsonSharedRegistry.generatedCapabilityType(nonNull), raw);
    assertEquals(JsonSharedRegistry.generatedCapabilityType(nullable), raw);

    assertPreserved(TypeExtMeta.of(Types.UINT8, false, false, false, false));
    assertPreserved(TypeExtMeta.of(Types.UNKNOWN, false, true, false, false));
    assertPreserved(TypeExtMeta.of(Types.UNKNOWN, false, false, true, false));
    assertPreserved(TypeExtMeta.of(Types.UNKNOWN, false, false, false, true));

    TypeRef<?> nullableElement = TypeRef.of(String.class, ordinary(true));
    TypeRef<?> list =
        TypeRef.ofDeclaredTypeArguments(
            java.util.List.class,
            ordinary(false),
            Collections.singletonList(nullableElement),
            null);
    TypeRef<?> generated = JsonSharedRegistry.generatedCapabilityType(list);
    assertEquals(generated.getTypeArguments().get(0), nullableElement);
    assertNotEquals(generated, TypeRef.of(list.getType()));

    TypeRef<?> key = TypeRef.of(Integer.class, ordinary(false));
    TypeRef<?> value = TypeRef.of(String.class, ordinary(true));
    List<TypeRef<?>> mapArguments = java.util.Arrays.asList(key, value);
    TypeRef<?> map = TypeRef.ofDeclaredTypeArguments(Map.class, ordinary(true), mapArguments, null);
    assertEquals(JsonSharedRegistry.generatedCapabilityType(map).getTypeArguments(), mapArguments);
  }

  private static void assertPreserved(TypeExtMeta metadata) {
    TypeRef<?> type = TypeRef.of(String.class, metadata);
    assertSame(JsonSharedRegistry.generatedCapabilityType(type), type);
  }

  private static TypeExtMeta ordinary(boolean nullable) {
    return TypeExtMeta.of(Types.UNKNOWN, nullable, false, false, false);
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
