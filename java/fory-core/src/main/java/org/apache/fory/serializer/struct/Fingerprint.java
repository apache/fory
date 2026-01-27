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

package org.apache.fory.serializer.struct;

import java.util.ArrayList;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.resolver.ClassInfo;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.type.Descriptor;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.type.Types;
import org.apache.fory.util.Utils;

public class Fingerprint {
  private static final Logger LOG = LoggerFactory.getLogger(Fingerprint.class);

  /**
   * Computes the fingerprint string for a struct type used in schema versioning.
   *
   * <p><b>Fingerprint Format:</b>
   *
   * <p>Each field contributes: {@code <field_id_or_name>,<type_id>,<ref>,<nullable>;}
   *
   * <p>Fields are sorted by their identifier (field ID or name) lexicographically as strings.
   *
   * <p><b>Field Components:</b>
   *
   * <ul>
   *   <li><b>field_id_or_name</b>: Tag ID as string if configured via {@link ForyField#id()} (e.g.,
   *       "0", "1"), otherwise snake_case field name
   *   <li><b>type_id</b>: Fory TypeId as decimal string (e.g., "4" for INT32)
   *   <li><b>ref</b>: "1" if reference tracking enabled, "0" otherwise
   *   <li><b>nullable</b>: "1" if null flag is written, "0" otherwise
   * </ul>
   *
   * <p><b>Example fingerprints:</b>
   *
   * <ul>
   *   <li>With tag IDs: "0,4,0,0;1,4,0,1;2,9,0,1;"
   *   <li>With field names: "age,4,0,0;name,9,0,1;"
   * </ul>
   *
   * <p>The fingerprint is used to compute a hash for struct schema versioning. Different
   * nullable/ref settings will produce different fingerprints, ensuring schema compatibility is
   * properly validated.
   *
   * @param fory the Fory instance for type resolution
   * @param descriptors the sorted list of field descriptors
   * @return the fingerprint string
   */
  public static String computeStructFingerprint(Fory fory, List<Descriptor> descriptors) {
    // Build fingerprint info for each field
    List<String[]> fieldInfos = new ArrayList<>(descriptors.size());
    for (Descriptor descriptor : descriptors) {
      Class<?> rawType = descriptor.getTypeRef().getRawType();
      int typeId = getTypeId(fory, descriptor);

      // Get field identifier: tag ID if configured, otherwise snake_case name
      String fieldIdentifier;
      ForyField foryField = descriptor.getForyField();
      if (foryField != null && foryField.id() >= 0) {
        fieldIdentifier = String.valueOf(foryField.id());
      } else {
        fieldIdentifier = descriptor.getSnakeCaseName();
      }

      // Get ref flag from @ForyField annotation only (compile-time info)
      // If annotation is absent or ref not explicitly set to true, ref is 0
      // This allows fingerprint to be computed at compile time for C++/Rust
      char ref = (foryField != null && foryField.ref()) ? '1' : '0';

      // Get nullable flag:
      // - Primitives are always non-nullable
      // - For xlang: default is false (except Optional types, boxed types), can be
      //   overridden by @ForyField
      // - For native: use descriptor.isNullable() which defaults to true for non-primitives
      char nullable;
      if (rawType.isPrimitive()) {
        nullable = '0';
      } else if (fory.isCrossLanguage()) {
        // For xlang: nullable defaults to false, except for Optional types, boxed types
        // If @ForyField annotation is present, use its nullable value
        if (foryField != null) {
          nullable = foryField.nullable() ? '1' : '0';
        } else {
          // Default: Optional types, boxed primitives are nullable
          nullable = (TypeUtils.isOptionalType(rawType) || TypeUtils.isBoxed(rawType)) ? '1' : '0';
        }
      } else {
        nullable = descriptor.isNullable() ? '1' : '0';
      }

      fieldInfos.add(
          new String[] {
            fieldIdentifier, String.valueOf(typeId), String.valueOf(ref), String.valueOf(nullable)
          });
    }

    // Sort by field identifier (lexicographically as strings)
    fieldInfos.sort((a, b) -> a[0].compareTo(b[0]));

    // Build fingerprint string
    StringBuilder builder = new StringBuilder();
    for (String[] info : fieldInfos) {
      builder
          .append(info[0])
          .append(',')
          .append(info[1])
          .append(',')
          .append(info[2])
          .append(',')
          .append(info[3])
          .append(';');
    }
    String fingerprint = builder.toString();
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      LOG.info(
          "Fingerprint string for {} is: {}", Descriptor.getDeclareClass(descriptors), fingerprint);
    }
    return fingerprint;
  }

  private static int getTypeId(Fory fory, Descriptor descriptor) {
    Class<?> cls = descriptor.getTypeRef().getRawType();
    TypeResolver resolver = fory._getTypeResolver();
    if (resolver.isSet(cls)) {
      return Types.SET;
    } else if (resolver.isCollection(cls)) {
      return Types.LIST;
    } else if (resolver.isMap(cls)) {
      return Types.MAP;
    } else {
      if (ReflectionUtils.isAbstract(cls) || cls.isInterface() || cls.isEnum()) {
        return Types.UNKNOWN;
      }
      ClassInfo classInfo = resolver.getClassInfo(cls, false);
      if (classInfo == null) {
        return Types.UNKNOWN;
      }
      int typeId = Types.getDescriptorTypeId(fory, descriptor);
      int internalTypeId = typeId & 0xff;
      if (Types.isUnionType(internalTypeId)) {
        return Types.UNION;
      }
      if (Types.isUserDefinedType((byte) internalTypeId)) {
        return Types.UNKNOWN;
      }
      return typeId;
    }
  }
}
