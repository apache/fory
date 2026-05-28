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

package org.apache.fory.format.encoder;

import static org.apache.fory.type.TypeUtils.getRawType;

import java.lang.invoke.MethodHandle;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.apache.fory.Fory;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.CustomTypeEncoderRegistry;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.SchemaHistory;
import org.apache.fory.format.type.TypeInference;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.type.TypeResolutionContext;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.ExceptionUtils;

public class ArrayCodecBuilder<C extends Collection<?>>
    extends BaseCodecBuilder<ArrayCodecBuilder<C>> {

  private final TypeRef<C> collectionType;
  private final Field elementField;

  ArrayCodecBuilder(final TypeRef<C> collectionType) {
    super(TypeInference.inferSchema(collectionType, false));
    this.collectionType = collectionType;
    elementField = DataTypes.fieldOfSchema(schema, 0);
  }

  public Supplier<ArrayEncoder<C>> build() {
    final Function<BinaryArrayWriter, ArrayEncoder<C>> arrayEncoderFactory = buildWithWriter();
    return new Supplier<ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> get() {
        final BinaryArrayWriter writer = codecFormat.newArrayWriter(elementField);
        return new BufferResettingArrayEncoder<>(
            initialBufferSize, writer, arrayEncoderFactory.apply(writer));
      }
    };
  }

  Function<BinaryArrayWriter, ArrayEncoder<C>> buildWithWriter() {
    loadArrayInnerCodecs();
    if (!schemaEvolution || !isVersionedBeanElement()) {
      final Function<BinaryArrayWriter, GeneratedArrayEncoder> generatedEncoderFactory =
          generatedEncoderFactory();
      return new Function<BinaryArrayWriter, ArrayEncoder<C>>() {
        @Override
        public ArrayEncoder<C> apply(final BinaryArrayWriter writer) {
          return new BinaryArrayEncoder<>(
              writer, generatedEncoderFactory.apply(writer), sizeEmbedded);
        }
      };
    }
    return buildVersionedWithWriter();
  }

  private boolean isVersionedBeanElement() {
    Class<?> elementClass = getRawType(TypeUtils.getElementType(collectionType));
    // Use the same resolution context as the row-format type inference, which synthesizes
    // interface-typed bean fields. Without this, classes that contain interface members
    // would not be recognized as beans even though the row codec can encode them.
    return TypeUtils.isBean(
        TypeRef.of(elementClass),
        new TypeResolutionContext(CustomTypeEncoderRegistry.customTypeHandler(), true));
  }

  private Function<BinaryArrayWriter, ArrayEncoder<C>> buildVersionedWithWriter() {
    Class<?> elementClass = getRawType(TypeUtils.getElementType(collectionType));
    UnaryOperator<Schema> schemaTransform =
        codecFormat == CompactCodecFormat.INSTANCE
            ? CompactBinaryRowWriter::sortSchema
            : UnaryOperator.identity();
    SchemaHistory history = SchemaHistory.build(elementClass, schemaTransform);
    SchemaHistory.VersionedSchema current = history.current();

    // Generate per-version row codec classes and per-version array codec classes.
    Map<Long, ProjectionArrayFactory> projectionFactories = new HashMap<>();
    for (SchemaHistory.VersionedSchema vs : history.versions()) {
      if (vs == current) {
        continue;
      }
      String suffix = "_V" + vs.version();
      Encoders.loadOrGenProjectionRowCodecClass(
          elementClass, codecFormat, vs.schema(), vs.liveFieldNames(), suffix);
      Class<?> arrayClass =
          Encoders.loadOrGenProjectionArrayCodecClass(
              collectionType, TypeRef.of(elementClass), codecFormat, suffix);
      MethodHandle ctor = Encoders.constructorHandleFor(arrayClass, GeneratedArrayEncoder.class);
      // The array's "elementField" is a ListType whose valueField is the element struct. Build
      // a parallel ListType for this historical version so the projection codec can produce a
      // BinaryArray with the right element width.
      Field histValueField =
          DataTypes.field(
              DataTypes.ARRAY_ITEM_NAME, new DataTypes.StructType(vs.schema().fields()), true);
      Field histListField = DataTypes.arrayField(elementField.name(), histValueField);
      projectionFactories.put(vs.strictHash(), new ProjectionArrayFactory(histListField, ctor));
    }
    final Function<BinaryArrayWriter, GeneratedArrayEncoder> currentFactory =
        generatedEncoderFactory();
    long currentHash = current.strictHash();
    return new Function<BinaryArrayWriter, ArrayEncoder<C>>() {
      @Override
      public ArrayEncoder<C> apply(final BinaryArrayWriter writer) {
        Map<Long, BinaryArrayEncoder.ProjectionArrayCodec> proj = new HashMap<>();
        for (Map.Entry<Long, ProjectionArrayFactory> entry : projectionFactories.entrySet()) {
          proj.put(entry.getKey(), entry.getValue().instantiate(fory));
        }
        return new BinaryArrayEncoder<>(
            writer, currentFactory.apply(writer), sizeEmbedded, currentHash, proj);
      }
    };
  }

  private final class ProjectionArrayFactory {
    private final Field elementField;
    private final MethodHandle ctor;

    ProjectionArrayFactory(Field elementField, MethodHandle ctor) {
      this.elementField = elementField;
      this.ctor = ctor;
    }

    BinaryArrayEncoder.ProjectionArrayCodec instantiate(Fory fory) {
      try {
        BinaryArrayWriter projWriter = codecFormat.newArrayWriter(elementField);
        Object[] references = {elementField, projWriter, fory};
        GeneratedArrayEncoder codec = (GeneratedArrayEncoder) ctor.invokeExact(references);
        return new BinaryArrayEncoder.ProjectionArrayCodec(projWriter, codec);
      } catch (Throwable e) {
        throw ExceptionUtils.throwException(e);
      }
    }
  }

  private void loadArrayInnerCodecs() {
    final Set<TypeRef<?>> set = new HashSet<>();
    Encoders.findBeanToken(collectionType, set);
    if (set.isEmpty()) {
      throw new IllegalArgumentException("can not find bean class.");
    }

    for (final TypeRef<?> tt : set) {
      Encoders.loadOrGenRowCodecClass(getRawType(tt), codecFormat);
    }
  }

  Function<BinaryArrayWriter, GeneratedArrayEncoder> generatedEncoderFactory() {
    final TypeRef<?> elementType = TypeUtils.getElementType(collectionType);
    final Class<?> arrayCodecClass =
        Encoders.loadOrGenArrayCodecClass(collectionType, elementType, codecFormat);
    final MethodHandle constructorHandle =
        Encoders.constructorHandleFor(arrayCodecClass, GeneratedArrayEncoder.class);
    return new Function<BinaryArrayWriter, GeneratedArrayEncoder>() {
      @Override
      public GeneratedArrayEncoder apply(final BinaryArrayWriter writer) {
        final Object[] references = {writer.getField(), writer, fory};
        try {
          return (GeneratedArrayEncoder) constructorHandle.invokeExact(references);
        } catch (Throwable t) {
          throw ExceptionUtils.throwException(t);
        }
      }
    };
  }
}
