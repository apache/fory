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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import org.apache.fory.annotation.Internal;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.meta.TypeDef;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.serializer.ForyExtraFields.FieldIdentity;
import org.apache.fory.type.Descriptor;
import org.apache.fory.util.record.RecordUtils;

/**
 * Internal bridge between the serialization framework (including generated code) and the {@link
 * ForyExtraFields} sink.
 *
 * <p>Not part of the stable public API: methods here are public only so cross-package framework
 * classes and JIT-generated code can reach them
 */
@Internal
public final class ForyExtraFieldsSupport {

  private static final Logger LOG = LoggerFactory.getLogger(ForyExtraFieldsSupport.class);

  // Lower-bound graph-memory cost of the owners
  private static final int SINK_OWNER_BYTES =
      Math.addExact(
          GraphMemoryEstimates.shallowObjectBytes(ForyExtraFields.class),
          GraphMemoryEstimates.shallowObjectBytes(HashMap.class));

  // Lower-bound graph-memory cost charged per captured entry. Mirrors the map serializers'
  // `numElements * 2 * REFERENCE_BYTES` bound (see {@code MapLikeSerializer.readMapSize})

  private static final int PER_ENTRY_BYTES = 2 * GraphMemoryEstimates.REFERENCE_BYTES;

  private ForyExtraFieldsSupport() {}

  /** Whether extra-field capture/replay can apply under {@code config}. */
  public static boolean isEnabled(Config config) {
    return config.isCompatible() && !config.isXlang();
  }

  public static Field findSinkField(Class<?> cls) {
    FieldAccessor accessor = findSinkAccessor(cls);
    return accessor == null ? null : accessor.getField();
  }

  public static FieldAccessor findSinkAccessor(Class<?> cls) {
    for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
      for (Field f : c.getDeclaredFields()) {
        if (ForyExtraFields.class == f.getType()) {
          // A sink must be a non-static instance field
          if (Modifier.isStatic(f.getModifiers())) {
            warnStaticSink(f);
            continue;
          }
          return FieldAccessor.createAccessor(f);
        }
      }
    }
    return null;
  }

  /**
   * Warns that a {@code static} {@link ForyExtraFields} field cannot serve as a sink. A sink must
   * be a non-static instance field: capture writes into a per-deserialized-instance owner, and
   * {@link FieldAccessor#createAccessor} rejects static fields.
   */
  private static void warnStaticSink(Field staticSink) {
    LOG.warn(
        "Ignoring static {} field {}.{}: an extra-fields sink must be a non-static instance field. "
            + "Declare the sink as a non-static field to capture unmatched remote fields.",
        ForyExtraFields.class.getSimpleName(),
        staticSink.getDeclaringClass().getName(),
        staticSink.getName());
  }

  /**
   * A record cannot host an extra-fields sink: the interpreter and generated read paths build the
   * instance from constructor arguments and cannot capture unmatched fields into an
   * already-immutable record.
   */
  public static void rejectRecordSink(Class<?> cls) {
    if (RecordUtils.isRecord(cls)) {
      throw new UnsupportedOperationException(
          "ForyExtraFields is not supported on records: "
              + cls.getName()
              + ". Extra-field capture requires a mutable owner; declare the "
              + ForyExtraFields.class.getSimpleName()
              + " sink on a regular class instead.");
    }
  }

  /** Returns the remote {@link TypeDef} captured with {@code extraField}, or {@code null}. */
  public static TypeDef getTypeDef(ForyExtraFields extraField) {
    return extraField.getTypeDef();
  }

  public static void capture(
      ReadContext readContext,
      Object target,
      FieldAccessor sinkAccessor,
      TypeDef typeDef,
      FieldIdentity id,
      Object value) {
    ForyExtraFields extraField = (ForyExtraFields) sinkAccessor.getObject(target);
    if (extraField == null) {
      readContext.reserveGraphMemory(SINK_OWNER_BYTES);
      extraField = new ForyExtraFields();
      sinkAccessor.putObject(target, extraField);
    }
    TypeDef existingTypeDef = extraField.getTypeDef();
    if (existingTypeDef == null) {
      extraField.setTypeDef(typeDef);
    } else if (existingTypeDef.getId() != typeDef.getId()) {
      throw new IllegalStateException(
          "Inconsistent remote schema while capturing extra fields on "
              + target.getClass().getName()
              + ": sink already bound to TypeDef id "
              + existingTypeDef.getId()
              + " but field "
              + id
              + " arrived under TypeDef id "
              + typeDef.getId());
    }
    readContext.reserveGraphMemory(PER_ENTRY_BYTES);
    extraField.put(id, value);
  }

  /**
   * Builds the stable identity of a remote field: its fory field id when it has one, otherwise the
   * {@code (declaringClass, name)} pair. Mirrors {@code TypeDef.fieldKey}
   */
  public static FieldIdentity fieldIdentity(Descriptor descriptor) {
    if (descriptor.hasForyFieldId()) {
      return FieldIdentity.ofId(descriptor.getForyFieldId());
    }
    return FieldIdentity.of(descriptor.getDeclaringClass(), descriptor.getName());
  }

  /** Factory reached from generated code for a field written by its fory field id. */
  public static FieldIdentity fieldIdentity(int id) {
    return FieldIdentity.ofId(id);
  }

  /** Factory reached from generated code for a field written by name. */
  public static FieldIdentity fieldIdentity(String declaringClass, String name) {
    return FieldIdentity.of(declaringClass, name);
  }
}
