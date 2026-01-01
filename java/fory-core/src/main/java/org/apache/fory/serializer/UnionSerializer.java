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

import java.util.function.BiFunction;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
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
 *   <li>Value data: the serialized value of the active alternative
 * </ul>
 *
 * <p>The Union type (Union, Union2, etc.) is determined by the declared field type during
 * deserialization, not from the serialized data. This allows cross-language interoperability with
 * union types in other languages like C++'s std::variant, Rust's enum, or Python's typing.Union.
 */
public class UnionSerializer extends Serializer<Union> {
  /** Array of factories for creating Union instances by type tag. */
  @SuppressWarnings("unchecked")
  private static final BiFunction<Integer, Object, Union>[] FACTORIES =
      new BiFunction[] {
        (BiFunction<Integer, Object, Union>) Union::new,
        (BiFunction<Integer, Object, Union>) (index, value) -> Union2.of(index, value),
        (BiFunction<Integer, Object, Union>) (index, value) -> Union3.of(index, value),
        (BiFunction<Integer, Object, Union>) (index, value) -> Union4.of(index, value),
        (BiFunction<Integer, Object, Union>) (index, value) -> Union5.of(index, value),
        (BiFunction<Integer, Object, Union>) (index, value) -> Union6.of(index, value)
      };

  private final BiFunction<Integer, Object, Union> factory;

  @SuppressWarnings("unchecked")
  public UnionSerializer(Fory fory, Class<? extends Union> cls) {
    super(fory, (Class<Union>) cls);
    this.factory = FACTORIES[getTypeIndex(cls)];
  }

  private static int getTypeIndex(Class<? extends Union> cls) {
    if (cls == Union.class) {
      return 0;
    } else if (cls == Union2.class) {
      return 1;
    } else if (cls == Union3.class) {
      return 2;
    } else if (cls == Union4.class) {
      return 3;
    } else if (cls == Union5.class) {
      return 4;
    } else if (cls == Union6.class) {
      return 5;
    } else {
      // Default to base Union for unknown subclasses
      return 0;
    }
  }

  @Override
  public void write(MemoryBuffer buffer, Union union) {
    xwrite(buffer, union);
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
  public Union read(MemoryBuffer buffer) {
    return xread(buffer);
  }

  @Override
  public Union xread(MemoryBuffer buffer) {
    int index = buffer.readVarUint32();
    Object value = fory.xreadRef(buffer);
    return factory.apply(index, value);
  }

  @Override
  public Union copy(Union union) {
    if (union == null) {
      return null;
    }
    Object value = union.getValue();
    Object copiedValue = value != null ? fory.copyObject(value) : null;
    return factory.apply(union.getIndex(), copiedValue);
  }
}
