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

package org.apache.fory.json.codec;

import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonSubTypes.Inclusion;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Resolver-local closed subtype dispatcher whose branch slots follow child JsonTypeInfo updates.
 *
 * <p>Inline discriminator state belongs to this parent. Any-readable children use parent-local
 * field tables and complete generated-reader arrays so one child can be shared by parents with
 * different discriminator names without changing canonical child metadata. The derived skip table
 * applies only to the outer inline object.
 *
 * <p>Writing rejects fixed-schema discriminator collisions when branches are resolved, but never
 * queries an Any Map. Runtime dynamic-key conflicts are owned by the application; probing here
 * would invoke an Any getter twice and leak parent-specific policy into the child writer.
 */
@Internal
@SuppressWarnings("unchecked")
public final class ClosedSubtypeCodec implements JsonValueCodec<Object> {
  private final Class<?> baseType;
  private final JsonSubTypesInfo definition;
  private final JsonTypeInfo[] children;
  private final ObjectCodec<Object>[] objectCodecs;
  private JsonFieldTable[] inlineReadTables;
  private volatile Latin1ReaderCodec<Object>[] inlineLatin1Readers;
  private volatile Utf16ReaderCodec<Object>[] inlineUtf16Readers;
  private volatile Utf8ReaderCodec<Object>[] inlineUtf8Readers;

  /** Creates an unresolved resolver-local dispatcher shell for a validated subtype definition. */
  @Internal
  public ClosedSubtypeCodec(Class<?> baseType, JsonSubTypesInfo definition) {
    this.baseType = baseType;
    this.definition = definition;
    children = new JsonTypeInfo[definition.classes.length];
    objectCodecs =
        definition.inclusion == Inclusion.PROPERTY
            ? (ObjectCodec<Object>[]) new ObjectCodec<?>[children.length]
            : null;
  }

  /**
   * Resolves every finite subtype branch after this dispatcher's base-type shell is published.
   *
   * <p>Publishing first is required because child metadata can recursively resolve the base type.
   * The caller must hold the resolver's JIT lock, and the resolver owns rollback if this method
   * fails.
   */
  @Internal
  public void resolve(JsonTypeResolver resolver) {
    for (int i = 0; i < children.length; i++) {
      Class<?> subtype = definition.classes[i];
      JsonTypeInfo child = resolver.getTypeInfo(subtype, subtype);
      if (definition.inclusion == Inclusion.PROPERTY) {
        if (resolver.canonicalObjectCodec(child) == null) {
          throw new ForyJsonException(
              "Inline JSON subtype requires the default object representation: " + subtype);
        }
        ObjectCodec<?> objectCodec = resolver.getObjectCodec(subtype);
        rejectDiscriminatorCollision(objectCodec, definition.scanInfo.property());
        objectCodecs[i] = (ObjectCodec<Object>) objectCodec;
        ObjectCodec.AnyInfo any = objectCodec.anyInfo();
        if (any != null && (any.readField() != null || any.readSetter() != null)) {
          if (inlineReadTables == null) {
            inlineReadTables = new JsonFieldTable[children.length];
          }
          JsonFieldTable table =
              objectCodec.readTable().withSkippedName(definition.scanInfo.property());
          inlineReadTables[i] = table;
          // The subtype scan restores the cursor, so the outer child rereads the discriminator and
          // needs this parent-local skip table. The resolver constructs a complete immutable array
          // of generated readers for each representation and publishes that array in one volatile
          // write; never publish its elements independently. Nested child values keep the canonical
          // table and canonical capability.
        }
      }
      children[i] = child;
    }
  }

