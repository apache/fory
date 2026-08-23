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

package org.apache.fory.json.kotlin

import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.fory.codegen.CompileState
import org.apache.fory.json.ForyJson
import org.apache.fory.json.ForyJsonBuilder
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codegen.GeneratedCodecKey
import org.apache.fory.json.codegen.JsonJITContext
import org.apache.fory.reflect.ReflectionUtils
import org.apache.fory.reflect.TypeRef
import org.codehaus.janino.util.ClassFile

internal enum class KotlinJsonTestMode {
  INTERPRETED,
  SYNCHRONOUS,
  ASYNCHRONOUS,
}

internal fun forEachJsonMode(action: (ForyJson) -> Unit) {
  KotlinJsonTestMode.entries.forEach { mode ->
    val json = newKotlinJson(mode)
    action(json)
    if (mode == KotlinJsonTestMode.ASYNCHRONOUS && awaitAsyncCodegen(json)) action(json)
  }
}

internal fun escapedDigits(digits: String): String =
  buildString(digits.length * 6) {
    for (digit in digits) {
      append("\\u00")
      append(digit.code.toString(16).padStart(2, '0'))
    }
  }

internal fun <K> assertEscapedMapKey(
  type: TypeRef<Map<K, String>>,
  key: K,
  digits: String,
  overflow: String,
) {
  val escaped = escapedDigits(digits)
  val escapedOverflow = escapedDigits(overflow)
  forEachJsonMode { json ->
    val latin1 = "{\"$escaped\":\"value\"}"
    val utf16 = "{\"$escaped\":\"雪\"}"
    assertEquals(mapOf(key to "value"), json.fromJson(latin1, type))
    assertEquals(mapOf(key to "雪"), json.fromJson(utf16, type))
    assertEquals(mapOf(key to "value"), json.fromJson(latin1.toByteArray(), type))
    assertFailsWith<ForyJsonException> { json.fromJson("{\"$escapedOverflow\":\"bad\"}", type) }
    assertFailsWith<ForyJsonException> {
      json.fromJson("{\"\\u0078\":\"bad\"}".toByteArray(), type)
    }
  }
}

internal fun newKotlinJson(
  mode: KotlinJsonTestMode,
  configure: ForyJsonBuilder.() -> Unit = {},
): ForyJson {
  val builder = ForyJsonKotlin.builder().apply(configure)
  return when (mode) {
    KotlinJsonTestMode.INTERPRETED -> builder.withCodegen(false).build()
    KotlinJsonTestMode.SYNCHRONOUS -> builder.withCodegen(true).withAsyncCompilation(false).build()
    KotlinJsonTestMode.ASYNCHRONOUS -> builder.withCodegen(true).withAsyncCompilation(true).build()
  }
}

@Suppress("UNCHECKED_CAST")
internal fun generatedClassBytes(json: ForyJson, modelName: String): Map<String, ByteArray> {
  return compileStates(json)
    .values
    .flatMap { finishedResult(it).entries }
    .filter { it.key.contains(modelName) }
    .associate { it.key to it.value }
}

/** Waits for resolver publication and safely verifies every scheduled compilation result. */
@Suppress("UNCHECKED_CAST")
internal fun awaitAsyncCodegen(json: ForyJson): Boolean {
  val slots = ReflectionUtils.getObjectFieldValue(json, "slots") as Array<Any>
  val deadline = System.nanoTime() + 30_000_000_000L
  for (slot in slots) {
    val state = ReflectionUtils.getObjectFieldValue(slot, "state")
    val resolver = ReflectionUtils.getObjectFieldValue(state, "typeResolver")
    val jitContext = ReflectionUtils.getObjectFieldValue(resolver, "jitContext") as JsonJITContext
    while (true) {
      jitContext.lock()
      val active =
        try {
          (ReflectionUtils.getObjectFieldValue(jitContext, "activeTasks") as Set<Any>).isNotEmpty()
        } finally {
          jitContext.unlock()
        }
      if (!active) break
      assertTrue(System.nanoTime() < deadline, "Asynchronous Kotlin JSON publication timed out")
      Thread.yield()
    }
  }
  val states = compileStates(json).values
  states.forEach { finishedResult(it) }
  return states.isNotEmpty()
}

@Suppress("UNCHECKED_CAST")
private fun compileStates(json: ForyJson): Map<String, CompileState> {
  val slots = ReflectionUtils.getObjectFieldValue(json, "slots") as Array<Any>
  val state = ReflectionUtils.getObjectFieldValue(slots[0], "state")
  val resolver = ReflectionUtils.getObjectFieldValue(state, "typeResolver")
  val registry = ReflectionUtils.getObjectFieldValue(resolver, "sharedRegistry")
  val codegen = ReflectionUtils.getObjectFieldValue(registry, "codegen")
  val futures =
    ReflectionUtils.getObjectFieldValue(registry, "generatedClassFutures")
      as Map<GeneratedCodecKey, CompletableFuture<Class<*>?>>
  val generatedNames = futures.values.mapNotNull { it.join()?.name }.toSet()
  val compiler =
    codegen.javaClass.getDeclaredMethod(
      "compiler",
      GeneratedCodecKey::class.java,
      Class::class.java,
      String::class.java,
      String::class.java,
    )
  compiler.isAccessible = true
  return futures.keys
    .map { key -> compiler.invoke(codegen, key, key.targetClass(), "", "") }
    .map { ReflectionUtils.getObjectFieldValue(it, "codeGenerator") }
    .distinct()
    .flatMap {
      (ReflectionUtils.getObjectFieldValue(it, "parallelCompileState") as Map<String, CompileState>)
        .entries
    }
    .filter { it.key in generatedNames }
    .associate { it.toPair() }
}

private fun finishedResult(state: CompileState): Map<String, ByteArray> {
  state.lock.lock()
  return try {
    assertTrue(state.finished, "Asynchronous Kotlin JSON compilation did not finish successfully")
    assertNotNull(state.result, "Asynchronous Kotlin JSON compilation produced no bytecode")
  } finally {
    state.lock.unlock()
  }
}

internal fun generatedMethodRefs(bytes: ByteArray): List<GeneratedMethodRef> {
  val classFile = ClassFile(ByteArrayInputStream(bytes))
  return (1 until classFile.constantPoolSize).mapNotNull { index ->
    val info =
      runCatching { classFile.getConstantPoolInfo(index.toShort()) }.getOrNull()
        as? ClassFile.ConstantMethodrefInfo ?: return@mapNotNull null
    val nameAndType = info.getNameAndType(classFile)
    GeneratedMethodRef(
      info.getClassInfo(classFile).getName(classFile),
      nameAndType.getName(classFile),
      nameAndType.getDescriptor(classFile),
    )
  }
}

internal data class GeneratedMethodRef(
  val owner: String,
  val name: String,
  val descriptor: String,
)
