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

package org.apache.fory.json.meta;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonTypeUseTest {
  @Test
  public void testNestedExtraction() throws Exception {
    JsonTypeUse values = typeUse("values");
    assertEquals(values.rawType(), List.class);
    assertFalse(values.hasCodec());
    assertTrue(values.hasExplicitCodec());
    assertEquals(values.argument(0).codecClass(), ACodec.class);
    assertEquals(values.argument(0).rawType(), String.class);

    JsonTypeUse array = typeUse("array");
    assertEquals(array.codecClass(), ACodec.class);
    assertNull(array.arrayComponent().codecClass());

    JsonTypeUse elements = typeUse("elements");
    assertNull(elements.codecClass());
    assertEquals(elements.arrayComponent().codecClass(), ACodec.class);
  }

  @Test
  public void testOwnerSubstitution() throws Exception {
    JsonTypeUse owner = typeUse("envelope");
    Field value = Envelope.class.getDeclaredField("value");
    assertNull(JsonTypeUse.forField(value));

    JsonTypeUse resolved =
        JsonTypeUse.resolveMember(
            TypeRef.of(owner.type()), owner, value.getGenericType(), null, "Envelope.value");
    assertEquals(resolved.rawType(), String.class);
    assertEquals(resolved.codecClass(), ACodec.class);
  }

  @Test
  public void testHierarchyProjection() throws Exception {
    JsonTypeUse values = typeUse("reorderedValues");
    JsonTypeUse collection = values.projectTo(Collection.class, "reorderedValues");
    assertEquals(collection.argumentCount(), 1);
    assertEquals(collection.argument(0).rawType(), Integer.class);
    assertEquals(collection.argument(0).codecClass(), ACodec.class);

    JsonTypeUse entries = typeUse("reorderedEntries");
    JsonTypeUse map = entries.projectTo(Map.class, "reorderedEntries");
    assertEquals(map.argument(0).rawType(), String.class);
    assertFalse(map.argument(0).hasExplicitCodec());
    assertEquals(map.argument(1).rawType(), Integer.class);
    assertEquals(map.argument(1).codecClass(), ACodec.class);
  }

  @Test
  public void testMergeAndWildcardRejection() throws Exception {
    JsonTypeUse first = typeUse("values");
    JsonTypeUse same = typeUse("sameValues");
    assertEquals(JsonTypeUse.merge(first, same, "value"), first);

    JsonTypeUse conflicting = typeUse("conflictingValues");
    assertThrows(ForyJsonException.class, () -> JsonTypeUse.merge(first, conflicting, "value"));
    assertThrows(ForyJsonException.class, () -> typeUse("wildcard"));
  }

  private static JsonTypeUse typeUse(String name) throws Exception {
    return JsonTypeUse.forField(Models.class.getDeclaredField(name));
  }

  private static final class Models {
    List<@JsonCodec(ACodec.class) String> values;
    List<@JsonCodec(ACodec.class) String> sameValues;
    List<@JsonCodec(BCodec.class) String> conflictingValues;
    String @JsonCodec(ACodec.class) [] array;

    @JsonCodec(ACodec.class)
    String[] elements;

    Envelope<@JsonCodec(ACodec.class) String> envelope;
    ReorderedValues<String, @JsonCodec(ACodec.class) Integer> reorderedValues;
    ReorderedEntries<@JsonCodec(ACodec.class) Integer, String> reorderedEntries;
    List<@JsonCodec(ACodec.class) ? extends String> wildcard;
  }

  private static final class Envelope<T> {
    T value;
  }

  private static final class ReorderedValues<K, V> extends ArrayList<V> {}

  private static final class ReorderedEntries<V, K> extends HashMap<K, V> {}

  public static final class ACodec extends NullCodec {}

  public static final class BCodec extends NullCodec {}

  public abstract static class NullCodec implements JsonValueCodec<Object> {
    @Override
    public void writeString(StringJsonWriter writer, Object value) {
      writer.writeNull();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value) {
      writer.writeNull();
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      reader.skipValue();
      return null;
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      reader.skipValue();
      return null;
    }
  }
}
