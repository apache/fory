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
import org.apache.fory.codegen.CodeGenerator;
import org.apache.fory.format.type.SchemaHistory;

/**
 * Suffix routing shared by row/array/map projection codec generation. Each cross-product entry gets
 * a class-name suffix that uniquely identifies its full nested combination, and the per-nested-bean
 * suffix map directs codegen to embed the right inner projection class for each nested-bean type at
 * this combination's versions.
 */
final class ProjectionRouting {
  /**
   * Longest readable class-name suffix. Each nested versioned bean adds its simple name and a hash
   * to a suffix, and a map codec joins a value and a key suffix, so a wide bean or map could push
   * the source file that {@code FORY_CODE_DIR} writes for the class past the 255-byte file name
   * limit of common file systems. A longer suffix is cut to this length and ends with a hash token
   * instead, so a projection class name is at most 97 characters longer than the name of the
   * current codec class.
   */
  static final int MAX_READABLE_SUFFIX_LENGTH = 80;

  private ProjectionRouting() {}

  /**
   * Build a class-name suffix that uniquely identifies {@code vs} across the whole cross-product,
   * at any nesting depth. The suffix encodes the outer version and strict hash and, for each nested
   * bean, that inner's simple name, version, and strict hash. Each hash is 16 hex digits, so each
   * token has a fixed end, and the name follows the layout it decodes.
   *
   * <p>The cross-product for one outer schema enumerates a fixed set of nested classes, so every
   * combination carries the same classes in the same sorted-by-name positions; two combinations
   * differ only in some inner's chosen (version, hash). Sorting by full class name therefore binds
   * each token position to one class, which keeps the suffix injective even when two nested beans
   * share a simple name and an identical (structural) strict hash. The simple name is for human
   * readability, not disambiguation. Do not switch this to an unordered per-class join: positional
   * stability is what prevents same-simple-name beans from collapsing onto one class name.
   *
   * <p>A suffix longer than {@link #MAX_READABLE_SUFFIX_LENGTH} is cut, and the strict hash of
   * {@code vs} identifies the combination instead; see {@link #bounded}.
   */
  static String projectionSuffix(SchemaHistory.VersionedSchema vs) {
    StringBuilder sb =
        new StringBuilder("_V")
            .append(vs.version())
            .append("h")
            .append(String.format("%016x", vs.strictHash()));
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
            .append(String.format("%016x", inner.strictHash()));
      }
    }
    return bounded(sb.toString(), vs.strictHash());
  }

  /**
   * Class-name suffix for the projection map codec of one (value-combination, key-combination)
   * pair, from the {@link #projectionSuffix} of each position, empty for a position at its current
   * schema. The codec class is cached by name, so distinct pairs must map to distinct suffixes or
   * the second pair would reuse the first pair's codec class. Each half is written after its
   * length, so no token on one side can imitate the boundary between the halves, whatever the bean
   * simple names are. The current-schema codec, with both halves empty, gets no suffix. A suffix
   * longer than {@link #MAX_READABLE_SUFFIX_LENGTH} is cut, and {@code mapHash}, the combined hash
   * that the map payload carries for the pair, identifies it instead.
   */
  static String mapSuffix(String valueSuffix, String keySuffix, long mapHash) {
    if (valueSuffix.isEmpty() && keySuffix.isEmpty()) {
      return "";
    }
    return bounded(
        "_" + valueSuffix.length() + valueSuffix + "_" + keySuffix.length() + keySuffix, mapHash);
  }

  /**
   * {@code readable} if it is at most {@link #MAX_READABLE_SUFFIX_LENGTH} characters long, else its
   * first {@code MAX_READABLE_SUFFIX_LENGTH} characters (one fewer if the cut would split a
   * surrogate pair), then {@code _} and the 16 hex digits of {@code hash}. A cut suffix is longer
   * than every suffix that is not cut, so the two kinds never collide, and {@code hash} must be
   * unique among the suffixes of one codec class.
   */
  private static String bounded(String readable, long hash) {
    if (readable.length() <= MAX_READABLE_SUFFIX_LENGTH) {
      return readable;
    }
    int end = MAX_READABLE_SUFFIX_LENGTH;
    if (Character.isHighSurrogate(readable.charAt(end - 1))) {
      end--;
    }
    return readable.substring(0, end) + "_" + String.format("%016x", hash);
  }

  /**
   * Per-nested-bean-type suffix map for codegen, recursively materializing the inner projection
   * class for each nested bean that a live field of {@code vs} reaches. Projection skips removed
   * slots, so a bean reached only through removed fields gets no entry and no class. Empty string
   * means the inner bean uses its current-version codec class. The chosen inner entry is taken
   * directly from {@code vs}, so this resolves the correct combination to arbitrary depth without
   * re-deriving it from a version number.
   *
   * <p>Called when an outer combination is first compiled (its hash first decoded), so the inner
   * classes are generated lazily alongside it rather than at builder time. {@code codeGenerator} is
   * the generator pinned when the evolution encoder was built; every lazily generated class
   * compiles in it, so an empty-suffix reference resolves to the current-version codec it already
   * defined.
   */
  static Map<Class<?>, String> nestedSuffixesFor(
      SchemaHistory.VersionedSchema vs, Encoding codecFormat, CodeGenerator codeGenerator) {
    Map<Class<?>, String> out = new HashMap<>();
    for (Map.Entry<Class<?>, SchemaHistory.VersionedSchema> e : vs.nestedBeanSchemas().entrySet()) {
      Class<?> innerClass = e.getKey();
      SchemaHistory.VersionedSchema innerVs = e.getValue();
      if (!vs.liveNestedClasses().contains(innerClass)) {
        continue;
      }
      if (innerVs.isCurrent()) {
        out.put(innerClass, "");
      } else {
        String innerSuffix = projectionSuffix(innerVs);
        out.put(innerClass, innerSuffix);
        // Generate the inner's projection class so the outer's `new InnerCodec<suffix>` resolves at
        // class load. Recurses through the inner's own nested combination.
        Encoders.loadOrGenProjectionRowCodecClass(
            innerClass,
            codecFormat,
            innerVs.schema(),
            innerVs.liveFieldNames(),
            innerSuffix,
            nestedSuffixesFor(innerVs, codecFormat, codeGenerator),
            codeGenerator);
      }
    }
    return out;
  }
}
