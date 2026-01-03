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

import static org.apache.fory.Fory.NOT_NULL_VALUE_FLAG;

import org.apache.fory.Fory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.ClassInfoHolder;
import org.apache.fory.resolver.ClassResolver;
import org.apache.fory.resolver.RefResolver;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.resolver.XtypeResolver;
import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;

// This polymorphic interface has cost, do not expose it as a public class
// If it's used in other packages in fory, duplicate it in those packages.
@SuppressWarnings({"rawtypes", "unchecked"})
// noinspection Duplicates
abstract class SerializationBinding {
  private static final Logger LOG = LoggerFactory.getLogger(SerializationBinding.class);

  protected final Fory fory;
  protected final RefResolver refResolver;
  protected final TypeResolver typeResolver;

  SerializationBinding(Fory fory) {
    this.fory = fory;
    this.refResolver = fory.getRefResolver();
    typeResolver = fory._getTypeResolver();
  }

  abstract <T> void writeRef(MemoryBuffer buffer, T obj);

  abstract <T> void writeRef(MemoryBuffer buffer, T obj, Serializer<T> serializer);

  abstract void writeRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder);

  abstract void writeRef(MemoryBuffer buffer, Object obj, ClassInfo classInfo);

  abstract void writeNonRef(MemoryBuffer buffer, Object obj);

  abstract void writeNonRef(MemoryBuffer buffer, Object obj, Serializer serializer);

  abstract void writeNonRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder);

  abstract void writeNullable(MemoryBuffer buffer, Object obj);

  abstract void writeNullable(MemoryBuffer buffer, Object obj, Serializer serializer);

  abstract void writeNullable(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder);

  abstract void writeNullable(MemoryBuffer buffer, Object obj, ClassInfo classInfo);

  abstract void writeNullable(
      MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder, boolean nullable);

  abstract void writeNullable(
      MemoryBuffer buffer, Object obj, Serializer serializer, boolean nullable);

  abstract void writeContainerFieldValue(
      MemoryBuffer buffer, Object fieldValue, ClassInfo classInfo);

  abstract void write(MemoryBuffer buffer, Serializer serializer, Object value);

  abstract Object read(MemoryBuffer buffer, Serializer serializer);

  abstract <T> T readRef(MemoryBuffer buffer, Serializer<T> serializer);

  abstract Object readRef(MemoryBuffer buffer, SerializationFieldInfo field);

  abstract Object readRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder);

  abstract Object readRef(MemoryBuffer buffer);

  abstract Object readNonRef(MemoryBuffer buffer);

  abstract Object readNonRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder);

  abstract Object readNonRef(MemoryBuffer buffer, SerializationFieldInfo field);

  abstract Object readNullable(MemoryBuffer buffer, Serializer<Object> serializer);

  abstract Object readNullable(
      MemoryBuffer buffer, Serializer<Object> serializer, boolean nullable);

  abstract Object readContainerFieldValue(MemoryBuffer buffer, SerializationFieldInfo field);

  abstract Object readContainerFieldValueRef(MemoryBuffer buffer, SerializationFieldInfo fieldInfo);

  public int preserveRefId(int refId) {
    return refResolver.preserveRefId(refId);
  }

  void incReadDepth() {
    fory.incReadDepth();
  }

  void incDepth() {
    fory.incDepth();
  }

  void decDepth() {
    fory.decDepth();
  }

  static SerializationBinding createBinding(Fory fory) {
    if (fory.isCrossLanguage()) {
      return new XlangSerializationBinding(fory);
    } else {
      return new JavaSerializationBinding(fory);
    }
  }

  static final class JavaSerializationBinding extends SerializationBinding {
    private final ClassResolver classResolver;

    JavaSerializationBinding(Fory fory) {
      super(fory);
      classResolver = fory.getClassResolver();
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj) {
      fory.writeRef(buffer, obj);
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj, Serializer<T> serializer) {
      fory.writeRef(buffer, obj, serializer);
    }

    @Override
    public void writeRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      fory.writeRef(buffer, obj, classInfoHolder);
    }

    @Override
    public void writeRef(MemoryBuffer buffer, Object obj, ClassInfo classInfo) {
      fory.writeRef(buffer, obj, classInfo);
    }

    @Override
    public <T> T readRef(MemoryBuffer buffer, Serializer<T> serializer) {
      return fory.readRef(buffer, serializer);
    }

    @Override
    public Object readRef(MemoryBuffer buffer, SerializationFieldInfo field) {
      if (field.useDeclaredTypeInfo) {
        return fory.readRef(buffer, field.classInfo.getSerializer());
      }
      return fory.readRef(buffer, field.classInfoHolder);
    }

    @Override
    public Object readRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.readRef(buffer, classInfoHolder);
    }

    @Override
    public Object readRef(MemoryBuffer buffer) {
      return fory.readRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer) {
      return fory.readNonRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.readNonRef(buffer, classInfoHolder);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer, SerializationFieldInfo field) {
      if (field.useDeclaredTypeInfo) {
        return fory.readNonRef(buffer, field.classInfo);
      }
      return fory.readNonRef(buffer, field.classInfoHolder);
    }

    @Override
    public Object readNullable(MemoryBuffer buffer, Serializer<Object> serializer) {
      return fory.readNullable(buffer, serializer);
    }

    @Override
    public Object readNullable(
        MemoryBuffer buffer, Serializer<Object> serializer, boolean nullable) {
      if (nullable) {
        return readNullable(buffer, serializer);
      } else {
        return read(buffer, serializer);
      }
    }

    @Override
    public Object readContainerFieldValue(MemoryBuffer buffer, SerializationFieldInfo field) {
      return fory.readNonRef(buffer, field.classInfoHolder);
    }

    @Override
    public Object readContainerFieldValueRef(
        MemoryBuffer buffer, SerializationFieldInfo fieldInfo) {
      RefResolver refResolver = fory.getRefResolver();
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= NOT_NULL_VALUE_FLAG) {
        // ref value or not-null value
        Object o = fory.readData(buffer, classResolver.readClassInfo(buffer));
        refResolver.setReadObject(nextReadRefId, o);
        return o;
      } else {
        return refResolver.getReadObject();
      }
    }

    @Override
    public void write(MemoryBuffer buffer, Serializer serializer, Object value) {
      serializer.write(buffer, value);
    }

    @Override
    public Object read(MemoryBuffer buffer, Serializer serializer) {
      return serializer.read(buffer);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object obj) {
      fory.writeNonRef(buffer, obj);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object obj, Serializer serializer) {
      fory.writeNonRef(buffer, obj, serializer);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      fory.writeNonRef(buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoHolder));
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        writeNonRef(buffer, obj);
      }
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj, Serializer serializer) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        serializer.write(buffer, obj);
      }
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        fory.writeNonRef(buffer, obj, classResolver.getClassInfo(obj.getClass(), classInfoHolder));
      }
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj, ClassInfo classInfo) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        fory.writeNonRef(buffer, obj, classInfo);
      }
    }

    @Override
    public void writeNullable(
        MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder, boolean nullable) {
      if (nullable) {
        writeNullable(buffer, obj, classInfoHolder);
      } else {
        writeNonRef(buffer, obj, classInfoHolder);
      }
    }

    @Override
    public void writeNullable(
        MemoryBuffer buffer, Object obj, Serializer serializer, boolean nullable) {
      if (nullable) {
        writeNullable(buffer, obj, serializer);
      } else {
        write(buffer, serializer, obj);
      }
    }

    @Override
    public void writeContainerFieldValue(
        MemoryBuffer buffer, Object fieldValue, ClassInfo classInfo) {
      fory.writeNonRef(buffer, fieldValue, classInfo);
    }
  }

  static final class XlangSerializationBinding extends SerializationBinding {
    private final XtypeResolver xtypeResolver;

    XlangSerializationBinding(Fory fory) {
      super(fory);
      xtypeResolver = fory.getXtypeResolver();
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj) {
      fory.xwriteRef(buffer, obj);
    }

    @Override
    public <T> void writeRef(MemoryBuffer buffer, T obj, Serializer<T> serializer) {
      fory.writeRef(buffer, obj, serializer);
    }

    @Override
    public void writeRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      fory.xwriteRef(buffer, obj, classInfoHolder);
    }

    @Override
    public void writeRef(MemoryBuffer buffer, Object obj, ClassInfo classInfo) {
      fory.xwriteRef(buffer, obj, classInfo);
    }

    @Override
    public <T> T readRef(MemoryBuffer buffer, Serializer<T> serializer) {
      return (T) fory.xreadRef(buffer, serializer);
    }

    @Override
    public Object readRef(MemoryBuffer buffer, SerializationFieldInfo field) {
      if (field.isArray) {
        fory.getGenerics().pushGenericType(field.genericType);
        Object o;
        if (field.useDeclaredTypeInfo) {
          o = fory.xreadRef(buffer, field.serializer);
        } else {
          o = fory.xreadRef(buffer);
        }
        fory.getGenerics().popGenericType();
        return o;
      } else {
        if (field.useDeclaredTypeInfo) {
          return fory.xreadRef(buffer, field.serializer);
        } else {
          return fory.xreadRef(buffer);
        }
      }
    }

    @Override
    public Object readRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.xreadRef(buffer, classInfoHolder);
    }

    @Override
    public Object readRef(MemoryBuffer buffer) {
      return fory.xreadRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer) {
      return fory.xreadNonRef(buffer);
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer, ClassInfoHolder classInfoHolder) {
      return fory.xreadNonRef(buffer, xtypeResolver.readClassInfo(buffer, classInfoHolder));
    }

    @Override
    public Object readNonRef(MemoryBuffer buffer, SerializationFieldInfo field) {
      if (field.isArray) {
        fory.getGenerics().pushGenericType(field.genericType);
        Object o;
        if (field.useDeclaredTypeInfo) {
          o = fory.xreadNonRef(buffer, field.serializer);
        } else {
          o = fory.xreadNonRef(buffer);
        }
        fory.getGenerics().popGenericType();
        return o;
      } else {
        if (field.useDeclaredTypeInfo) {
          return fory.xreadNonRef(buffer, field.serializer);
        } else {
          return fory.xreadNonRef(buffer);
        }
      }
    }

    @Override
    public Object readNullable(MemoryBuffer buffer, Serializer<Object> serializer) {
      return fory.xreadNullable(buffer, serializer);
    }

    @Override
    public Object readNullable(
        MemoryBuffer buffer, Serializer<Object> serializer, boolean nullable) {
      if (nullable) {
        return readNullable(buffer, serializer);
      } else {
        return read(buffer, serializer);
      }
    }

    @Override
    public Object readContainerFieldValue(MemoryBuffer buffer, SerializationFieldInfo field) {
      return fory.xreadNonRef(buffer, field.containerClassInfo);
    }

    @Override
    public Object readContainerFieldValueRef(MemoryBuffer buffer, SerializationFieldInfo field) {
      int nextReadRefId = refResolver.tryPreserveRefId(buffer);
      if (nextReadRefId >= NOT_NULL_VALUE_FLAG) {
        Object o = fory.xreadNonRef(buffer, field.containerClassInfo);
        refResolver.setReadObject(nextReadRefId, o);
        return o;
      } else {
        return refResolver.getReadObject();
      }
    }

    @Override
    public void write(MemoryBuffer buffer, Serializer serializer, Object value) {
      serializer.xwrite(buffer, value);
    }

    @Override
    public Object read(MemoryBuffer buffer, Serializer serializer) {
      return serializer.xread(buffer);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object obj) {
      fory.xwriteNonRef(buffer, obj);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object obj, Serializer serializer) {
      fory.xwriteNonRef(buffer, obj, serializer);
    }

    @Override
    public void writeNonRef(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      fory.xwriteNonRef(buffer, obj, xtypeResolver.getClassInfo(obj.getClass(), classInfoHolder));
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        fory.xwriteNonRef(buffer, obj);
      }
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj, Serializer serializer) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        serializer.xwrite(buffer, obj);
      }
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        fory.xwriteNonRef(buffer, obj, xtypeResolver.getClassInfo(obj.getClass(), classInfoHolder));
      }
    }

    @Override
    public void writeNullable(MemoryBuffer buffer, Object obj, ClassInfo classInfo) {
      if (obj == null) {
        buffer.writeByte(Fory.NULL_FLAG);
      } else {
        buffer.writeByte(NOT_NULL_VALUE_FLAG);
        fory.xwriteNonRef(buffer, obj, classInfo);
      }
    }

    @Override
    public void writeNullable(
        MemoryBuffer buffer, Object obj, ClassInfoHolder classInfoHolder, boolean nullable) {
      if (nullable) {
        writeNullable(buffer, obj, classInfoHolder);
      } else {
        writeNonRef(buffer, obj, classInfoHolder);
      }
    }

    @Override
    public void writeNullable(
        MemoryBuffer buffer, Object obj, Serializer serializer, boolean nullable) {
      if (nullable) {
        writeNullable(buffer, obj, serializer);
      } else {
        write(buffer, serializer, obj);
      }
    }

    @Override
    public void writeContainerFieldValue(
        MemoryBuffer buffer, Object fieldValue, ClassInfo classInfo) {
      fory.xwriteData(buffer, classInfo, fieldValue);
    }
  }
}