  @Override
  public void writeString(StringJsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    int index = requireSubtype(value.getClass());
    if (definition.inclusion == Inclusion.PROPERTY) {
      writer.writeObjectStart();
      writer.writeRawValue(
          definition.stringSubtypePrefixes[index], definition.stringUtf16SubtypePrefixes[index]);
      objectCodecs[index].writeMembers(writer, value, 1);
      writer.writeObjectEnd();
      return;
    }
    if (definition.inclusion == Inclusion.WRAPPER_OBJECT) {
      writer.writeObjectStart();
      writer.writeRawValue(
          definition.stringSubtypePrefixes[index], definition.stringUtf16SubtypePrefixes[index]);
      children[index].stringWriter().writeString(writer, value);
      writer.writeObjectEnd();
      return;
    }
    writer.writeArrayStart();
    writer.writeRawValue(
        definition.stringSubtypePrefixes[index], definition.stringUtf16SubtypePrefixes[index]);
    children[index].stringWriter().writeString(writer, value);
    writer.writeArrayEnd();
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    int index = requireSubtype(value.getClass());
    if (definition.inclusion == Inclusion.PROPERTY) {
      writer.writeObjectStart();
      writer.writeRawValue(definition.utf8SubtypePrefixes[index]);
      objectCodecs[index].writeMembers(writer, value, 1);
      writer.writeObjectEnd();
      return;
    }
    if (definition.inclusion == Inclusion.WRAPPER_OBJECT) {
      writer.writeObjectStart();
      writer.writeRawValue(definition.utf8SubtypePrefixes[index]);
      children[index].utf8Writer().writeUtf8(writer, value);
      writer.writeObjectEnd();
      return;
    }
    writer.writeArrayStart();
    writer.writeRawValue(definition.utf8SubtypePrefixes[index]);
    children[index].utf8Writer().writeUtf8(writer, value);
    writer.writeArrayEnd();
  }

  @Override
  public Object readLatin1(Latin1JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    if (definition.inclusion == Inclusion.PROPERTY) {
      int index = reader.scanObjectStringField(definition.scanInfo);
      JsonFieldTable[] tables = inlineReadTables;
      if (tables != null && tables[index] != null) {
        Latin1ReaderCodec<Object>[] readers = inlineLatin1Readers;
        if (readers != null) {
          return readers[index].readLatin1(reader);
        }
        return objectCodecs[index].readLatin1Object(reader, tables[index]);
      }
      return children[index].latin1Reader().readLatin1(reader);
    }
    reader.enterDepth();
    Object value;
    if (definition.inclusion == Inclusion.WRAPPER_OBJECT) {
      reader.expect('{');
      int index = reader.readSubtypeName(definition.scanInfo);
      reader.expect(':');
      value = children[index].latin1Reader().readLatin1(reader);
      reader.expect('}');
    } else {
      reader.expect('[');
      int index = reader.readSubtypeName(definition.scanInfo);
      reader.expect(',');
      value = children[index].latin1Reader().readLatin1(reader);
      reader.expect(']');
    }
    reader.exitDepth();
    return value;
  }

  @Override
  public Object readUtf16(Utf16JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    if (definition.inclusion == Inclusion.PROPERTY) {
      int index = reader.scanObjectStringField(definition.scanInfo);
      JsonFieldTable[] tables = inlineReadTables;
      if (tables != null && tables[index] != null) {
        Utf16ReaderCodec<Object>[] readers = inlineUtf16Readers;
        if (readers != null) {
          return readers[index].readUtf16(reader);
        }
        return objectCodecs[index].readUtf16Object(reader, tables[index]);
      }
      return children[index].utf16Reader().readUtf16(reader);
    }
    reader.enterDepth();
    Object value;
    if (definition.inclusion == Inclusion.WRAPPER_OBJECT) {
      reader.expect('{');
      int index = reader.readSubtypeName(definition.scanInfo);
      reader.expect(':');
      value = children[index].utf16Reader().readUtf16(reader);
      reader.expect('}');
    } else {
      reader.expect('[');
      int index = reader.readSubtypeName(definition.scanInfo);
      reader.expect(',');
      value = children[index].utf16Reader().readUtf16(reader);
      reader.expect(']');
    }
    reader.exitDepth();
    return value;
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    if (definition.inclusion == Inclusion.PROPERTY) {
      int index = reader.scanObjectStringField(definition.scanInfo);
      JsonFieldTable[] tables = inlineReadTables;
      if (tables != null && tables[index] != null) {
        Utf8ReaderCodec<Object>[] readers = inlineUtf8Readers;
        if (readers != null) {
          return readers[index].readUtf8(reader);
        }
        return objectCodecs[index].readUtf8Object(reader, tables[index]);
      }
      return children[index].utf8Reader().readUtf8(reader);
    }
    reader.enterDepth();
    Object value;
    if (definition.inclusion == Inclusion.WRAPPER_OBJECT) {
      reader.expect('{');
      int index = reader.readSubtypeName(definition.scanInfo);
      reader.expect(':');
      value = children[index].utf8Reader().readUtf8(reader);
      reader.expect('}');
    } else {
      reader.expect('[');
      int index = reader.readSubtypeName(definition.scanInfo);
      reader.expect(',');
      value = children[index].utf8Reader().readUtf8(reader);
      reader.expect(']');
    }
    reader.exitDepth();
    return value;
  }

