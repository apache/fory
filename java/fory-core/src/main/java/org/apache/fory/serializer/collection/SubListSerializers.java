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

package org.apache.fory.serializer.collection;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.resolver.TypeResolver;

@SuppressWarnings({"rawtypes", "unchecked"})
public class SubListSerializers {
  private static final Logger LOG = LoggerFactory.getLogger(SubListSerializers.class);

  private static final byte ELEMENTS_PAYLOAD = 0;
  private static final byte VIEW_PAYLOAD = 1;

  private static final Class<?> SubListClass;
  private static final Class<?> RandomAccessSubListClass;
  private static final Class<?> ArrayListSubListClass;
  private static final Class<?> SubAbstractListClass;
  private static final Class<?> SubAbstractListRandomAccessClass;

  private interface Stub {}

  private static final class SubListStub implements Stub {}

  private static final class RandomAccessSubListStub implements Stub {}

  private static final class ArrayListSubListStub implements Stub {}

  private static final class SubAbstractListStub implements Stub {}

  private static final class SubAbstractListRandomAccessStub implements Stub {}

  static {
    SubListClass =
        loadClassOrStub(SubListStub.class, "java.util.SubList", "java.util.AbstractList$SubList");
    RandomAccessSubListClass =
        loadClassOrStub(
            RandomAccessSubListStub.class,
            "java.util.RandomAccessSubList",
            "java.util.AbstractList$RandomAccessSubList");
    ArrayListSubListClass =
        loadClassOrStub(ArrayListSubListStub.class, "java.util.ArrayList$SubList");
    SubAbstractListClass =
        loadClassOrStub(SubAbstractListStub.class, "java.util.AbstractList$SubAbstractList");
    SubAbstractListRandomAccessClass =
        loadClassOrStub(
            SubAbstractListRandomAccessStub.class,
            "java.util.AbstractList$SubAbstractListRandomAccess");
  }

  public static void registerSerializers(TypeResolver classResolver, boolean preserveView) {
    // java.util.ImmutableCollections$SubList is already registered in
    // ImmutableCollectionSerializers
    for (Class<?> cls :
        new Class[] {
          SubListClass,
          RandomAccessSubListClass,
          ArrayListSubListClass,
          SubAbstractListClass,
          SubAbstractListRandomAccessClass
        }) {
      classResolver.registerInternalSerializer(
          cls, new SubListSerializer(classResolver, (Class<List>) cls, preserveView));
    }
  }

  private static Class<?> loadClassOrStub(Class<?> stubClass, String... classNames) {
    for (String className : classNames) {
      try {
        return Class.forName(className);
      } catch (ClassNotFoundException e) {
        // Try the next JDK/libcore spelling.
      }
    }
    return stubClass;
  }

  public static final class SubListSerializer extends CollectionSerializer<List> {
    private final ViewFields viewFields;
    private boolean serializedViewBefore;

    public SubListSerializer(TypeResolver typeResolver, Class<List> type) {
      this(typeResolver, type, false);
    }

    private SubListSerializer(TypeResolver typeResolver, Class<List> type, boolean preserveView) {
      super(typeResolver, type, true);
      viewFields =
          preserveView && typeResolver.trackingRef() && !typeResolver.isCrossLanguage()
              ? ViewFields.create(type)
              : null;
      typeResolver.setSerializer(type, this);
    }

    @Override
    public void write(WriteContext writeContext, List value) {
      ViewPayload viewPayload = getViewPayload(value);
      MemoryBuffer buffer = writeContext.getBuffer();
      if (viewPayload == null) {
        buffer.writeByte(ELEMENTS_PAYLOAD);
        super.write(writeContext, value);
      } else {
        checkViewSerialization(value);
        buffer.writeByte(VIEW_PAYLOAD);
        buffer.writeVarUInt32Small7(viewPayload.offset);
        buffer.writeVarUInt32Small7(viewPayload.size);
        writeContext.writeRef(viewPayload.source);
      }
    }

    @Override
    public List read(ReadContext readContext) {
      byte payloadMode = readContext.getBuffer().readByte();
      if (payloadMode == ELEMENTS_PAYLOAD) {
        return (List) super.read(readContext);
      }
      if (payloadMode == VIEW_PAYLOAD) {
        int offset = readContext.getBuffer().readVarUInt32Small7();
        int size = readContext.getBuffer().readVarUInt32Small7();
        List source = (List) readContext.readRef();
        List value = source.subList(offset, offset + size);
        readContext.reference(value);
        return value;
      }
      throw new DeserializationException("Unknown sublist payload mode: " + payloadMode);
    }

