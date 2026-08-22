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

import org.apache.fory.json.ForyJsonException
import org.apache.fory.json.codec.CompositeJsonCodec
import org.apache.fory.json.codec.JsonValueCodec
import org.apache.fory.json.codec.MapCodec
import org.apache.fory.json.codec.MapKeyCodec
import org.apache.fory.json.codec.TransparentNullCodec
import org.apache.fory.json.codec.TransparentUnboxedValueCodec
import org.apache.fory.json.reader.JsonReader
import org.apache.fory.json.reader.Latin1JsonReader
import org.apache.fory.json.reader.Utf16JsonReader
import org.apache.fory.json.reader.Utf8JsonReader
import org.apache.fory.json.resolver.JsonTypeInfo
import org.apache.fory.json.resolver.JsonTypeResolver
import org.apache.fory.json.writer.StringJsonWriter
import org.apache.fory.json.writer.Utf8JsonWriter
import org.apache.fory.reflect.TypeRef

/** Kotlin-owned entry for runtime value-class capabilities. */
internal object KotlinValueClassCodecs {
  fun create(type: TypeRef<*>): CompositeJsonCodec<Any?> {
    val model = KotlinValueClassMetadata.inspect(type)
    val state = KotlinValueClassOperations.create(model)
    return create(model.shape, state.operations, state.unboxed)
  }

  private fun create(
    shape: KotlinValueClassShape,
    operations: KotlinValueClassOperations,
    unboxed: KotlinUnboxedValueClassOperations,
  ): CompositeJsonCodec<Any?> =
    when {
      shape.outerNullable -> NullableOuterCodec(shape, operations, unboxed)
      shape.underlyingNullable -> NullableUnderlyingCodec(shape, operations, unboxed)
      else -> NonNullCodec(shape, operations, unboxed)
    }

  fun createMap(type: TypeRef<*>, resolver: JsonTypeResolver): JsonValueCodec<*> {
    val arguments = type.typeArguments
    if (arguments.size != 2) {
      throw ForyJsonException("Kotlin JSON map ${type.type} requires exact key and value types")
    }
    val keyType = arguments[0]
    val keyCodec = mapKeyCodec(keyType, resolver)
    val valueInfo = resolver.getTypeInfo(arguments[1])
    return MapCodec.createUncheckedKeyCodec(type.rawType, keyType.rawType, valueInfo, keyCodec)
  }

  fun mapKeyCodec(type: TypeRef<*>, resolver: JsonTypeResolver): MapKeyCodec {
    val model = KotlinValueClassMetadata.inspect(type)
    val shape = model.shape
    shape.requireMapKey()
    for (layer in shape.layers) resolver.checkMapKeySecure(layer.ownerClass)
    resolver.checkMapKeySecure(shape.terminalType.rawType)
    return KotlinValueClassMapKeys.create(
      shape,
      KotlinValueClassOperations.createMapKey(model),
      terminalMapKey(shape.terminalType),
    )
  }

  private fun terminalMapKey(type: TypeRef<*>): MapKeyCodec {
    KotlinMapKeyCodecs.keyCodec(type)?.let {
      return it
    }
    val rawType = type.rawType
    if (rawType != String::class.java && !rawType.isEnum && !signedMapKey(rawType)) {
      throw ForyJsonException("Unsupported Kotlin JSON value-class map key terminal ${type.type}")
    }
    return MapCodec.keyCodec(rawType)
  }

  private fun signedMapKey(type: Class<*>): Boolean =
    type == Byte::class.javaPrimitiveType ||
      type == Byte::class.javaObjectType ||
      type == Short::class.javaPrimitiveType ||
      type == Short::class.javaObjectType ||
      type == Int::class.javaPrimitiveType ||
      type == Int::class.javaObjectType ||
      type == Long::class.javaPrimitiveType ||
      type == Long::class.javaObjectType
}

internal interface KotlinValueClassCapability {
  fun writeString(writer: StringJsonWriter, value: Any)

  fun writeUtf8(writer: Utf8JsonWriter, value: Any)

  fun readLatin1(reader: Latin1JsonReader): Any

  fun readUtf16(reader: Utf16JsonReader): Any

  fun readUtf8(reader: Utf8JsonReader): Any
}

