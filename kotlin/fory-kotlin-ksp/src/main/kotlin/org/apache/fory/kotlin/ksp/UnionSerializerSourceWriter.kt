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

package org.apache.fory.kotlin.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

internal class UnionSerializerSourceWriter(private val union: KotlinSourceUnion) {
  private val builder = StringBuilder(16384)

  fun writeTo(codeGenerator: CodeGenerator) {
    val dependencies =
      Dependencies(aggregating = false, sources = union.originatingFiles.toTypedArray())
    val output =
      codeGenerator.createNewFile(dependencies, union.packageName, union.serializerName, "kt")
    OutputStreamWriter(output, StandardCharsets.UTF_8).use { it.write(write()) }
  }

  fun write(): String {
    writeHeader()
    writeClassStart()
    writeDescriptors()
    writeConstructors()
    writeWrite()
    writeRead()
    writeCopy()
    builder.append("}\n")
    return builder.toString()
  }

  private fun writeHeader() {
    builder.append("@file:OptIn(ExperimentalUnsignedTypes::class)\n\n")
    if (union.packageName.isNotEmpty()) {
      builder.append("package ").append(union.packageName).append("\n\n")
    }
    builder.append("import java.math.BigDecimal\n")
    builder.append("import java.time.Instant\n")
    builder.append("import java.time.LocalDate\n")
    builder.append("import java.util.Collections\n")
    builder.append("import org.apache.fory.Fory\n")
    builder.append("import org.apache.fory.context.CopyContext\n")
    builder.append("import org.apache.fory.context.ReadContext\n")
    builder.append("import org.apache.fory.context.WriteContext\n")
    builder.append("import org.apache.fory.kotlin.KotlinXlangArrayEncoding\n")
    builder.append("import org.apache.fory.kotlin.DurationEncoding\n")
    builder.append("import org.apache.fory.meta.TypeDef\n")
    builder.append("import org.apache.fory.meta.TypeExtMeta\n")
    builder.append("import org.apache.fory.reflect.TypeRef\n")
    builder.append("import org.apache.fory.resolver.TypeResolver\n")
    builder.append("import org.apache.fory.serializer.FieldGroups\n")
    builder.append("import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo\n")
    builder.append("import org.apache.fory.serializer.StaticGeneratedStructSerializer\n")
    builder.append("import org.apache.fory.serializer.UnionSerializer\n")
    builder.append("import org.apache.fory.serializer.collection.CollectionFlags\n")
    builder.append("import org.apache.fory.serializer.kotlin.DurationSerializers\n")
    builder.append("import org.apache.fory.serializer.kotlin.KotlinXlangUnsignedSerializers\n")
    builder.append("import org.apache.fory.type.Descriptor\n")
    builder.append("import org.apache.fory.type.BFloat16\n")
    builder.append("import org.apache.fory.type.BFloat16Array\n")
    builder.append("import org.apache.fory.type.Float16\n")
    builder.append("import org.apache.fory.type.Float16Array\n")
    builder.append("import org.apache.fory.type.Types\n\n")
    builder.append("import kotlin.time.Duration\n\n")
  }

  private fun writeClassStart() {
    builder.append("@Suppress(\"UNCHECKED_CAST\")\n")
    builder
      .append(union.serializerVisibility.keyword)
      .append(" class ")
      .append(union.serializerName)
      .append(" : StaticGeneratedStructSerializer<")
      .append(union.typeName)
      .append("> {\n")
    builder.append("  private val caseFields: Array<SerializationFieldInfo?>\n\n")
  }

