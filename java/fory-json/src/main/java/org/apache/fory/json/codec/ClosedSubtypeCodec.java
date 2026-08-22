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
import org.apache.fory.json.JsonCodecFactory;
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
import org.apache.fory.reflect.TypeRef;

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
public final class ClosedSubtypeCodec implements CompositeJsonCodec<Object> {
  private final Class<?> baseType;
  private final JsonSubTypesInfo definition;
  private final TypeRef<?> declaredType;
  private final JsonCodecFactory childFactory;
  private final Object[] fixedInstances;
  private final JsonTypeInfo[] children;
  private final ObjectCodec<Object>[] objectCodecs;
  private JsonFieldTable[] inlineReadTables;
  private InlineReader[] fixedInlineReaders;
  private Latin1ReaderCodec<Object>[] inlineLatin1Readers;
  private Utf16ReaderCodec<Object>[] inlineUtf16Readers;
  private Utf8ReaderCodec<Object>[] inlineUtf8Readers;

  /** Creates an unresolved resolver-local dispatcher shell for a validated subtype definition. */
  @Internal
  public ClosedSubtypeCodec(Class<?> baseType, JsonSubTypesInfo definition) {
    this(baseType, definition, null, null);
  }

  /** Creates an unresolved dispatcher with an exact factory-owned declared root type. */
  @Internal
  public ClosedSubtypeCodec(
      Class<?> baseType, JsonSubTypesInfo definition, TypeRef<?> declaredType) {
    this(baseType, definition, declaredType, null);
  }

  /** Creates an unresolved dispatcher with a cold default factory for its children. */
  @Internal
  public ClosedSubtypeCodec(
      Class<?> baseType,
      JsonSubTypesInfo definition,
      TypeRef<?> declaredType,
      JsonCodecFactory childFactory) {
    this(baseType, definition, declaredType, childFactory, null);
  }

  /** Creates an unresolved dispatcher with parent-owned fixed branch values. */
  @Internal
  public ClosedSubtypeCodec(
      Class<?> baseType,
      JsonSubTypesInfo definition,
      TypeRef<?> declaredType,
      JsonCodecFactory childFactory,
      Object[] fixedInstances) {
    if (fixedInstances != null && fixedInstances.length != definition.classes.length) {
      throw new IllegalArgumentException("Subtype branch metadata does not match the definition");
    }
    this.baseType = baseType;
    this.definition = definition;
    this.declaredType = declaredType;
    this.childFactory = childFactory;
    this.fixedInstances = fixedInstances == null ? null : fixedInstances.clone();
    if (fixedInstances != null) {
      for (int i = 0; i < fixedInstances.length; i++) {
        Object fixed = fixedInstances[i];
        if (fixed != null && fixed.getClass() != definition.classes[i]) {
          throw new IllegalArgumentException("Fixed value does not match subtype branch " + i);
        }
      }
    }
    children = new JsonTypeInfo[definition.classes.length];
    objectCodecs =
        definition.inclusion == Inclusion.PROPERTY
            ? (ObjectCodec<Object>[]) new ObjectCodec<?>[children.length]
            : null;
  }

  /** Returns the closed root that owns this codec's discriminator schema. */
  @Internal
  public Class<?> baseType() {
    return baseType;
  }

