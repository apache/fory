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

package org.apache.fory.json.codec;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.apache.fory.annotation.Internal;

/**
 * Source-generated sealed-subtype schema metadata.
 *
 * <p>This table is trusted build-time schema input, not JSON-controlled class resolution. The
 * shared registry still validates every class, logical name, and security policy before use.
 */
@Internal
public interface GeneratedJsonSubtypeTable {
  /** Returns the exact base described by this table. */
  Class<?> type();

  /** Returns the complete concrete sealed closure in canonical order. */
  Class<?>[] subtypes();

  /** Returns source simple names parallel to {@link #subtypes()}. */
  String[] names();

  /**
   * Hands a Kotlin-source Mixin for a Java sealed root to Java annotation processing.
   *
   * <p>KSP cannot read Java permitted-subclass metadata. This source-only marker keeps Java sealed
   * discovery in the annotation processor while naming the real Mixin that owns the generated pair
   * table. It is trusted build metadata and is absent at runtime.
   */
  @Internal
  @Retention(RetentionPolicy.SOURCE)
  @Target(ElementType.TYPE)
  @interface Generation {
    /** Returns the qualified Kotlin Mixin name that requires Java closure generation. */
    String mixin();
  }
}