  private fun writeDescriptors() {
    builder.append("  public companion object {\n")
    builder.append("    @JvmField\n")
    builder.append("    public val DESCRIPTORS: List<Descriptor> = buildDescriptors()\n\n")
    builder.append("    private fun buildDescriptors(): List<Descriptor> {\n")
    builder
      .append("      val descriptors = ArrayList<Descriptor>(")
      .append(union.cases.size)
      .append(")\n")
    for (case in union.cases) {
      builder.append("      descriptors.add(Descriptor(")
      builder.append(case.valueType.typeRefExpression()).append(", ")
      builder.append("\"").append(escape(case.valueType.typeName)).append("\", ")
      builder.append("\"").append(escape(case.className)).append("\", ")
      builder.append("0, ")
      builder.append("\"").append(escape(union.qualifiedTypeName)).append("\", ")
      builder.append("true, ")
      builder.append(case.knownId).append(", ")
      builder.append(case.valueType.nullable).append(", ")
      builder.append(case.valueType.trackingRef).append(", ")
      builder.append("org.apache.fory.annotation.ForyField.Dynamic.AUTO, ")
      builder.append("false))\n")
    }
    builder.append("      return Collections.unmodifiableList(descriptors)\n")
    builder.append("    }\n")
    builder.append("  }\n\n")
    builder.append("  override fun getGeneratedDescriptors(): List<Descriptor> = DESCRIPTORS\n\n")
  }

  private fun writeConstructors() {
    builder.append("  public constructor() : super() {\n")
    builder.append("    this.caseFields = emptyArray()\n")
    builder.append("  }\n\n")
    builder
      .append("  public constructor(typeResolver: TypeResolver, type: Class<*>)")
      .append(" : super(typeResolver, type, null, DESCRIPTORS) {\n")
    builder.append("    val fieldGroups: FieldGroups = buildFieldGroups(DESCRIPTORS)\n")
    builder.append("    val allFields = fieldGroups.allFields\n")
    builder.append("    val allFieldIds = localFieldIds(allFields, DESCRIPTORS)\n")
    builder.append("    this.caseFields = arrayOfNulls(DESCRIPTORS.size)\n")
    builder.append("    for (i in allFields.indices) {\n")
    builder.append("      this.caseFields[allFieldIds[i]] = allFields[i]\n")
    builder.append("    }\n")
    writeScalarBindings()
    builder.append("  }\n\n")
    builder
      .append("  public constructor(typeResolver: TypeResolver, type: Class<*>, typeDef: TypeDef?)")
      .append(" : this(typeResolver, type) {}\n\n")
  }

  private fun writeWrite() {
    builder
      .append("  override fun write(writeContext: WriteContext, value: ")
      .append(union.typeName)
      .append(") {\n")
    builder.append("    when (value) {\n")
    builder
      .append("      is ")
      .append(union.unknownCase.qualifiedClassName)
      .append(" -> UnionSerializer.writeUnknownValue(writeContext, value.value)\n")
    for ((index, case) in union.cases.withIndex()) {
      builder.append("      is ").append(case.qualifiedClassName).append(" -> ")
      val direct = directWriteCaseStatement(case, "value.value")
      if (direct == null) {
        builder
          .append("UnionSerializer.writeCaseValue(typeResolver, writeContext, caseFields[")
          .append(index)
          .append("]!!, value.value, ")
          .append(case.knownId)
          .append(")\n")
      } else {
        builder.append("{ ").append(direct).append(" }\n")
      }
    }
    builder.append("    }\n")
    builder.append("  }\n\n")
  }

  private fun writeRead() {
    builder
      .append("  override fun read(readContext: ReadContext): ")
      .append(union.typeName)
      .append(" {\n")
    builder.append("    val caseId = readContext.buffer.readVarUInt32()\n")
    builder.append("    return when (caseId) {\n")
    for ((index, case) in union.cases.withIndex()) {
      builder.append("      ").append(case.knownId).append(" -> ")
      val direct = directReadCaseExpression(case)
      if (direct == null) {
        builder
          .append(case.qualifiedClassName)
          .append("(UnionSerializer.readCaseValue(typeResolver, readContext, caseFields[")
          .append(index)
          .append("]!!) as ")
          .append(case.valueType.valueTypeName)
          .append(")\n")
      } else {
        builder.append(direct).append("\n")
      }
    }
    builder
      .append("      else -> ")
      .append(union.unknownCase.qualifiedClassName)
      .append("(UnionSerializer.readUnknownValue(readContext, caseId))\n")
    builder.append("    }\n")
    builder.append("  }\n\n")
    builder
      .append("  override fun readCompatible(readContext: ReadContext): ")
      .append(union.typeName)
      .append(" = read(readContext)\n\n")
  }