    @Override
    public Collection newCollection(ReadContext readContext) {
      MemoryBuffer buffer = readContext.getBuffer();
      int numElements = readCollectionSize(buffer);
      setNumElements(numElements);
      ArrayList list = new ArrayList(numElements);
      readContext.reference(list);
      return list;
    }

    @Override
    public Collection newCollection(Collection collection) {
      return new ArrayList(collection.size());
    }

    @Override
    public List copy(CopyContext copyContext, List value) {
      if (getViewPayload(value) != null) {
        throw new UnsupportedOperationException(
            "parent list didn't copy modCount, but sublist does copy it");
      }
      return super.copy(copyContext, value);
    }

    private ViewPayload getViewPayload(Collection value) {
      if (viewFields == null) {
        return null;
      }
      return viewFields.get(value);
    }

    private void checkViewSerialization(Object value) {
      if (!serializedViewBefore) {
        serializedViewBefore = true;
        LOG.warn(
            "List view of type {} is being serialized/deserialized. Fory writes the source list, "
                + "offset, and size so JVM and Android readers can parse the same payload.",
            value.getClass());
      }
    }
  }

  private static final class ViewPayload {
    private final List source;
    private final int offset;
    private final int size;

    private ViewPayload(List source, int offset, int size) {
      this.source = source;
      this.offset = offset;
      this.size = size;
    }
  }

  private static final class ViewFields {
    private final FieldAccessor sourceAccessor;
    private final FieldAccessor offsetAccessor;
    private final FieldAccessor sizeAccessor;

    private ViewFields(
        FieldAccessor sourceAccessor, FieldAccessor offsetAccessor, FieldAccessor sizeAccessor) {
      this.sourceAccessor = sourceAccessor;
      this.offsetAccessor = offsetAccessor;
      this.sizeAccessor = sizeAccessor;
    }

    private static ViewFields create(Class<?> type) {
      if (!MemoryUtils.JDK_COLLECTION_FIELD_ACCESS || Stub.class.isAssignableFrom(type)) {
        return null;
      }
      Class<?> cls = type;
      while (cls != null && cls != Object.class) {
        ViewFields viewFields = createFromDeclaredFields(cls);
        if (viewFields != null) {
          return viewFields;
        }
        cls = cls.getSuperclass();
      }
      return null;
    }

    private static ViewFields createFromDeclaredFields(Class<?> type) {
      Field sourceField = null;
      Field fallbackSourceField = null;
      Field offsetField = null;
      Field sizeField = null;
      boolean syntheticRootSource = false;
      int intFields = 0;
      int sourceFields = 0;
      for (Field field : type.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        Class<?> fieldType = field.getType();
        String name = field.getName();
        if (fieldType == int.class) {
          intFields++;
          if ("offset".equals(name)) {
            offsetField = field;
          } else if ("size".equals(name)) {
            sizeField = field;
          }
        } else if (List.class.isAssignableFrom(fieldType)) {
          sourceFields++;
          if ("root".equals(name) || "l".equals(name) || "list".equals(name)) {
            sourceField = field;
            syntheticRootSource = false;
          } else if (sourceField == null && field.isSynthetic() && name.startsWith("this$")) {
            // JDK 8 ArrayList$SubList keeps the root list in the synthetic outer field. Its
            // named "offset" field is root-relative, so do not use the "parent" list field here.
            sourceField = field;
            syntheticRootSource = true;
          } else if (fallbackSourceField == null) {
            fallbackSourceField = field;
          }
        }
      }
      if (sourceField == null && sourceFields == 1) {
        sourceField = fallbackSourceField;
      }
      boolean jdk8ArrayListSubListLayout = syntheticRootSource && intFields == 3;
      if (sourceField == null
          || offsetField == null
          || sizeField == null
          || (intFields != 2 && !jdk8ArrayListSubListLayout)) {
        return null;
      }
      return new ViewFields(
          FieldAccessor.createAccessor(sourceField),
          FieldAccessor.createAccessor(offsetField),
          FieldAccessor.createAccessor(sizeField));
    }

    private ViewPayload get(Collection value) {
      List source = (List) sourceAccessor.getObject(value);
      if (source == null) {
        return null;
      }
      int offset = offsetAccessor.getInt(value);
      int size = sizeAccessor.getInt(value);
      return new ViewPayload(source, offset, size);
    }
  }
}
