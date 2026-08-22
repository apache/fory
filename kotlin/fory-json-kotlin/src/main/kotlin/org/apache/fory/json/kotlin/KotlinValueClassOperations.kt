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

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.platform.AndroidSupport
import org.apache.fory.platform.internal._JDKAccess

/** Immutable operations that implement one exact value-class layer chain. */
internal interface KotlinValueClassOperations {
  companion object {
    fun create(model: KotlinReflectiveValueClass): KotlinValueClassOperationState =
      if (AndroidSupport.IS_ANDROID) {
        val operations = KotlinReflectionValueClassOperations(model)
        KotlinValueClassOperationState(operations, operations)
      } else {
        KotlinMethodHandleValueClassOperations.createCodec(model)
      }

    fun createMapKey(model: KotlinReflectiveValueClass): KotlinValueClassOperations =
      if (AndroidSupport.IS_ANDROID) {
        KotlinReflectionValueClassOperations(model)
      } else {
        KotlinMethodHandleValueClassOperations.createMapKey(model)
      }
  }
}

internal class KotlinValueClassOperationState(
  val operations: KotlinValueClassOperations,
  val unboxed: KotlinUnboxedValueClassOperations,
)

/** Interpreted and direct-codegen operations for one unboxed parent occurrence. */
internal interface KotlinUnboxedValueClassOperations {
  fun constructCarrier(reader: JsonReader, value: Any?): Any?

  fun extractValue(carrier: Any?): Any?

  fun constructMethods(): Array<Method>

  fun constructBoxBytes(): IntArray

  fun extractMethods(): Array<Method>
}

internal interface KotlinBooleanValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructBoolean(reader: JsonReader, value: Boolean): T

  fun unboxBoolean(value: T): Boolean
}

internal interface KotlinByteValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructByte(reader: JsonReader, value: Byte): T

  fun unboxByte(value: T): Byte
}

internal interface KotlinByteMapKeyOperations<T : Any> : KotlinByteValueClassOperations<T> {
  fun constructByteUncharged(value: Byte): T
}

internal interface KotlinShortValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructShort(reader: JsonReader, value: Short): T

  fun unboxShort(value: T): Short
}

internal interface KotlinShortMapKeyOperations<T : Any> : KotlinShortValueClassOperations<T> {
  fun constructShortUncharged(value: Short): T
}

internal interface KotlinIntValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructInt(reader: JsonReader, value: Int): T

  fun unboxInt(value: T): Int
}

internal interface KotlinIntMapKeyOperations<T : Any> : KotlinIntValueClassOperations<T> {
  fun constructIntUncharged(value: Int): T
}

internal interface KotlinLongValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructLong(reader: JsonReader, value: Long): T

  fun unboxLong(value: T): Long
}

internal interface KotlinLongMapKeyOperations<T : Any> : KotlinLongValueClassOperations<T> {
  fun constructLongUncharged(value: Long): T
}

internal interface KotlinFloatValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructFloat(reader: JsonReader, value: Float): T

  fun unboxFloat(value: T): Float
}

internal interface KotlinDoubleValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructDouble(reader: JsonReader, value: Double): T

  fun unboxDouble(value: T): Double
}

internal interface KotlinCharValueClassOperations<T : Any> : KotlinValueClassOperations {
  fun constructChar(reader: JsonReader, value: Char): T

  fun unboxChar(value: T): Char
}