  /**
   * Resolves every finite subtype branch after this dispatcher's base-type shell is published.
   *
   * <p>Publishing first is required because child metadata can recursively resolve the base type.
   * The caller must hold the resolver's JIT lock, and the resolver owns rollback if this method
   * fails.
   */
  @Internal
  @Override
  public void resolveTypes(TypeRef<?> type, JsonTypeResolver resolver) {
    TypeRef<?> rootType = declaredType == null ? type : declaredType;
    for (int i = 0; i < children.length; i++) {
      Class<?> subtype = definition.classes[i];
      TypeRef<?> childType = rootType.getSubtype(subtype);
      int prior = priorBranch(i, subtype);
      JsonTypeInfo child;
      if (prior >= 0 && fixedInstances[i] != null) {
        ObjectCodec<?> priorCodec = resolver.canonicalObjectCodec(children[prior]);
        child =
            priorCodec != null && priorCodec.fixedInstance()
                ? resolver.createSubtypeLeaf(childType, new FixedBranchCodec(fixedInstances[i]))
                : children[prior];
      } else {
        child =
            resolver.getSubtypeTypeInfo(baseType, childType, declaredType != null, childFactory);
      }
      if (definition.inclusion == Inclusion.PROPERTY) {
        ObjectCodec<?> objectCodec = resolver.canonicalObjectCodec(child);
        if (objectCodec == null) {
          throw new ForyJsonException(
              "Inline JSON subtype requires the default object representation: " + subtype);
        }
        rejectDiscriminatorCollision(objectCodec, definition.scanInfo.property());
        objectCodecs[i] = (ObjectCodec<Object>) objectCodec;
        ObjectCodec.AnyInfo any = objectCodec.anyInfo();
        boolean fixed = objectCodec.fixedInstance();
        if (fixed || any != null && (any.readField() != null || any.readSetter() != null)) {
          if (inlineReadTables == null) {
            inlineReadTables = new JsonFieldTable[children.length];
          }
          JsonFieldTable table =
              objectCodec.readTable().withSkippedName(definition.scanInfo.property());
          inlineReadTables[i] = table;
          if (fixed) {
            if (fixedInlineReaders == null) {
              fixedInlineReaders = new InlineReader[children.length];
            }
            fixedInlineReaders[i] = new FixedInlineReader((ObjectCodec<Object>) objectCodec, table);
          }
          // The subtype scan restores the cursor, so the outer child rereads the discriminator and
          // needs this parent-local skip table. The resolver constructs a complete immutable array
          // under its JIT lock and installs the array as one unit; never publish its elements
          // independently. Nested child values keep the canonical table and capability.
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
    int index = requireSubtype(value);
    if (definition.inclusion == Inclusion.PROPERTY) {
      writer.writeObjectStart();
      writer.writeRawValue(
          definition.stringSubtypePrefixes[index], definition.stringUtf16SubtypePrefixes[index]);
      objectCodecs[index].writeSubtypeMembers(writer, value, 1);
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
    int index = requireSubtype(value);
    if (definition.inclusion == Inclusion.PROPERTY) {
      writer.writeObjectStart();
      writer.writeRawValue(definition.utf8SubtypePrefixes[index]);
      objectCodecs[index].writeSubtypeMembers(writer, value, 1);
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

  private int requireSubtype(Object value) {
    Class<?> runtimeType = value.getClass();
    int index = -1;
    for (int i = 0; i < definition.classes.length; i++) {
      if (definition.classes[i] != runtimeType) {
        continue;
      }
      Object fixed = fixedInstances == null ? null : fixedInstances[i];
      if (fixed == value) {
        return i;
      }
      if (fixed == null && index < 0) {
        index = i;
      }
    }
    if (index < 0) {
      throw new ForyJsonException(
          "Runtime type " + runtimeType.getName() + " is not a declared subtype of " + baseType);
    }
    return index;
  }

  private int priorBranch(int index, Class<?> type) {
    if (fixedInstances == null) {
      return -1;
    }
    for (int i = 0; i < index; i++) {
      if (definition.classes[i] == type) {
        return i;
      }
    }
    return -1;
  }

  /** Parent-local fixed branch used when multiple discriminators share one runtime class. */
  private static final class FixedBranchCodec implements JsonValueCodec<Object> {
    private final Object instance;

    private FixedBranchCodec(Object instance) {
      this.instance = instance;
    }

    @Override
    public void writeString(StringJsonWriter writer, Object value) {
      requireInstance(value);
      writer.writeObjectStart();
      writer.writeObjectEnd();
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value) {
      requireInstance(value);
      writer.writeObjectStart();
      writer.writeObjectEnd();
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      readEmptyObject(reader);
      return instance;
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      readEmptyObject(reader);
      return instance;
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      readEmptyObject(reader);
      return instance;
    }

    private void requireInstance(Object value) {
      if (value != instance) {
        throw new ForyJsonException("Expected closed subtype fixed instance " + instance);
      }
    }

    private static void readEmptyObject(org.apache.fory.json.reader.JsonReader reader) {
      reader.enterDepth();
      reader.expectNextToken('{');
      if (!reader.consumeNextToken('}')) {
        throw new ForyJsonException("Closed subtype fixed instance requires an empty JSON object");
      }
      reader.exitDepth();
    }
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

  /** Returns the table-bound complete reader for a fixed inline branch, if this branch is fixed. */
  @Internal
  public InlineReader fixedInlineReader(int index) {
    return fixedInlineReaders == null ? null : fixedInlineReaders[index];
  }

  /** Complete parent-table-bound reader capability for one fixed inline branch. */
  @Internal
  public interface InlineReader
      extends Latin1ReaderCodec<Object>, Utf16ReaderCodec<Object>, Utf8ReaderCodec<Object> {}

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

  /** One immutable fixed-body capability shared by all three parent-local reader arrays. */
  private static final class FixedInlineReader implements InlineReader {
    private final ObjectCodec<Object> codec;
    private final JsonFieldTable table;

    private FixedInlineReader(ObjectCodec<Object> codec, JsonFieldTable table) {
      this.codec = codec;
      this.table = table;
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      return codec.readLatin1Object(reader, table);
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      return codec.readUtf16Object(reader, table);
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      return codec.readUtf8Object(reader, table);
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
