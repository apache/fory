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

import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.JsonCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringObjectWriter;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf8ObjectWriter;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Resolver-local closed subtype dispatcher whose branch slots follow child JsonTypeInfo updates.
 */
@SuppressWarnings("unchecked")
final class ClosedSubtypeCodec implements JsonCodec<Object> {
  private final Class<?> baseType;
  private final JsonSubTypesInfo definition;
  private final JsonTypeInfo[] children;
  private final ObjectCodec<Object>[] objectCodecs;

  ClosedSubtypeCodec(Class<?> baseType, JsonSubTypesInfo definition) {
    this.baseType = baseType;
    this.definition = definition;
    children = new JsonTypeInfo[definition.classes.length];
    objectCodecs =
        definition.wrapperObject
            ? null
            : (ObjectCodec<Object>[]) new ObjectCodec<?>[children.length];
  }

  void resolve(JsonTypeResolver resolver) {
    for (int i = 0; i < children.length; i++) {
      Class<?> subtype = definition.classes[i];
      JsonTypeInfo child = resolver.getTypeInfo(subtype, subtype);
      if (!definition.wrapperObject) {
        if (!child.usesDefaultObjectCodec()) {
          throw new ForyJsonException(
              "Inline JSON subtype requires the default object representation: " + subtype);
        }
        ObjectCodec<?> objectCodec = resolver.getObjectCodec(subtype);
        rejectDiscriminatorCollision(objectCodec, definition.scanInfo.property());
        objectCodecs[i] = (ObjectCodec<Object>) objectCodec;
        // Member-writer generation is requested once while the closed table is resolved. The
        // child capability slots are the single publication owner, so async completion becomes
        // visible to this dispatcher without a second cache or a resolver lookup on every value.
        resolver.stringObjectWriter(objectCodecs[i]);
        resolver.utf8ObjectWriter(objectCodecs[i]);
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
    if (definition.wrapperObject) {
      writer.writeObjectStart();
      writer.writeRawValue(
          definition.stringNamePrefixes[index], definition.stringUtf16NamePrefixes[index]);
      children[index].stringWriter().writeString(writer, value);
      writer.writeObjectEnd();
      return;
    }
    writer.writeObjectStart();
    writer.writeRawValue(
        definition.stringDiscriminators[index], definition.stringUtf16Discriminators[index]);
    stringObjectWriter(index).writeStringMembers(writer, value, 1);
    writer.writeObjectEnd();
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Object value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    int index = requireSubtype(value.getClass());
    if (definition.wrapperObject) {
      writer.writeObjectStart();
      writer.writeRawValue(definition.utf8NamePrefixes[index]);
      children[index].utf8Writer().writeUtf8(writer, value);
      writer.writeObjectEnd();
      return;
    }
    writer.writeObjectStart();
    writer.writeRawValue(definition.utf8Discriminators[index]);
    utf8ObjectWriter(index).writeUtf8Members(writer, value, 1);
    writer.writeObjectEnd();
  }

  @Override
  public Object readLatin1(Latin1JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    if (!definition.wrapperObject) {
      int index = reader.scanObjectStringField(definition.scanInfo);
      return children[index].latin1Reader().readLatin1(reader);
    }
    reader.enterDepth();
    reader.expect('{');
    int index = reader.readSubtypeName(definition.scanInfo);
    reader.expect(':');
    Object value = children[index].latin1Reader().readLatin1(reader);
    reader.expect('}');
    reader.exitDepth();
    return value;
  }

  @Override
  public Object readUtf16(Utf16JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    if (!definition.wrapperObject) {
      int index = reader.scanObjectStringField(definition.scanInfo);
      return children[index].utf16Reader().readUtf16(reader);
    }
    reader.enterDepth();
    reader.expect('{');
    int index = reader.readSubtypeName(definition.scanInfo);
    reader.expect(':');
    Object value = children[index].utf16Reader().readUtf16(reader);
    reader.expect('}');
    reader.exitDepth();
    return value;
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    if (!definition.wrapperObject) {
      int index = reader.scanObjectStringField(definition.scanInfo);
      return children[index].utf8Reader().readUtf8(reader);
    }
    reader.enterDepth();
    reader.expect('{');
    int index = reader.readSubtypeName(definition.scanInfo);
    reader.expect(':');
    Object value = children[index].utf8Reader().readUtf8(reader);
    reader.expect('}');
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

  private StringObjectWriter<Object> stringObjectWriter(int index) {
    StringWriterCodec<Object> writer = children[index].stringWriter();
    return writer instanceof StringObjectWriter
        ? (StringObjectWriter<Object>) writer
        : objectCodecs[index];
  }

  private Utf8ObjectWriter<Object> utf8ObjectWriter(int index) {
    Utf8WriterCodec<Object> writer = children[index].utf8Writer();
    return writer instanceof Utf8ObjectWriter
        ? (Utf8ObjectWriter<Object>) writer
        : objectCodecs[index];
  }

  private static void rejectDiscriminatorCollision(ObjectCodec<?> codec, String property) {
    long hash = org.apache.fory.json.meta.JsonFieldNameHash.hash(property);
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
  }

  private static void rejectCollision(
      String name, long nameHash, String property, long propertyHash, Class<?> subtype) {
    if (name.equals(property) || nameHash == propertyHash) {
      throw new ForyJsonException(
          "Inline discriminator " + property + " collides with property on " + subtype.getName());
    }
  }
}
