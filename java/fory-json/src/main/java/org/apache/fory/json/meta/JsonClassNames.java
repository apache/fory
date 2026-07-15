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

package org.apache.fory.json.meta;

import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;

/** Shared strict validation for binary class names used by generated metadata and R8 rules. */
@Internal
public final class JsonClassNames {
  private JsonClassNames() {}

  public static String requireBinaryName(String name, String source) {
    if (name == null || name.isEmpty()) {
      throw invalid(name, source, "must not be empty");
    }
    if (isPrimitiveOrVoid(name)) {
      throw invalid(name, source, "must name a reference type");
    }
    boolean segmentStart = true;
    for (int offset = 0; offset < name.length(); ) {
      int codePoint = name.codePointAt(offset);
      if (codePoint == '.') {
        if (segmentStart) {
          throw invalid(name, source, "contains an empty segment at " + offset);
        }
        segmentStart = true;
      } else if (segmentStart) {
        if (!Character.isJavaIdentifierStart(codePoint)) {
          throw invalid(name, source, "contains an invalid segment start at " + offset);
        }
        segmentStart = false;
      } else if (!Character.isJavaIdentifierPart(codePoint)) {
        throw invalid(name, source, "contains an invalid code point at " + offset);
      }
      offset += Character.charCount(codePoint);
    }
    if (segmentStart) {
      throw invalid(name, source, "contains an empty trailing segment");
    }
    return name;
  }

  private static boolean isPrimitiveOrVoid(String name) {
    return name.equals("void")
        || name.equals("boolean")
        || name.equals("byte")
        || name.equals("short")
        || name.equals("int")
        || name.equals("long")
        || name.equals("float")
        || name.equals("double")
        || name.equals("char");
  }

  private static ForyJsonException invalid(String name, String source, String reason) {
    return new ForyJsonException(
        "Invalid binary class name " + String.valueOf(name) + " from " + source + ": " + reason);
  }
}