  private fun writeCopy() {
    builder
      .append("  override fun copy(copyContext: CopyContext, value: ")
      .append(union.typeName)
      .append("): ")
      .append(union.typeName)
      .append(" {\n")
    builder.append("    return when (value) {\n")
    builder
      .append("      is ")
      .append(union.unknownCase.qualifiedClassName)
      .append(" -> ")
      .append(union.unknownCase.qualifiedClassName)
      .append("(UnionSerializer.copyUnknownValue(copyContext, value.value))\n")
    for ((index, case) in union.cases.withIndex()) {
      builder
        .append("      is ")
        .append(case.qualifiedClassName)
        .append(" -> ")
        .append(case.qualifiedClassName)
      val directCopy = directCopyCaseValue(case.valueType, "value.value")
      if (directCopy == null) {
        builder
          .append("(UnionSerializer.copyCaseValue(copyContext, caseFields[")
          .append(index)
          .append("]!!, value.value) as ")
          .append(case.valueType.valueTypeName)
          .append(")\n")
      } else {
        builder.append("(").append(directCopy).append(")\n")
      }
    }
    builder.append("    }\n")
    builder.append("  }\n")
  }

  private fun directWriteCaseStatement(case: KotlinSourceUnionCase, value: String): String? {
    val typeId = case.valueType.typeId ?: return null
    val caseId = case.knownId
    val nullableValue = "caseValue$caseId"
    return if (case.valueType.nullable) {
      val writeValue = directPayloadWrite(case.valueType, nullableValue) ?: return null
      "val $nullableValue = $value; val buffer = writeContext.buffer; buffer.writeVarUInt32($caseId); if ($nullableValue == null) { buffer.writeByte(Fory.NULL_FLAG) } else { buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG); buffer.writeUInt8($typeId); $writeValue }"
    } else {
      val writeValue = directPayloadWrite(case.valueType, value) ?: return null
      "val buffer = writeContext.buffer; buffer.writeVarUInt32($caseId); buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG); buffer.writeUInt8($typeId); $writeValue"
    }
  }

  private fun directReadCaseExpression(case: KotlinSourceUnionCase): String? {
    val readValue = directPayloadRead(case.valueType) ?: return null
    val typeId = case.valueType.typeId ?: return null
    val valueExpression =
      if (case.valueType.nullable) {
        "if (buffer.readByte() == Fory.NULL_FLAG) null else { check(buffer.readUInt8() == $typeId); $readValue }"
      } else {
        "if (buffer.readByte() == Fory.NULL_FLAG) throw org.apache.fory.exception.DeserializationException(\"Non-null Kotlin union case ${case.qualifiedClassName} decoded null\") else { check(buffer.readUInt8() == $typeId); $readValue }"
      }
    return "run { val buffer = readContext.buffer; ${case.qualifiedClassName}($valueExpression) }"
  }