private abstract class KotlinValueClassCodec(
  protected val shape: KotlinValueClassShape,
  private val operations: KotlinValueClassOperations,
  private val unboxedOperations: KotlinUnboxedValueClassOperations,
) : CompositeJsonCodec<Any?>, TransparentUnboxedValueCodec {
  private var capability: KotlinValueClassCapability = UnresolvedValueClassCapability
  private var terminalTypeInfo: JsonTypeInfo? = null

  final override fun resolveTypes(type: TypeRef<*>, resolver: JsonTypeResolver) {
    if (type != shape.ownerType) {
      throw ForyJsonException(
        "Kotlin value-class codec owner ${shape.ownerType} cannot bind child for $type",
      )
    }
    for (layer in shape.layers) resolver.checkSecure(layer.ownerClass)
    val child = resolver.getTypeInfo(shape.terminalType)
    terminalTypeInfo = child
    capability = KotlinValueClassCapabilities.bind(shape, operations, child)
  }

  final override fun carrierType(): Class<*> = shape.layers.first().carrierClass

  final override fun valueTypeInfo(): JsonTypeInfo =
    terminalTypeInfo
      ?: throw IllegalStateException("Kotlin value-class terminal capability is not resolved")

  final override fun constructCarrier(reader: JsonReader, value: Any?): Any? =
    unboxedOperations.constructCarrier(reader, value)

  final override fun extractValue(carrier: Any?): Any? = unboxedOperations.extractValue(carrier)

  final override fun constructMethods(): Array<java.lang.reflect.Method> =
    unboxedOperations.constructMethods()

  final override fun constructBoxBytes(): IntArray = unboxedOperations.constructBoxBytes()

  final override fun extractMethods(): Array<java.lang.reflect.Method> =
    unboxedOperations.extractMethods()

  final override fun readLatin1Carrier(reader: Latin1JsonReader): Any? =
    constructCarrier(reader, valueTypeInfo().latin1Reader().readLatin1(reader))

  final override fun readUtf16Carrier(reader: Utf16JsonReader): Any? =
    constructCarrier(reader, valueTypeInfo().utf16Reader().readUtf16(reader))

  final override fun readUtf8Carrier(reader: Utf8JsonReader): Any? =
    constructCarrier(reader, valueTypeInfo().utf8Reader().readUtf8(reader))

  final override fun writeStringCarrier(writer: StringJsonWriter, carrier: Any?) =
    valueTypeInfo().stringWriter().writeString(writer, extractValue(carrier))

  final override fun writeUtf8Carrier(writer: Utf8JsonWriter, carrier: Any?) =
    valueTypeInfo().utf8Writer().writeUtf8(writer, extractValue(carrier))

  protected fun writeStringValue(writer: StringJsonWriter, value: Any) =
    capability.writeString(writer, value)

  protected fun writeUtf8Value(writer: Utf8JsonWriter, value: Any) =
    capability.writeUtf8(writer, value)

  protected fun readLatin1Value(reader: Latin1JsonReader): Any = capability.readLatin1(reader)

  protected fun readUtf16Value(reader: Utf16JsonReader): Any = capability.readUtf16(reader)

  protected fun readUtf8Value(reader: Utf8JsonReader): Any = capability.readUtf8(reader)

  protected fun nonNull(value: Any?): Any =
    value ?: throw ForyJsonException("Kotlin value class ${shape.ownerClass.name} is not nullable")
}

private class NullableOuterCodec(
  shape: KotlinValueClassShape,
  operations: KotlinValueClassOperations,
  unboxed: KotlinUnboxedValueClassOperations,
) : KotlinValueClassCodec(shape, operations, unboxed) {
  override fun writeString(writer: StringJsonWriter, value: Any?) {
    if (value == null) writer.writeNull() else writeStringValue(writer, value)
  }

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) {
    if (value == null) writer.writeNull() else writeUtf8Value(writer, value)
  }

  override fun readLatin1(reader: Latin1JsonReader): Any? =
    if (reader.tryReadNullToken()) null else readLatin1Value(reader)

  override fun readUtf16(reader: Utf16JsonReader): Any? =
    if (reader.tryReadNullToken()) null else readUtf16Value(reader)

  override fun readUtf8(reader: Utf8JsonReader): Any? =
    if (reader.tryReadNullToken()) null else readUtf8Value(reader)
}

