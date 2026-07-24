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

package org.apache.fory.codegen;

import org.apache.fory.annotation.Internal;

/** Collision-free Java names derived from model binary names. */
@Internal
public final class GeneratedClassNames {
  private GeneratedClassNames() {}

  /** Appends a generated-class suffix after escaping the binary simple name. */
  public static String withSuffix(String binaryName, String suffix) {
    int packageEnd = binaryName.lastIndexOf('.');
    String binarySimpleName = packageEnd < 0 ? binaryName : binaryName.substring(packageEnd + 1);
    String generatedSimpleName = escapeBinarySimpleName(binarySimpleName) + suffix;
    return packageEnd < 0
        ? generatedSimpleName
        : binaryName.substring(0, packageEnd + 1) + generatedSimpleName;
  }

  /** Escapes one binary simple name into a collision-free Java identifier. */
  public static String escapeBinarySimpleName(String binarySimpleName) {
    return escape(binarySimpleName, false);
  }

  /** Escapes every segment of a binary name while preserving package separators. */
  public static String escapeBinaryName(String binaryName) {
    return escape(binaryName, true);
  }

  private static String escape(String value, boolean preserveDots) {
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