  private fun directPayloadWrite(type: KotlinSourceTypeNode, value: String): String? {
    val listWrite = directListBodyWrite(type, value)
    if (listWrite != null) {
      return listWrite
    }
    val denseUnsignedArrayWrite = denseUnsignedArrayWrite(type)
    if (denseUnsignedArrayWrite != null && !type.trackingRef) {
      return "KotlinXlangArrayEncoding.$denseUnsignedArrayWrite(writeContext, $value)"
    }
    if (!canDirect(type)) {
      return null
    }
    return when (type.typeId) {
      "Types.BOOL" -> "buffer.writeBoolean($value)"
      "Types.INT8" -> "buffer.writeByte($value.toInt())"
      "Types.INT16" -> "buffer.writeInt16($value)"
      "Types.INT32" -> "buffer.writeInt32($value)"
      "Types.VARINT32" -> "buffer.writeVarInt32($value)"
      "Types.INT64" -> "buffer.writeInt64($value)"
      "Types.VARINT64" -> "buffer.writeVarInt64($value)"
      "Types.TAGGED_INT64" -> "buffer.writeTaggedInt64($value)"
      "Types.UINT8" -> "buffer.writeByte($value.toInt())"
      "Types.UINT16" -> "buffer.writeInt16($value.toShort())"
      "Types.UINT32" -> "buffer.writeInt32($value.toInt())"
      "Types.VAR_UINT32" -> "buffer.writeVarUInt32($value.toInt())"
      "Types.UINT64" -> "buffer.writeInt64($value.toLong())"
      "Types.VAR_UINT64" -> "buffer.writeVarUInt64($value.toLong())"
      "Types.TAGGED_UINT64" -> "buffer.writeTaggedUInt64($value.toLong())"
      "Types.FLOAT32" -> "buffer.writeFloat32($value)"
      "Types.FLOAT64" -> "buffer.writeFloat64($value)"
      "Types.STRING" -> "writeContext.writeString($value)"
      "Types.DURATION" -> "DurationEncoding.write(writeContext, $value)"
      else -> null
    }
  }

  private fun directPayloadRead(type: KotlinSourceTypeNode): String? {
    val listRead = directListBodyRead(type)
    if (listRead != null) {
      return listRead
    }
    val denseUnsignedArrayRead = denseUnsignedArrayRead(type)
    if (denseUnsignedArrayRead != null && !type.trackingRef) {
      return "KotlinXlangArrayEncoding.$denseUnsignedArrayRead(readContext)"
    }
    if (!canDirect(type)) {
      return null
    }
    return when (type.typeId) {
      "Types.BOOL" -> "buffer.readBoolean()"
      "Types.INT8" -> "buffer.readByte()"
      "Types.INT16" -> "buffer.readInt16()"
      "Types.INT32" -> "buffer.readInt32()"
      "Types.VARINT32" -> "buffer.readVarInt32()"
      "Types.INT64" -> "buffer.readInt64()"
      "Types.VARINT64" -> "buffer.readVarInt64()"
      "Types.TAGGED_INT64" -> "buffer.readTaggedInt64()"
      "Types.UINT8" -> "buffer.readByte().toUByte()"
      "Types.UINT16" -> "buffer.readInt16().toUShort()"
      "Types.UINT32" -> "buffer.readInt32().toUInt()"
      "Types.VAR_UINT32" -> "buffer.readVarUInt32().toUInt()"
      "Types.UINT64" -> "buffer.readInt64().toULong()"
      "Types.VAR_UINT64" -> "buffer.readVarUInt64().toULong()"
      "Types.TAGGED_UINT64" -> "buffer.readTaggedUInt64().toULong()"
      "Types.FLOAT32" -> "buffer.readFloat32()"
      "Types.FLOAT64" -> "buffer.readFloat64()"
      "Types.STRING" -> "readContext.readString()"
      "Types.DURATION" -> "DurationEncoding.read(readContext)"
      else -> null
    }
  }

  private fun canDirect(type: KotlinSourceTypeNode): Boolean =
    !type.trackingRef && type.typeArguments.isEmpty() && type.componentType == null

  private fun directListBodyWrite(type: KotlinSourceTypeNode, value: String): String? {
    if (type.typeId != "Types.LIST" || type.typeArguments.size != 1 || type.nullable) {
      return null
    }
    val elementType = type.typeArguments[0]
    if (elementType.nullable || !canDirect(elementType)) {
      return null
    }
    val writeElement = directPayloadWrite(elementType, "element") ?: return null
    return "$value.let { listValue -> buffer.writeVarUInt32Small7(listValue.size); if (listValue.isNotEmpty()) { buffer.writeByte(CollectionFlags.DECL_SAME_TYPE_NOT_HAS_NULL); for (element in listValue) { $writeElement } } }"
  }