private class NullableUnderlyingCodec(
  shape: KotlinValueClassShape,
  operations: KotlinValueClassOperations,
  unboxed: KotlinUnboxedValueClassOperations,
) : KotlinValueClassCodec(shape, operations, unboxed), TransparentNullCodec {
  override fun writeString(writer: StringJsonWriter, value: Any?) =
    writeStringValue(writer, nonNull(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) =
    writeUtf8Value(writer, nonNull(value))

  override fun readLatin1(reader: Latin1JsonReader): Any = readLatin1Value(reader)

  override fun readUtf16(reader: Utf16JsonReader): Any = readUtf16Value(reader)

  override fun readUtf8(reader: Utf8JsonReader): Any = readUtf8Value(reader)
}

private class NonNullCodec(
  shape: KotlinValueClassShape,
  operations: KotlinValueClassOperations,
  unboxed: KotlinUnboxedValueClassOperations,
) : KotlinValueClassCodec(shape, operations, unboxed) {
  override fun writeString(writer: StringJsonWriter, value: Any?) =
    writeStringValue(writer, nonNull(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any?) =
    writeUtf8Value(writer, nonNull(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    if (reader.tryReadNullToken()) nullFailure() else readLatin1Value(reader)

  override fun readUtf16(reader: Utf16JsonReader): Any =
    if (reader.tryReadNullToken()) nullFailure() else readUtf16Value(reader)

  override fun readUtf8(reader: Utf8JsonReader): Any =
    if (reader.tryReadNullToken()) nullFailure() else readUtf8Value(reader)

  private fun nullFailure(): Nothing =
    throw ForyJsonException("Kotlin value class ${shape.ownerClass.name} is not nullable")
}

private object UnresolvedValueClassCapability : KotlinValueClassCapability {
  override fun writeString(writer: StringJsonWriter, value: Any): Unit = unresolved()

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any): Unit = unresolved()

  override fun readLatin1(reader: Latin1JsonReader): Any = unresolved()

  override fun readUtf16(reader: Utf16JsonReader): Any = unresolved()

  override fun readUtf8(reader: Utf8JsonReader): Any = unresolved()

  private fun unresolved(): Nothing =
    throw IllegalStateException("Kotlin value-class child capability is not resolved")
}

internal class GenericValueClassCapability(
  private val operations: BoxedValueClassOperations,
  child: JsonTypeInfo,
) : KotlinValueClassCapability {
  private val stringWriter = child.stringWriter()
  private val utf8Writer = child.utf8Writer()
  private val latin1Reader = child.latin1Reader()
  private val utf16Reader = child.utf16Reader()
  private val utf8Reader = child.utf8Reader()

  override fun writeString(writer: StringJsonWriter, value: Any) =
    stringWriter.writeString(writer, operations.unbox(value))

  override fun writeUtf8(writer: Utf8JsonWriter, value: Any) =
    utf8Writer.writeUtf8(writer, operations.unbox(value))

  override fun readLatin1(reader: Latin1JsonReader): Any =
    operations.construct(reader, latin1Reader.readLatin1(reader))

  override fun readUtf16(reader: Utf16JsonReader): Any =
    operations.construct(reader, utf16Reader.readUtf16(reader))

  override fun readUtf8(reader: Utf8JsonReader): Any =
    operations.construct(reader, utf8Reader.readUtf8(reader))
}

internal interface BoxedValueClassOperations {
  fun construct(reader: JsonReader, value: Any?): Any

  fun unbox(value: Any): Any?
}

internal interface UnchargedBoxedOperations : BoxedValueClassOperations {
  fun constructUncharged(value: Any?): Any
}

internal fun boxedOperations(operations: KotlinValueClassOperations): BoxedValueClassOperations =
  when (operations) {
    is BoxedValueClassOperations -> operations
    is KotlinBooleanValueClassOperations<*> -> BooleanBoxedOperations(operations.cast())
    is KotlinByteValueClassOperations<*> -> ByteBoxedOperations(operations.cast())
    is KotlinShortValueClassOperations<*> -> ShortBoxedOperations(operations.cast())
    is KotlinIntValueClassOperations<*> -> IntBoxedOperations(operations.cast())
    is KotlinLongValueClassOperations<*> -> LongBoxedOperations(operations.cast())
    is KotlinFloatValueClassOperations<*> -> FloatBoxedOperations(operations.cast())
    is KotlinDoubleValueClassOperations<*> -> DoubleBoxedOperations(operations.cast())
    is KotlinCharValueClassOperations<*> -> CharBoxedOperations(operations.cast())
    else ->
      throw ForyJsonException("Unknown Kotlin value-class operations ${operations.javaClass.name}")
  }

@Suppress("UNCHECKED_CAST") private fun <T> Any.cast(): T = this as T

private class BooleanBoxedOperations(
  private val delegate: KotlinBooleanValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructBoolean(reader, value as Boolean)

  override fun unbox(value: Any): Any = delegate.unboxBoolean(value)
}

private class ByteBoxedOperations(
  private val delegate: KotlinByteValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructByte(reader, (value as Number).toByte())

  override fun unbox(value: Any): Any = delegate.unboxByte(value)
}

private class ShortBoxedOperations(
  private val delegate: KotlinShortValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructShort(reader, (value as Number).toShort())

  override fun unbox(value: Any): Any = delegate.unboxShort(value)
}

private class IntBoxedOperations(
  private val delegate: KotlinIntValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructInt(reader, (value as Number).toInt())

  override fun unbox(value: Any): Any = delegate.unboxInt(value)
}

private class LongBoxedOperations(
  private val delegate: KotlinLongValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructLong(reader, (value as Number).toLong())

  override fun unbox(value: Any): Any = delegate.unboxLong(value)
}

private class FloatBoxedOperations(
  private val delegate: KotlinFloatValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructFloat(reader, (value as Number).toFloat())

  override fun unbox(value: Any): Any = delegate.unboxFloat(value)
}

private class DoubleBoxedOperations(
  private val delegate: KotlinDoubleValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructDouble(reader, (value as Number).toDouble())

  override fun unbox(value: Any): Any = delegate.unboxDouble(value)
}

private class CharBoxedOperations(
  private val delegate: KotlinCharValueClassOperations<Any>,
) : BoxedValueClassOperations {
  override fun construct(reader: JsonReader, value: Any?): Any =
    delegate.constructChar(reader, value as Char)

  override fun unbox(value: Any): Any = delegate.unboxChar(value)
}