  private int requireSubtype(Class<?> runtimeType) {
    int index = definition.classIndex(runtimeType);
    if (index < 0) {
      throw new ForyJsonException(
          "Runtime type " + runtimeType.getName() + " is not a declared subtype of " + baseType);
    }
    return index;
  }

  @Internal
  public int childCount() {
    return children.length;
  }

  @Internal
  public JsonTypeInfo child(int index) {
    return children[index];
  }

  @Internal
  public JsonFieldTable inlineReadTable(int index) {
    return inlineReadTables == null ? null : inlineReadTables[index];
  }

  @Internal
  public Latin1ReaderCodec<Object>[] inlineLatin1Readers() {
    return inlineLatin1Readers;
  }

  @Internal
  public Utf16ReaderCodec<Object>[] inlineUtf16Readers() {
    return inlineUtf16Readers;
  }

  @Internal
  public Utf8ReaderCodec<Object>[] inlineUtf8Readers() {
    return inlineUtf8Readers;
  }

  @Internal
  public void installInlineLatin1Readers(Latin1ReaderCodec<Object>[] readers) {
    validateInlineReaders(readers);
    if (inlineLatin1Readers != null) {
      throw new IllegalStateException("Inline Latin1 readers are already installed");
    }
    inlineLatin1Readers = readers;
  }

  @Internal
  public void installInlineUtf16Readers(Utf16ReaderCodec<Object>[] readers) {
    validateInlineReaders(readers);
    if (inlineUtf16Readers != null) {
      throw new IllegalStateException("Inline UTF16 readers are already installed");
    }
    inlineUtf16Readers = readers;
  }

  @Internal
  public void installInlineUtf8Readers(Utf8ReaderCodec<Object>[] readers) {
    validateInlineReaders(readers);
    if (inlineUtf8Readers != null) {
      throw new IllegalStateException("Inline UTF8 readers are already installed");
    }
    inlineUtf8Readers = readers;
  }

  private void validateInlineReaders(Object[] readers) {
    if (inlineReadTables == null || readers == null || readers.length != children.length) {
      throw new IllegalArgumentException("Inline reader array does not match subtype branches");
    }
    for (int i = 0; i < children.length; i++) {
      if (inlineReadTables[i] != null && readers[i] == null) {
        throw new IllegalArgumentException("Missing inline reader for subtype branch " + i);
      }
    }
  }

  private static void rejectDiscriminatorCollision(ObjectCodec<?> codec, String property) {
    // Only the statically known child schema is validated here. Do not probe Any output: dynamic
    // discriminator conflicts are application-owned, and invoking its getter here would duplicate
    // access while leaking this parent's policy into the child writer.
    long hash = JsonFieldNameHash.hash(property);
    for (JsonFieldInfo field : codec.writeFields()) {
      rejectCollision(field.name(), field.nameHash(), property, hash, codec.type());
    }
    for (JsonFieldInfo field : codec.readFields()) {
      rejectCollision(field.name(), field.nameHash(), property, hash, codec.type());
    }
    JsonCreatorInfo creator = codec.creatorInfo();
    if (creator != null) {
      for (JsonCreatorFieldInfo field : creator.fields()) {
        rejectCollision(field.name(), field.nameHash(), property, hash, codec.type());
      }
    }
    JsonUnwrappedInfo unwrapped = codec.unwrappedInfo();
    if (unwrapped != null) {
      for (String name : unwrapped.flattenedNames()) {
        rejectCollision(name, JsonFieldNameHash.hash(name), property, hash, codec.type());
      }
    }
  }

  private static void rejectCollision(
      String name, long nameHash, String property, long propertyHash, Class<?> subtype) {
    if (name.equals(property) || nameHash == propertyHash) {
      throw new ForyJsonException(
          "Inline discriminator " + property + " collides with property on " + subtype.getName());
    }
  }
}
