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

import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassInfoHolder;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.type.Union;

/**
 * Serializer for {@link Union} type.
 *
 * <p>The serialization format is:
 *
 * <ul>
 *   <li>Variant index (varuint32): identifies which alternative type is active
 *   <li>Type info: the type information for the active alternative
 *   <li>Value data: the serialized value of the active alternative
 * </ul>
 *
 * <p>This allows cross-language interoperability with union types in other languages like C++'s
 * std::variant, Rust's enum, or Python's typing.Union.
 */
public class UnionSerializer extends Serializer<Union> {
  private final ClassResolver classResolver;
  private final ClassInfoHolder classInfoHolder;

  public UnionSerializer(Fory fory) {
    super(fory, Union.class);
    this.classResolver = fory.getClassResolver();
    this.classInfoHolder = fory.getClassResolver().nilClassInfoHolder();
  }

  @Override
  public void write(MemoryBuffer buffer, Union union) {
    int activeIndex = union.getActiveIndex();
    buffer.writeVarUint32(activeIndex);

    if (activeIndex >= 0) {
      Object value = union.getValue();
      if (value != null) {
        ClassInfo classInfo = classResolver.getClassInfo(value.getClass(), classInfoHolder);
        classResolver.writeClassInfo(buffer, classInfo);
        fory.writeNonRef(buffer, value, classInfo);
      } else {
        // Write null marker
        buffer.writeByte(Fory.NULL_FLAG);
      }
    }
  }

  @Override
  public Union copy(Union union) {
    if (union == null) {
      return null;
    }
    Union copy = new Union(union.getAlternativeTypes());
    if (union.hasValue()) {
      Object value = union.getValue();
      Object copiedValue = fory.copyObject(value);
      copy.setValueAt(union.getActiveIndex(), copiedValue);
    }
    return copy;
  }

  @Override
  public Union read(MemoryBuffer buffer) {
    int activeIndex = buffer.readVarUint32();

    // We need to read the type info and value, but we don't know the alternative types
    // until we read the Union's metadata. For now, create a Union with Object as alternative.
    if (activeIndex >= 0) {
      int refId = fory.getRefResolver().tryPreserveRefId(buffer);
      if (refId >= Fory.NOT_NULL_VALUE_FLAG) {
        ClassInfo classInfo = classResolver.readClassInfo(buffer, classInfoHolder);
        Object value = classInfo.getSerializer().read(buffer);
        fory.getRefResolver().setReadObject(refId, value);

        Union union = new Union(classInfo.getCls());
        union.setValueAt(0, value);
        return union;
      } else if (refId == Fory.NULL_FLAG) {
        return new Union(Object.class);
      } else {
        Object value = fory.getRefResolver().getReadObject();
        Union union = new Union(value.getClass());
        union.setValue(value);
        return union;
      }
    }

    return new Union(Object.class);
  }

  @Override
  public void xwrite(MemoryBuffer buffer, Union union) {
    int activeIndex = union.getActiveIndex();
    buffer.writeVarUint32(activeIndex);

    if (activeIndex >= 0) {
      Object value = union.getValue();
      if (value != null) {
        // Write type info and value using xlang serialization
        fory.xwriteRef(buffer, value);
      } else {
        // Write null marker
        buffer.writeByte(Fory.NULL_FLAG);
      }
    }
  }

  @Override
  public Union xread(MemoryBuffer buffer) {
    int activeIndex = buffer.readVarUint32();

    if (activeIndex >= 0) {
      // Read the value using xlang deserialization
      Object value = fory.xreadRef(buffer);
      if (value != null) {
        // Create a Union with the value's type as the only alternative
        // In xlang mode, we don't have the original type information,
        // so we use the deserialized value's type
        Union union = new Union(value.getClass());
        union.setValueAt(0, value);
        return union;
      }
    }

    return new Union(Object.class);
  }
}