/** Android interpreted owner for the same exact methods validated by value-class metadata. */
private class KotlinReflectionValueClassOperations(
  private val model: KotlinReflectiveValueClass,
) : KotlinValueClassOperations, KotlinUnboxedValueClassOperations, UnchargedBoxedOperations {
  init {
    try {
      model.constructors.forEach { it.isAccessible = true }
      model.boxes.forEach { it.isAccessible = true }
      model.unboxes.forEach { it.isAccessible = true }
    } catch (cause: RuntimeException) {
      throw failure("bind", cause)
    }
  }

  override fun construct(reader: JsonReader, value: Any?): Any =
    constructValue(reader, value, true)!!

  override fun constructUncharged(value: Any?): Any = constructValue(null, value, true)!!

  override fun unbox(value: Any): Any? {
    var current: Any? = invoke0(model.unboxes[0], value)
    for (index in 1 until model.shape.layers.size) {
      if (model.shape.layers[index - 1].carrierClass == model.shape.layers[index].ownerClass) {
        current = invoke0(model.unboxes[index], current)
      }
    }
    return current
  }

  override fun constructCarrier(reader: JsonReader, value: Any?): Any? =
    constructValue(reader, value, false)

  override fun extractValue(carrier: Any?): Any? {
    var current = carrier
    for (index in 1 until model.shape.layers.size) {
      if (model.shape.layers[index - 1].carrierClass == model.shape.layers[index].ownerClass) {
        current = invoke0(model.unboxes[index], current)
      }
    }
    return current
  }

  override fun constructMethods(): Array<Method> = model.carrierMethods().construct

  override fun constructBoxBytes(): IntArray = model.carrierMethods().boxBytes

  override fun extractMethods(): Array<Method> = model.carrierMethods().extract

  private fun constructValue(reader: JsonReader?, terminal: Any?, boxOuter: Boolean): Any? {
    val layers = model.shape.layers
    var index = layers.lastIndex
    var current = invoke1(model.constructors[index], null, terminal)
    while (index > 0) {
      val inner = layers[index]
      val outer = layers[index - 1]
      if (outer.carrierClass == inner.ownerClass) {
        if (reader != null) reader.reserveGraphMemory(inner.shallowBytes)
        current = invoke1(model.boxes[index], null, current)
      }
      index--
      current = invoke1(model.constructors[index], null, current)
    }
    if (boxOuter) {
      if (reader != null) reader.reserveGraphMemory(layers[0].shallowBytes)
      current = invoke1(model.boxes[0], null, current)
    }
    return current
  }

  private fun invoke0(method: Method, receiver: Any?): Any? =
    try {
      method.invoke(receiver)
    } catch (cause: Throwable) {
      throw failure(method.name, cause)
    }

  private fun invoke1(method: Method, receiver: Any?, argument: Any?): Any? =
    try {
      method.invoke(receiver, argument)
    } catch (cause: Throwable) {
      throw failure(method.name, cause)
    }

  private fun failure(operation: String, cause: Throwable): ForyJsonException {
    val actual = if (cause is InvocationTargetException) cause.cause ?: cause else cause
    if (actual is Error) throw actual
    if (actual is ForyJsonException) return actual
    return ForyJsonException(
      "Kotlin value-class $operation failed for ${model.shape.ownerClass.name}",
      actual,
    )
  }
}

/** HotSpot owner; method discovery and MethodHandle composition finish during codec creation. */
private object KotlinMethodHandleValueClassOperations {
  private val LOOKUP: MethodHandles.Lookup = MethodHandles.lookup()
  private val RESERVE: MethodHandle =
    LOOKUP.findStatic(
      KotlinMethodHandleValueClassOperations::class.java,
      "reserve",
      MethodType.methodType(Void.TYPE, JsonReader::class.java, Int::class.javaPrimitiveType),
    )

  fun createCodec(model: KotlinReflectiveValueClass): KotlinValueClassOperationState {
    val constructors = bind(model.shape, model.constructors, "constructor-impl")
    val boxes = bind(model.shape, model.boxes, "box-impl")
    val unboxes = bind(model.shape, model.unboxes, "unbox-impl")
    val charged = buildConstruct(model.shape, constructors, boxes, true)
    val unbox = buildUnbox(model.shape, unboxes)
    val carrier = model.shape.layers.last().carrierClass
    val invocationCarrier = if (carrier.isPrimitive) carrier else Any::class.java
    val unboxed = createUnboxed(model, constructors, boxes, unboxes)
    return KotlinValueClassOperationState(
      KotlinExactValueClassOperations.createCodec(
        model.shape.ownerClass,
        carrier,
        charged.asType(
          MethodType.methodType(
            Any::class.java,
            JsonReader::class.java,
            invocationCarrier,
          ),
        ),
        unbox.asType(MethodType.methodType(invocationCarrier, Any::class.java)),
      ),
      unboxed,
    )
  }

  fun createMapKey(model: KotlinReflectiveValueClass): KotlinValueClassOperations {
    val constructors = bind(model.shape, model.constructors, "constructor-impl")
    val boxes = bind(model.shape, model.boxes, "box-impl")
    val unboxes = bind(model.shape, model.unboxes, "unbox-impl")
    val charged = buildConstruct(model.shape, constructors, boxes, true)
    val uncharged = buildConstruct(model.shape, constructors, boxes, false)
    val unbox = buildUnbox(model.shape, unboxes)
    val carrier = model.shape.layers.last().carrierClass
    val invocationCarrier = if (carrier.isPrimitive) carrier else Any::class.java
    return KotlinExactValueClassOperations.createMapKey(
      model.shape.ownerClass,
      carrier,
      charged.asType(
        MethodType.methodType(Any::class.java, JsonReader::class.java, invocationCarrier),
      ),
      uncharged.asType(MethodType.methodType(Any::class.java, invocationCarrier)),
      unbox.asType(MethodType.methodType(invocationCarrier, Any::class.java)),
    )
  }

