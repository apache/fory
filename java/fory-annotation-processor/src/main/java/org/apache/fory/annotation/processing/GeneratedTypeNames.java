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

package org.apache.fory.annotation.processing;

/** Collision-free generated names for the dependency-free annotation processor. */
final class GeneratedTypeNames {
  private GeneratedTypeNames() {}

  static String escapeBinarySimpleName(String binarySimpleName) {
    return escape(binarySimpleName, false);
  }

  static String escapeBinaryName(String binaryName) {
    return escape(binaryName, true);
  }

  private static String escape(String value, boolean preserveDots) {
    // Keep this encoding identical to fory-core GeneratedClassNames. The processor intentionally
    // has no runtime dependency, so generated source names need this local build-time owner.
    StringBuilder builder = new StringBuilder(value.length() + 32);
    for (int i = 0; i < value.length(); ) {
      int codePoint = value.codePointAt(i);
      if (codePoint == '$') {
        builder.append("_d_");
      } else if (codePoint == '_') {
        builder.append("_u_");
      } else if (preserveDots && codePoint == '.') {
        builder.append('.');
      } else if (Character.isJavaIdentifierPart(codePoint)) {
        builder.appendCodePoint(codePoint);
      } else {
        builder.append("_x").append(Integer.toHexString(codePoint)).append('_');
      }
      i += Character.charCount(codePoint);
    }
    return builder.toString();
  }
}
