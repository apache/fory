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

import java.util.ArrayList;
import java.util.Collection;
import org.apache.fory.json.codec.CodecUtils;
import org.apache.fory.json.codec.CollectionCodec;
import org.apache.fory.json.codec.JsonUnwrappedInfo;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.ObjectCodec.AnyInfo;
import org.apache.fory.json.codegen.GeneratedCodecKey;
import org.apache.fory.json.codegen.GeneratedCodecKey.Role;
import org.apache.fory.json.meta.JsonCreatorFieldInfo;
import org.apache.fory.json.meta.JsonCreatorInfo;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.reflect.TypeRef;

/**
 * Collects the model-local inputs of one generated JSON class.
 *
 * <p>Occurrence scanning is deliberately mechanical and one level deep. Keep generator storage,
 * visibility, and source-equivalence decisions in the Writer/Reader generators; conservative key
 * splits are safer than duplicating those decisions here.
 */
final class GeneratedCodecKeyBuilder {
  private enum Part {
    TARGET_FACTORY,
    TARGET_MIXIN,
    UNWRAPPED_FACTORY,
    UNWRAPPED_MIXIN,
    EXACT_CODEC,
    FACTORY,
    MIXIN,
    CYCLE_SLOT
  }

  private final JsonTypeResolver resolver;
  private final ObjectCodec<?> owner;
  private final JsonTypeResolver.CapabilityKind kind;
  private final TypeRef<?> rootBinding;
  private final ArrayList<Object> keyParts;
  private int occurrence;

  private GeneratedCodecKeyBuilder(
      JsonTypeResolver resolver,
      JsonTypeInfo typeInfo,
      ObjectCodec<?> owner,
      JsonTypeResolver.CapabilityKind kind) {
    this.resolver = resolver;
    this.owner = owner;
    this.kind = kind;
    rootBinding = owner.type().getTypeParameters().length == 0 ? null : typeInfo.typeRef();
    keyParts = new ArrayList<>();
    JsonSharedRegistry registry = resolver.sharedRegistry();
    if (!JsonTypeResolver.readerKind(kind)) {
      keyParts.add(registry.writeNullFields());
      keyParts.add(registry.writeLongAsString());
    }
    keyParts.add(registry.propertyDiscoveryEnabled());
    keyParts.add(registry.propertyNamingStrategy());
    addModelInputs(typeInfo, owner.unwrappedInfo(), registry);
    addOccurrences();
  }

  static GeneratedCodecKeyBuilder object(
      JsonTypeResolver resolver,
      JsonTypeInfo typeInfo,
      ObjectCodec<?> owner,
      JsonTypeResolver.CapabilityKind kind) {
    return new GeneratedCodecKeyBuilder(resolver, typeInfo, owner, kind);
  }

  GeneratedCodecKey build() {
    return GeneratedCodecKey.object(owner.type(), rootBinding, role(kind), keyParts.toArray());
  }

  static GeneratedCodecKey collection(
      JsonTypeInfo typeInfo, CollectionCodec<?> owner, JsonTypeResolver.CapabilityKind kind) {
    Class<?> rawType = CodecUtils.rawType(typeInfo.type(), Collection.class);
    Class<?> elementType =
        CodecUtils.rawType(CodecUtils.elementType(typeInfo.type()), Object.class);
    return GeneratedCodecKey.collection(
        rawType,
        typeInfo.typeRef(),
        elementType,
        kind == JsonTypeResolver.CapabilityKind.UTF8_WRITER
            ? Role.UTF8_COLLECTION_WRITER
            : Role.UTF8_COLLECTION_READER,
        owner instanceof CollectionCodec.StringCollectionCodec);
  }

  void addCycleSlots(boolean[] slots) {
    for (int i = 0; i < slots.length; i++) {
      if (slots[i]) {
        keyParts.add(Part.CYCLE_SLOT);
        keyParts.add(i);
      }
    }
  }

  private void addModelInputs(
      JsonTypeInfo typeInfo, JsonUnwrappedInfo unwrapped, JsonSharedRegistry registry) {
    add(Part.TARGET_FACTORY, typeInfo.factoryKey());
    add(Part.TARGET_MIXIN, registry.mixinType(owner.type()));
    if (unwrapped == null) {
      return;
    }
    JsonUnwrappedInfo.Group[] groups = unwrapped.groups();
    for (int i = 0; i < groups.length; i++) {
      ObjectCodec<?> child = groups[i].childCodec();
      add(Part.UNWRAPPED_FACTORY, i, resolver.factoryKey(child));
      add(Part.UNWRAPPED_MIXIN, i, registry.mixinType(child.type()));
    }
  }

  private void addOccurrences() {
    AnyInfo any = owner.anyInfo();
    if (!JsonTypeResolver.readerKind(kind)) {
      JsonFieldInfo[] fields =
          owner.unwrappedInfo() == null ? owner.writeFields() : owner.unwrappedInfo().writeFields();
      for (JsonFieldInfo field : fields) {
        addRegistration(field.writeTypeInfo());
      }
      if (any != null && (any.writeField() != null || any.writeGetter() != null)) {
        addRegistration(any.valueTypeInfo());
      }
      return;
    }

    JsonCreatorInfo creator = owner.creatorInfo();
    if (creator == null) {
      for (JsonFieldInfo field : owner.readFields()) {
        addRegistration(field.readTypeInfo());
      }
    } else {
      for (JsonCreatorFieldInfo field : creator.fields()) {
        addRegistration(field.typeInfo());
      }
    }
    JsonUnwrappedInfo unwrapped = owner.unwrappedInfo();
    if (unwrapped != null) {
      for (JsonUnwrappedInfo.ReadRoute route : unwrapped.readRoutes()) {
        addRegistration(
            route.field() == null ? route.creatorField().typeInfo() : route.field().readTypeInfo());
      }
    }
    if (any != null && (any.readField() != null || any.readSetter() != null)) {
      addRegistration(any.valueTypeInfo());
    }
  }

  private void addRegistration(JsonTypeInfo typeInfo) {
    JsonSharedRegistry registry = resolver.sharedRegistry();
    String factoryKey = typeInfo.factoryKey();
    if (factoryKey != null) {
      keyParts.add(Part.FACTORY);
      keyParts.add(occurrence);
      keyParts.add(factoryKey);
    } else if (typeInfo.exactCodecClass() != null) {
      keyParts.add(Part.EXACT_CODEC);
      keyParts.add(occurrence);
      keyParts.add(typeInfo.exactCodecClass());
    }
    Class<?> mixinType = registry.mixinType(typeInfo.rawType());
    if (mixinType != null) {
      keyParts.add(Part.MIXIN);
      keyParts.add(occurrence);
      keyParts.add(mixinType);
    }
    occurrence++;
  }

  private void add(Part part, Object value) {
    if (value != null) {
      keyParts.add(part);
      keyParts.add(value);
    }
  }

  private void add(Part part, int index, Object value) {
    if (value != null) {
      keyParts.add(part);
      keyParts.add(index);
      keyParts.add(value);
    }
  }

  private static Role role(JsonTypeResolver.CapabilityKind kind) {
    switch (kind) {
      case STRING_WRITER:
        return Role.STRING_WRITER;
      case UTF8_WRITER:
        return Role.UTF8_WRITER;
      case LATIN1_READER:
        return Role.LATIN1_READER;
      case UTF16_READER:
        return Role.UTF16_READER;
      case UTF8_READER:
        return Role.UTF8_READER;
      default:
        throw new IllegalStateException("Unknown JSON capability kind " + kind);
    }
  }
}
