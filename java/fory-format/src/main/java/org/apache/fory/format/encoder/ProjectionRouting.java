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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.format.type.SchemaHistory;

/**
 * Suffix routing shared by row/array/map projection codec generation. Each cross-product entry gets
 * a class-name suffix that uniquely identifies its full nested combination, and the per-nested-bean
 * suffix map directs codegen to embed the right inner projection class for each nested-bean type at
 * this combination's versions.
 */
final class ProjectionRouting {
  private ProjectionRouting() {}

  /**
   * Build a class-name suffix that uniquely identifies {@code vs} across the whole cross-product,
   * at any nesting depth. The suffix encodes the outer version and, for each nested bean, that
   * inner's simple name, version, and its strict hash.
   *
   * <p>The cross-product for one outer schema enumerates a fixed set of nested classes, so every
   * combination carries the same classes in the same sorted-by-name positions; two combinations
   * differ only in some inner's chosen (version, hash). Sorting by full class name therefore binds
   * each token position to one class, which keeps the suffix injective even when two nested beans
   * share a simple name and an identical (structural) strict hash. The simple name is for human
   * readability, not disambiguation. Do not switch this to an unordered per-class join: positional
   * stability is what prevents same-simple-name beans from collapsing onto one class name.
   */
  static String projectionSuffix(SchemaHistory.VersionedSchema vs) {
    StringBuilder sb = new StringBuilder("_V").append(vs.version());
    if (!vs.nestedBeanSchemas().isEmpty()) {
      List<Map.Entry<Class<?>, SchemaHistory.VersionedSchema>> entries =
          new ArrayList<>(vs.nestedBeanSchemas().entrySet());
      entries.sort((a, b) -> a.getKey().getName().compareTo(b.getKey().getName()));
      for (Map.Entry<Class<?>, SchemaHistory.VersionedSchema> e : entries) {
        SchemaHistory.VersionedSchema inner = e.getValue();
        sb.append("_")
            .append(e.getKey().getSimpleName())
            .append(inner.version())
            .append("h")
            .append(Long.toHexString(inner.strictHash()));
      }
    }
    return sb.toString();
  }

  /**
   * Per-nested-bean-type suffix map for codegen, recursively materializing every inner projection
   * class implied by {@code vs}. Empty string means the inner bean uses its current-version codec
   * class. The chosen inner entry is taken directly from {@code vs}, so this resolves the correct
   * combination to arbitrary depth without re-deriving it from a version number.
   */
  static Map<Class<?>, String> nestedSuffixesFor(
      SchemaHistory.VersionedSchema vs, Encoding codecFormat) {
    Map<Class<?>, String> out = new HashMap<>();
    for (Map.Entry<Class<?>, SchemaHistory.VersionedSchema> e : vs.nestedBeanSchemas().entrySet()) {
      Class<?> innerClass = e.getKey();
      SchemaHistory.VersionedSchema innerVs = e.getValue();
      if (innerVs.isCurrent()) {
        out.put(innerClass, "");
      } else {
        String innerSuffix = projectionSuffix(innerVs);
        out.put(innerClass, innerSuffix);
        // Eagerly generate the inner's projection class so the outer's `new InnerCodec<suffix>`
        // resolves at class load. Recurses through the inner's own nested combination.
        Encoders.loadOrGenProjectionRowCodecClass(
            innerClass,
            codecFormat,
            innerVs.schema(),
            innerVs.liveFieldNames(),
            innerSuffix,
            nestedSuffixesFor(innerVs, codecFormat));
      }
    }
    return out;
  }
}