  private fun createUnboxed(
    model: KotlinReflectiveValueClass,
    constructors: List<MethodHandle>,
    boxes: List<MethodHandle>,
    unboxes: List<MethodHandle>,
  ): KotlinUnboxedValueClassOperations {
    val shape = model.shape
    val carrier = shape.layers.first().carrierClass
    val construct =
      buildCarrierConstruct(shape, constructors, boxes)
        .asType(
          MethodType.methodType(
            Any::class.java,
            JsonReader::class.java,
            Any::class.java,
          ),
        )
    val extract =
      buildCarrierExtract(shape, unboxes)
        .asType(MethodType.methodType(Any::class.java, Any::class.java))
    val methods = model.carrierMethods()
    return KotlinExactUnboxedValueOperations.create(
      shape.ownerClass,
      carrier,
      construct,
      extract,
      methods.construct,
      methods.boxBytes,
      methods.extract,
    )
  }

  @JvmStatic
  private fun reserve(reader: JsonReader, bytes: Int) {
    reader.reserveGraphMemory(bytes)
  }

  private fun bind(
    shape: KotlinValueClassShape,
    methods: List<java.lang.reflect.Method>,
    operation: String,
  ): List<MethodHandle> =
    methods.mapIndexed { index, method ->
      try {
        _JDKAccess._trustedLookup(shape.layers[index].ownerClass).unreflect(method)
      } catch (cause: IllegalAccessException) {
        throw ForyJsonException(
          "Cannot bind exact Kotlin value-class $operation for ${shape.layers[index].ownerClass.name}",
          cause,
        )
      }
    }

  private fun buildConstruct(
    shape: KotlinValueClassShape,
    constructors: List<MethodHandle>,
    boxes: List<MethodHandle>,
    charge: Boolean,
  ): MethodHandle {
    val layers = shape.layers
    var index = layers.lastIndex
    var current = constructors[index]
    if (charge) current = MethodHandles.dropArguments(current, 0, JsonReader::class.java)
    while (index > 0) {
      val inner = layers[index]
      val outer = layers[index - 1]
      if (outer.carrierClass == inner.ownerClass) {
        current =
          if (charge) composeCharged(current, boxes[index], inner.shallowBytes)
          else MethodHandles.filterReturnValue(current, boxes[index])
      }
      index--
      current = MethodHandles.filterReturnValue(current, constructors[index])
    }
    return if (charge) composeCharged(current, boxes[0], layers[0].shallowBytes)
    else MethodHandles.filterReturnValue(current, boxes[0])
  }

  private fun buildCarrierConstruct(
    shape: KotlinValueClassShape,
    constructors: List<MethodHandle>,
    boxes: List<MethodHandle>,
  ): MethodHandle {
    val layers = shape.layers
    var index = layers.lastIndex
    var current = MethodHandles.dropArguments(constructors[index], 0, JsonReader::class.java)
    while (index > 0) {
      val inner = layers[index]
      val outer = layers[index - 1]
      if (outer.carrierClass == inner.ownerClass) {
        current = composeCharged(current, boxes[index], inner.shallowBytes)
      }
      index--
      current = MethodHandles.filterReturnValue(current, constructors[index])
    }
    return current
  }

  private fun composeCharged(current: MethodHandle, box: MethodHandle, bytes: Int): MethodHandle {
    val chargedBox = chargedBox(box, bytes)
    val filtered = MethodHandles.collectArguments(chargedBox, 1, current)
    val input = current.type().parameterType(1)
    return MethodHandles.permuteArguments(
      filtered,
      MethodType.methodType(box.type().returnType(), JsonReader::class.java, input),
      0,
      0,
      1,
    )
  }

  private fun chargedBox(box: MethodHandle, bytes: Int): MethodHandle {
    val target = MethodHandles.dropArguments(box, 0, JsonReader::class.java)
    val reserve = MethodHandles.insertArguments(RESERVE, 1, bytes)
    return MethodHandles.foldArguments(target, reserve)
  }

  private fun buildUnbox(
    shape: KotlinValueClassShape,
    unboxes: List<MethodHandle>,
  ): MethodHandle {
    val layers = shape.layers
    var current = unboxes[0]
    for (index in 1 until layers.size) {
      if (layers[index - 1].carrierClass == layers[index].ownerClass) {
        current = MethodHandles.filterReturnValue(current, unboxes[index])
      }
    }
    return current
  }

  private fun buildCarrierExtract(
    shape: KotlinValueClassShape,
    unboxes: List<MethodHandle>,
  ): MethodHandle {
    val layers = shape.layers
    var current = MethodHandles.identity(layers.first().carrierClass)
    for (index in 1 until layers.size) {
      if (layers[index - 1].carrierClass == layers[index].ownerClass) {
        current = MethodHandles.filterReturnValue(current, unboxes[index])
      }
    }
    return current
  }
}
