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

internal data class JvmType(val descriptor: String) {
  init {
    require(parseType(descriptor, 0).second == descriptor.length) {
      "Invalid JVM type descriptor $descriptor"
    }
    require(descriptor != "V") { "void is not a value type" }
  }

  val sourceName: String
    get() = sourceName(descriptor)
}

internal data class JvmMethodDescriptor(val parameters: List<JvmType>, val result: String)

internal fun parseMethodDescriptor(descriptor: String): JvmMethodDescriptor {
  require(descriptor.startsWith('(')) { "Invalid JVM method descriptor $descriptor" }
  var offset = 1
  val parameters = ArrayList<JvmType>()
  while (descriptor[offset] != ')') {
    val parsed = parseType(descriptor, offset)
    parameters += JvmType(descriptor.substring(offset, parsed.second))
    offset = parsed.second
  }
  offset++
  val result = parseType(descriptor, offset, allowVoid = true)
  require(result.second == descriptor.length) { "Invalid JVM method descriptor $descriptor" }
  return JvmMethodDescriptor(parameters, descriptor.substring(offset))
}

internal fun methodDescriptor(parameters: List<JvmType>, result: String): String =
  parameters.joinToString(separator = "", prefix = "(", postfix = ")$result") { it.descriptor }

internal fun appendParameters(descriptor: String, parameters: List<JvmType>): String {
  val end = descriptor.indexOf(')')
  require(end > 0) { "Invalid JVM method descriptor $descriptor" }
  return descriptor.substring(0, end) +
    parameters.joinToString("") { it.descriptor } +
    descriptor.substring(end)
}

private fun parseType(
  descriptor: String,
  start: Int,
  allowVoid: Boolean = false,
): Pair<Char, Int> {
  require(start < descriptor.length) { "Incomplete JVM descriptor $descriptor" }
  var offset = start
  while (descriptor[offset] == '[') {
    offset++
    require(offset < descriptor.length) { "Incomplete JVM descriptor $descriptor" }
  }
  val kind = descriptor[offset]
  val end =
    when (kind) {
      'Z',
      'B',
      'S',
      'I',
      'J',
      'F',
      'D',
      'C' -> offset + 1
      'V' -> {
        require(allowVoid && offset == start) { "void is not valid here in $descriptor" }
        offset + 1
      }
      'L' -> {
        val separator = descriptor.indexOf(';', offset + 1)
        require(separator > offset + 1) { "Invalid JVM object descriptor $descriptor" }
        separator + 1
      }
      else -> error("Invalid JVM descriptor $descriptor")
    }
  return kind to end
}

private fun sourceName(descriptor: String): String =
  when (descriptor[0]) {
    'Z' -> "boolean"
    'B' -> "byte"
    'S' -> "short"
    'I' -> "int"
    'J' -> "long"
    'F' -> "float"
    'D' -> "double"
    'C' -> "char"
    '[' -> sourceName(descriptor.substring(1)) + "[]"
    'L' -> descriptor.substring(1, descriptor.length - 1).replace('/', '.').replace('$', '.')
    else -> error("Invalid JVM type descriptor $descriptor")
  }
