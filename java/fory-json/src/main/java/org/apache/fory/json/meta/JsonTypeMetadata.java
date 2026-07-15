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

/**
 * Permanent base ABI for one build-time generated Fory JSON metadata companion.
 *
 * <p>Generated companions are located by a deterministic binary name. Keep this class, its
 * protected constructor, the section constants, {@link #abiVersion()}, and the three abstract
 * bootstrap methods binary compatible. The compact fact format is separately versioned and may
 * change when {@link #ABI_VERSION} changes.
 */
@Internal
public abstract class JsonTypeMetadata {
  public static final int ABI_VERSION = 1;
  public static final int DECLARATIONS = 0;
  public static final int SUBTYPES = 1;
  public static final int OBJECT = 2;
  public static final int SECTION_COUNT = 3;
  public static final String SUFFIX = "_ForyJsonMetadata";

  private final int abiVersion;

  protected JsonTypeMetadata(
      Class<?> requestedType, String generatedTargetBinaryName, int generatedAbiVersion) {
    if (requestedType == null) {
      throw new ForyJsonException("Generated JSON metadata target must not be null");
    }
    Class<?> generatedTarget;
    try {
      generatedTarget =
          Class.forName(generatedTargetBinaryName, false, getClass().getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      throw new ForyJsonException(
          "Generated JSON metadata cannot resolve target " + generatedTargetBinaryName, e);
    }
    if (generatedTarget != requestedType) {
      throw new ForyJsonException(
          "Generated JSON metadata target mismatch: requested "
              + requestedType.getName()
              + " but companion declares "
              + generatedTarget.getName());
    }
    if (generatedAbiVersion != ABI_VERSION) {
      throw new ForyJsonException(
          "Generated JSON metadata ABI mismatch for "
              + requestedType.getName()
              + ": runtime "
              + ABI_VERSION
              + ", generated "
              + generatedAbiVersion
              + ". Align fory-json and fory-annotation-processor and recompile the model.");
    }
    abiVersion = generatedAbiVersion;
  }

  public final int abiVersion() {
    return abiVersion;
  }

  public abstract Object metadata(int section);

  public abstract Class<?> metadataType(int section, int index);

  public abstract Object metadataOperation(int section, int index);

  /** Returns the generated companion binary name for one target binary name. */
  public static String generatedBinaryName(String targetBinaryName) {
    int packageEnd = targetBinaryName.lastIndexOf('.');
    if (packageEnd < 0) {
      return escapeSimpleName(targetBinaryName) + SUFFIX;
    }
    return targetBinaryName.substring(0, packageEnd + 1)
        + escapeSimpleName(targetBinaryName.substring(packageEnd + 1))
        + SUFFIX;
  }

  /** Returns a collision-free generated resource-name component. */
  public static String escapedResourceName(String targetBinaryName) {
    StringBuilder builder = new StringBuilder(targetBinaryName.length() + 32);
    for (int i = 0; i < targetBinaryName.length(); ) {
      int codePoint = targetBinaryName.codePointAt(i);
      if (codePoint == '.') {
        builder.append('.');
      } else {
        appendEscaped(builder, codePoint);
      }
      i += Character.charCount(codePoint);
    }
    return builder.toString();
  }

  private static String escapeSimpleName(String binarySimpleName) {
    StringBuilder builder = new StringBuilder(binarySimpleName.length() + 32);
    for (int i = 0; i < binarySimpleName.length(); ) {
      int codePoint = binarySimpleName.codePointAt(i);
      appendEscaped(builder, codePoint);
      i += Character.charCount(codePoint);
    }
    return builder.toString();
  }

  private static void appendEscaped(StringBuilder builder, int codePoint) {
    if (codePoint == '$') {
      builder.append('_');
    } else if (codePoint == '_') {
      builder.append("_u_");
    } else if (Character.isJavaIdentifierPart(codePoint)) {
      builder.appendCodePoint(codePoint);
    } else {
      builder.append("_x").append(Integer.toHexString(codePoint)).append('_');
    }
  }
}
