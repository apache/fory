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

package org.apache.fory.json.kotlin.ksp

/** Hands a Kotlin-source Mixin to the Java sealed-hierarchy metadata owner. */
internal object JavaSubtypeGenerationWriter {
  fun write(generation: JavaSubtypeGeneration): String =
    buildString(512) {
      if (generation.packageName.isNotEmpty()) append("package ${generation.packageName};\n\n")
      append("@org.apache.fory.json.codec.GeneratedJsonSubtypeTable.Generation(")
      append("mixin = \"")
      generation.mixinSourceName.forEach { character ->
        when (character) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\b' -> append("\\b")
          '\u000C' -> append("\\f")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          '\t' -> append("\\t")
          else -> append(character)
        }
      }
      append("\")\n")
      append("final class ${generation.simpleName} {}\n")
    }
}
