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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.fory.meta.TypeDef;

/**
 * Opt-in sink for compatible-mode extra fields. A class participates by declaring a field of this
 * type; the serialization framework detects it, excludes it from the normal field set, and routes
 * unmatched remote fields into it instead of discarding them.
 *
 * <p>The remote {@link TypeDef} is stored transiently so that on re-serialization the framework can
 * emit the remote schema header and replay all fields in their original order, allowing a
 * downstream peer with the full schema to recover them.
 *
 * <p>Captured fields are keyed by {@link FieldIdentity}
 *
 * <p>Application code has read-only access to captured values through this class ({@link
 * #get(FieldIdentity)}, {@link #containsKey(FieldIdentity)}, {@link #isEmpty}, {@link
 * #fieldIdentities}). Look up a field with {@link FieldIdentity#of(String, String)} when the writer
 * sent field names, or {@link FieldIdentity#ofId(int)} when the field was written by id (e.g.
 * {@code @ForyField(id=…)}). A field written by id has no name on the wire, so it can only be found
 * by id.
 *
 * <p><b>Records are not supported.</b> Capture requires a mutable owner: the framework populates
 * the sink while reading unmatched fields, but a record is built from constructor arguments and is
 * immutable, so there is no instance to populate mid-read. Declaring a {@code ForyExtraFields}
 * component on a record is rejected with an {@link UnsupportedOperationException}.
 */
public final class ForyExtraFields {

  /**
   * The complete, unambiguous identity of a captured remote field. A field is identified either by
   * its fory field id (when it has one) or by its {@code (declaringClass, name)} pair
   */
  public static final class FieldIdentity {
    // For id-based identities: fieldId >= 0, declaringClass and name are null.
    // For name-based identities: fieldId == -1, declaringClass and name are set.
    private final String declaringClass;
    private final String name;
    private final int fieldId;

    private FieldIdentity(String declaringClass, String name, int fieldId) {
      this.declaringClass = declaringClass;
      this.name = name;
      this.fieldId = fieldId;
    }

    /** Identity of a field written by its fory field id (e.g. {@code @ForyField(id=…)}). */
    public static FieldIdentity ofId(int fieldId) {
      return new FieldIdentity(null, null, fieldId);
    }

    /** Identity of a field written by name, qualified by its declaring class. */
    public static FieldIdentity of(String declaringClass, String name) {
      return new FieldIdentity(declaringClass, name, -1);
    }

    public boolean hasFieldId() {
      return fieldId >= 0;
    }

    /** The fory field id; valid only when {@link #hasFieldId()}. */
    public int getFieldId() {
      return fieldId;
    }

    /** The remote declaring class name; {@code null} for id-based identities. */
    public String getDeclaringClass() {
      return declaringClass;
    }

    /** The remote field name; {@code null} for id-based identities. */
    public String getName() {
      return name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FieldIdentity)) {
        return false;
      }
      FieldIdentity other = (FieldIdentity) o;
      return fieldId == other.fieldId
          && Objects.equals(declaringClass, other.declaringClass)
          && Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
      return hasFieldId() ? Integer.hashCode(fieldId) : Objects.hash(declaringClass, name);
    }

    @Override
    public String toString() {
      return hasFieldId() ? "id:" + fieldId : declaringClass + "." + name;
    }
  }

  private final Map<FieldIdentity, Object> fields = new HashMap<>();

  // Excluded from Java serialization because TypeDef is runtime metadata used
  // only for Fory serialization. If a ForyExtraFields instance is serialized
  // with ObjectOutputStream, this field will be null after deserialization, so
  // the captured extra fields cannot later be replayed by Fory.
  private transient TypeDef typeDef;

  public Object get(FieldIdentity id) {
    return fields.get(id);
  }

  public boolean isEmpty() {
    return fields.isEmpty();
  }

  public boolean containsKey(FieldIdentity id) {
    return fields.containsKey(id);
  }

  /** The identities of all captured fields. */
  public Set<FieldIdentity> fieldIdentities() {
    return Collections.unmodifiableSet(fields.keySet());
  }

  Object put(FieldIdentity id, Object value) {
    return fields.put(id, value);
  }

  // Replay uses a sentinel so a captured null value is distinguished from an absent field.
  Object getOrDefault(FieldIdentity id, Object defaultValue) {
    return fields.getOrDefault(id, defaultValue);
  }

  TypeDef getTypeDef() {
    return typeDef;
  }

  void setTypeDef(TypeDef typeDef) {
    this.typeDef = typeDef;
  }
}
