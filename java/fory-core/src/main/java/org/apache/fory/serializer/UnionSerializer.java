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
import org.apache.fory.type.union.Union;
import org.apache.fory.type.union.Union2;
import org.apache.fory.type.union.Union3;
import org.apache.fory.type.union.Union4;
import org.apache.fory.type.union.Union5;
import org.apache.fory.type.union.Union6;

/**
 * Serializer for {@link Union} and its subclasses ({@link Union2}, {@link Union3}, {@link Union4},
 * {@link Union5}, {@link Union6}).
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
 *
 * <p>Note: When deserializing, this serializer always returns the base {@link Union} class. If you
 * need a specific typed union (e.g., {@link Union2}), you can use {@link Union2#of(int, Object)} to
 * convert.
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
    int index = union.getIndex();
    buffer.writeVarUint32(index);

    Object value = union.getValue();
    if (value != null) {
      ClassInfo classInfo = classResolver.getClassInfo(value.getClass(), classInfoHolder);
      classResolver.writeClassInfo(buffer, classInfo);
      fory.writeNonRef(buffer, value, classInfo);
    } else {
      buffer.writeByte(Fory.NULL_FLAG);
    }
  }

  @Override
  public Union copy(Union union) {
    if (union == null) {
      return null;
    }
    Object value = union.getValue();
    Object copiedValue = value != null ? fory.copyObject(value) : null;
    return new Union(union.getIndex(), copiedValue);
  }

  @Override
  public Union read(MemoryBuffer buffer) {
    int index = buffer.readVarUint32();

    int refId = fory.getRefResolver().tryPreserveRefId(buffer);
    if (refId >= Fory.NOT_NULL_VALUE_FLAG) {
      ClassInfo classInfo = classResolver.readClassInfo(buffer, classInfoHolder);
      Object value = classInfo.getSerializer().read(buffer);
      fory.getRefResolver().setReadObject(refId, value);
      return new Union(index, value);
    } else if (refId == Fory.NULL_FLAG) {
      return new Union(index, null);
    } else {
      Object value = fory.getRefResolver().getReadObject();
      return new Union(index, value);
    }
  }

  @Override
  public void xwrite(MemoryBuffer buffer, Union union) {
    int index = union.getIndex();
    buffer.writeVarUint32(index);

    Object value = union.getValue();
    if (value != null) {
      fory.xwriteRef(buffer, value);
    } else {
      buffer.writeByte(Fory.NULL_FLAG);
    }
  }

  @Override
  public Union xread(MemoryBuffer buffer) {
    int index = buffer.readVarUint32();

    Object value = fory.xreadRef(buffer);
    return new Union(index, value);
  }
}