  private fun directListBodyRead(type: KotlinSourceTypeNode): String? {
    if (type.typeId != "Types.LIST" || type.typeArguments.size != 1 || type.nullable) {
      return null
    }
    val elementType = type.typeArguments[0]
    if (elementType.nullable || !canDirect(elementType)) {
      return null
    }
    val readElement = directPayloadRead(elementType) ?: return null
    val valueType = type.valueTypeName.removeSuffix("?")
    return "run { val size = buffer.readVarUInt32Small7(); val result = if (size == 0) java.util.ArrayList<Any?>(0) else { check(buffer.readByte().toInt() == CollectionFlags.DECL_SAME_TYPE_NOT_HAS_NULL); buffer.checkReadableBytes(size); val values = java.util.ArrayList<Any?>(size); for (i in 0 until size) { values.add($readElement) }; values }; result as $valueType }"
  }

  private fun denseUnsignedArrayWrite(type: KotlinSourceTypeNode): String? =
    when (type.valueTypeName.removeSuffix("?")) {
      "UByteArray" -> "writeUByteArray"
      "UShortArray" -> "writeUShortArray"
      "UIntArray" -> "writeUIntArray"
      "ULongArray" -> "writeULongArray"
      else -> null
    }

  private fun denseUnsignedArrayRead(type: KotlinSourceTypeNode): String? =
    when (type.valueTypeName.removeSuffix("?")) {
      "UByteArray" -> "readUByteArray"
      "UShortArray" -> "readUShortArray"
      "UIntArray" -> "readUIntArray"
      "ULongArray" -> "readULongArray"
      else -> null
    }

  private fun writeScalarBindings() {
    for ((index, case) in union.cases.withIndex()) {
      if (case.valueType.typeArguments.isEmpty() || !needsScalarSerializer(case.valueType)) {
        continue
      }
      writeScalarBinding(case.valueType, "this.caseFields[$index]!!.genericType")
    }
  }

  private fun writeScalarBinding(type: KotlinSourceTypeNode, genericExpression: String) {
    if (type.unsigned && type.componentType == null) {
      builder
        .append("    ")
        .append(genericExpression)
        .append(".setSerializer(KotlinXlangUnsignedSerializers.serializer(typeResolver.config, ")
        .append(type.typeId ?: "Types.UNKNOWN")
        .append(", ")
        .append("\"")
        .append(type.valueTypeName.removeSuffix("?"))
        .append("\"")
        .append("))\n")
      return
    }
    if (type.typeId == "Types.DURATION") {
      builder
        .append("    ")
        .append(genericExpression)
        .append(".setSerializer(DurationSerializers.serializer(typeResolver.config))\n")
      return
    }
    for (i in type.typeArguments.indices) {
      writeScalarBinding(type.typeArguments[i], "$genericExpression.getTypeParameter$i()")
    }
  }

  private fun needsScalarSerializer(type: KotlinSourceTypeNode): Boolean =
    (type.unsigned && type.componentType == null) ||
      type.typeId == "Types.DURATION" ||
      type.typeArguments.any { needsScalarSerializer(it) }

  private fun directCopyCaseValue(type: KotlinSourceTypeNode, value: String): String? {
    if (type.unsigned && type.componentType != null) {
      return "$value.copyOf()"
    }
    if (type.typeId == "Types.LIST" && type.typeArguments.size == 1) {
      val element = type.typeArguments[0]
      if (!element.nullable && canDirect(element)) {
        return "java.util.ArrayList($value) as ${type.valueTypeName.removeSuffix("?")}"
      }
    }
    if (!canDirect(type)) {
      return null
    }
    return when (type.typeId) {
      "Types.BOOL",
      "Types.INT8",
      "Types.INT16",
      "Types.INT32",
      "Types.VARINT32",
      "Types.INT64",
      "Types.VARINT64",
      "Types.TAGGED_INT64",
      "Types.UINT8",
      "Types.UINT16",
      "Types.UINT32",
      "Types.VAR_UINT32",
      "Types.UINT64",
      "Types.VAR_UINT64",
      "Types.TAGGED_UINT64",
      "Types.FLOAT32",
      "Types.FLOAT64",
      "Types.STRING",
      "Types.DURATION" -> value
      else -> null
    }
  }

  private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
