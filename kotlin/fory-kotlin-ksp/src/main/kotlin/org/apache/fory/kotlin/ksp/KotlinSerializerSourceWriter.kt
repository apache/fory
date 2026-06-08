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

internal class KotlinSerializerSourceWriter(private val struct: KotlinSourceStruct) {
  private val builder = StringBuilder(32768)
  private val hasComparatorGuards = struct.fields.any { needsComparatorGuard(it.type) }

  private data class ContainerTarget(val init: String, val result: String)

  fun writeTo(codeGenerator: CodeGenerator) {
    val dependencies =
      Dependencies(aggregating = false, sources = struct.originatingFiles.toTypedArray())
    val output =
      codeGenerator.createNewFile(dependencies, struct.packageName, struct.serializerName, "kt")
    OutputStreamWriter(output, StandardCharsets.UTF_8).use { it.write(write()) }
  }

  fun write(): String {
    writeHeader()
    writeClassStart()
    writeDescriptors()
    writeConstructors()
    writeWrite()
    writeRead()
    writeCompatibleRead()
    writeCopy()
    builder.append("}\n")
    return builder.toString()
  }

  private fun writeHeader() {
    builder.append("@file:OptIn(ExperimentalUnsignedTypes::class)\n\n")
    if (struct.packageName.isNotEmpty()) {
      builder.append("package ").append(struct.packageName).append("\n\n")
    }
    builder.append("import java.util.Collections\n")
    builder.append("import org.apache.fory.Fory\n")
    builder.append("import org.apache.fory.annotation.ForyField\n")
    builder.append("import org.apache.fory.context.CopyContext\n")
    builder.append("import org.apache.fory.context.ReadContext\n")
    builder.append("import org.apache.fory.context.WriteContext\n")
    builder.append("import org.apache.fory.exception.DeserializationException\n")
    builder.append("import org.apache.fory.kotlin.KotlinCollectionAdapters\n")
    builder.append("import org.apache.fory.kotlin.DurationEncoding\n")
    builder.append("import org.apache.fory.kotlin.KotlinXlangArrayEncoding\n")
    builder.append("import org.apache.fory.serializer.kotlin.KotlinXlangUnsignedSerializers\n")
    builder.append("import org.apache.fory.serializer.kotlin.DurationSerializers\n")
    builder.append("import org.apache.fory.meta.TypeDef\n")
    builder.append("import org.apache.fory.meta.TypeExtMeta\n")
    builder.append("import org.apache.fory.reflect.TypeRef\n")
    builder.append("import org.apache.fory.resolver.TypeResolver\n")
    builder.append("import org.apache.fory.serializer.FieldGroups\n")
    builder.append("import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo\n")
    builder.append("import org.apache.fory.serializer.StaticGeneratedStructSerializer\n")
    builder.append("import org.apache.fory.serializer.converter.FieldConverters\n")
    builder.append("import org.apache.fory.type.Descriptor\n")
    builder.append("import org.apache.fory.type.BFloat16Array\n")
    builder.append("import org.apache.fory.type.Float16Array\n")
    builder.append("import org.apache.fory.type.Types\n\n")
  }

  private fun writeClassStart() {
    builder.append(
      "@Suppress(\"UNCHECKED_CAST\", \"PLATFORM_CLASS_MAPPED_TO_KOTLIN\", \"UNNECESSARY_NOT_NULL_ASSERTION\")\n"
    )
    builder
      .append(struct.serializerVisibility.keyword)
      .append(" class ")
      .append(struct.serializerName)
      .append(" : StaticGeneratedStructSerializer<")
      .append(struct.typeName)
      .append("> {\n")
    builder.append("  private val allFields: Array<SerializationFieldInfo>\n")
    builder.append("  private val allFieldIds: IntArray\n")
    builder.append("  private val fieldsById: Array<SerializationFieldInfo?>\n")
    builder.append("  private val constructorFieldIds: IntArray?\n")
    builder.append("  private val constructorFieldBits: LongArray?\n")
    builder.append("  private val classVersionHash: Int\n")
    builder.append("  private val sameSchemaCompatible: Boolean\n\n")
  }

  private fun writeDescriptors() {
    builder.append("  public companion object {\n")
    builder
      .append("    private const val HAS_COMPAT_NESTED_FIELDS: Boolean = ")
      .append(struct.hasCompatStructFields)
      .append("\n\n")
    builder.append("    @JvmField\n")
    builder.append("    public val DESCRIPTORS: List<Descriptor> = buildDescriptors()\n\n")
    builder.append("    private fun buildDescriptors(): List<Descriptor> {\n")
    builder
      .append("      val descriptors = ArrayList<Descriptor>(")
      .append(struct.fields.size)
      .append(")\n")
    for (field in struct.fields) {
      builder.append("      descriptors.add(Descriptor(")
      builder.append(field.type.typeRefExpression()).append(", ")
      builder.append("\"").append(escape(field.type.typeName)).append("\", ")
      builder.append("\"").append(escape(field.name)).append("\", ")
      builder.append("0, ")
      builder.append("\"").append(escape(struct.qualifiedTypeName)).append("\", ")
      builder.append(field.hasForyField).append(", ")
      builder.append(field.foryFieldId).append(", ")
      builder.append(field.nullable).append(", ")
      builder.append(field.trackingRef).append(", ")
      builder.append("ForyField.Dynamic.").append(field.dynamic).append(", ")
      builder.append(field.arrayType).append("))\n")
    }
    builder.append("      return Collections.unmodifiableList(descriptors)\n")
    builder.append("    }\n")
    if (hasComparatorGuards) {
      builder.append("    private val NATURAL_ORDER_COMPARATOR: java.util.Comparator<*> =\n")
      builder.append(
        "      java.util.Comparator.naturalOrder<Comparable<Any>>() as java.util.Comparator<*>\n"
      )
    }
    builder.append("  }\n\n")
    builder.append("  override fun getGeneratedDescriptors(): List<Descriptor> = DESCRIPTORS\n\n")
  }

  private fun writeConstructors() {
    builder.append("  public constructor() : super() {\n")
    builder.append("    this.allFields = emptyArray()\n")
    builder.append("    this.allFieldIds = IntArray(0)\n")
    builder.append("    this.fieldsById = arrayOfNulls(0)\n")
    builder.append("    this.constructorFieldIds = null\n")
    builder.append("    this.constructorFieldBits = null\n")
    builder.append("    this.classVersionHash = 0\n")
    builder.append("    this.sameSchemaCompatible = false\n")
    builder.append("  }\n\n")

    builder.append(
      "  public constructor(typeResolver: TypeResolver, type: Class<*>) : super(typeResolver, type) {\n"
    )
    writeConstructorBody("buildFieldGroups(DESCRIPTORS)", "false")
    builder.append("  }\n\n")

    builder.append(
      "  public constructor(typeResolver: TypeResolver, type: Class<*>, typeDef: TypeDef?) : super(typeResolver, type, typeDef, DESCRIPTORS) {\n"
    )
    writeConstructorBody(
      "buildLocalFieldGroups(DESCRIPTORS)",
      "typeDef != null && !HAS_COMPAT_NESTED_FIELDS && typeDef.id == TypeDef.buildTypeDef(typeResolver, type).id",
    )
    builder.append("  }\n\n")
  }

  private fun writeConstructorBody(fieldGroupsExpression: String, sameSchemaExpression: String) {
    builder.append("    val fieldGroups: FieldGroups = ").append(fieldGroupsExpression).append("\n")
    builder.append("    this.allFields = fieldGroups.allFields\n")
    builder.append("    this.allFieldIds = localFieldIds(this.allFields, DESCRIPTORS)\n")
    builder.append("    this.fieldsById = arrayOfNulls(DESCRIPTORS.size)\n")
    builder.append("    for (i in this.allFields.indices) {\n")
    builder.append("      this.fieldsById[this.allFieldIds[i]] = this.allFields[i]\n")
    builder.append("    }\n")
    if (struct.construction == KotlinStructConstruction.CONSTRUCTOR) {
      builder.append("    this.constructorFieldIds = intArrayOf(")
      for (i in struct.fields.indices) {
        if (i > 0) {
          builder.append(", ")
        }
        builder.append(struct.fields[i].id)
      }
      builder.append(")\n")
    } else {
      builder.append("    this.constructorFieldIds = null\n")
    }
    builder.append(
      "    this.constructorFieldBits = buildConstructorFieldBits(DESCRIPTORS.size, constructorFieldIds)\n"
    )
    writeScalarBindings()
    builder.append(
      "    this.classVersionHash = if (typeResolver.checkClassVersion()) computeClassVersionHash(DESCRIPTORS) else 0\n"
    )
    builder.append("    this.sameSchemaCompatible = ").append(sameSchemaExpression).append("\n")
  }

  private fun writeScalarBindings() {
    for (field in struct.fields) {
      if (field.type.typeArguments.isEmpty() || !needsScalarSerializer(field.type)) {
        continue
      }
      writeScalarBinding(field.type, "this.fieldsById[${field.id}]!!.genericType")
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

  private fun writeWrite() {
    builder
      .append("  override fun write(writeContext: WriteContext, value: ")
      .append(struct.typeName)
      .append(") {\n")
    writeComparatorPreflight()
    builder.append("    val buffer = writeContext.buffer\n")
    builder.append("    if (typeResolver.checkClassVersion()) {\n")
    builder.append("      buffer.writeInt32(classVersionHash)\n")
    builder.append("    }\n")
    builder.append("    for (i in allFields.indices) {\n")
    builder.append("      val fieldInfo = allFields[i]\n")
    builder.append("      when (allFieldIds[i]) {\n")
    for (field in struct.fields) {
      builder.append("        ").append(field.id).append(" -> ")
      val direct = directWriteStatement(field, "value.${field.name}")
      if (direct == null) {
        builder
          .append("writeFieldValue(writeContext, fieldInfo, ")
          .append("value.${field.name}")
          .append(")\n")
      } else {
        builder.append("{ ").append(direct).append(" }\n")
      }
    }
    builder.append(
      "        else -> throw IllegalStateException(\"Unknown generated field id \${allFieldIds[i]}\")\n"
    )
    builder.append("      }\n")
    builder.append("    }\n")
    builder.append("  }\n\n")
    if (hasComparatorGuards) {
      builder.append(
        "  private fun requireXlangNaturalOrdering(typeName: String, comparator: java.util.Comparator<*>?) {\n"
      )
      builder.append("    if (comparator != null && comparator !== NATURAL_ORDER_COMPARATOR) {\n")
      builder.append("      throw UnsupportedOperationException(\n")
      builder.append(
        "        \"Xlang serialization of \$typeName with a custom comparator is unsupported because the xlang container wire format does not encode comparators\"\n"
      )
      builder.append("      )\n")
      builder.append("    }\n")
      builder.append("  }\n\n")
    }
    if (struct.construction != KotlinStructConstruction.CONSTRUCTOR) {
      return
    }

    builder
      .append("  private fun newConstructorObject(fieldValues: Array<Any?>): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    return ")
    appendFieldValuesConstructorCall()
    builder.append("\n")
    builder.append("  }\n\n")

    builder.append(
      "  private fun setBufferedFields(value: ${struct.typeName}, fieldValues: Array<Any?>, bufferedFields: LongArray) {\n"
    )
    builder.append("    for (fieldId in fieldsById.indices) {\n")
    builder.append("      if (hasField(bufferedFields, fieldId)) {\n")
    builder.append(
      "        setFieldById(value, fieldsById[fieldId]!!, fieldId, resolveBufferedValue(fieldValues[fieldId], value))\n"
    )
    builder.append("      }\n")
    builder.append("    }\n")
    builder.append("  }\n\n")

    builder.append(
      "  private fun setFieldById(value: ${struct.typeName}, fieldInfo: SerializationFieldInfo, fieldId: Int, fieldValue: Any?) {\n"
    )
    builder.append("    when (fieldId) {\n")
    for (field in struct.fields) {
      builder
        .append("      ")
        .append(field.id)
        .append(" -> setGeneratedFieldValue(value, fieldInfo, fieldValue)\n")
    }
    builder.append(
      "      else -> throw IllegalStateException(\"Unknown generated field id \${fieldId}\")\n"
    )
    builder.append("    }\n")
    builder.append("  }\n\n")

    builder
      .append("  private fun copyConstructorObject(copyContext: CopyContext, value: ")
      .append(struct.typeName)
      .append("): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    val fieldValues = arrayOfNulls<Any?>(DESCRIPTORS.size)\n")
    builder.append("    val pendingMarker = beginConstructorCopy(copyContext, value)\n")
    for (field in struct.fields) {
      builder.append("    if (hasField(constructorFieldBits!!, ").append(field.id).append(")) {\n")
      builder
        .append("      fieldValues[")
        .append(field.id)
        .append("] = ")
        .append(constructorCopyExpression(field))
        .append("\n")
      builder.append("    }\n")
    }
    builder.append(
      "    checkNoConstructorCopyBackrefs(fieldValues, constructorFieldIds!!, pendingMarker)\n"
    )
    builder.append("    val copied = newConstructorObject(fieldValues)\n")
    builder.append("    copyContext.reference(value, copied)\n")
    for (field in struct.fields) {
      builder.append("    if (!hasField(constructorFieldBits!!, ").append(field.id).append(")) {\n")
      builder
        .append("      setGeneratedFieldValue(copied, fieldsById[")
        .append(field.id)
        .append("]!!, ")
        .append(copyExpression(field))
        .append(")\n")
      builder.append("    }\n")
    }
    builder.append("    return copied\n")
    builder.append("  }\n\n")
  }

  private fun writeRead() {
    builder
      .append("  override fun read(readContext: ReadContext): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    if (typeDef != null) {\n")
    builder.append(
      "      return if (sameSchemaCompatible) readSchemaConsistent(readContext) else readCompatible(readContext)\n"
    )
    builder.append("    }\n")
    builder.append("    return readSchemaConsistent(readContext)\n")
    builder.append("  }\n\n")

    builder
      .append("  private fun readSchemaConsistent(readContext: ReadContext): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    val buffer = readContext.buffer\n")
    builder.append("    if (typeResolver.checkClassVersion()) {\n")
    builder.append("      checkClassVersion(buffer.readInt32(), classVersionHash)\n")
    builder.append("    }\n")
    if (struct.construction == KotlinStructConstruction.MUTABLE) {
      writeMutableReadBody()
      builder.append("  }\n\n")
      return
    }
    builder.append("    return readSchemaConstructor(readContext)\n")
    builder.append("  }\n\n")
    writeConstructorRead()
  }

  private fun writeConstructorRead() {
    builder
      .append("  private fun readSchemaConstructor(readContext: ReadContext): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    val fieldValues = arrayOfNulls<Any?>(DESCRIPTORS.size)\n")
    builder.append("    val bufferedFields = newFieldBits(DESCRIPTORS.size)\n")
    builder.append("    beginConstructorRef(readContext)\n")
    builder.append("    try {\n")
    builder.append("      var remaining = countConstructorFields(constructorFieldBits!!)\n")
    builder.append("      var value: ").append(struct.typeName).append("? = null\n")
    builder.append("      if (remaining == 0) {\n")
    builder.append("        val constructed = newConstructorObject(fieldValues)\n")
    builder.append("        value = constructed\n")
    builder.append("        referenceConstructorRef(readContext, constructed)\n")
    builder.append("      }\n")
    builder.append("      for (i in allFields.indices) {\n")
    builder.append("        val fieldInfo = allFields[i]\n")
    builder.append("        val fieldId = allFieldIds[i]\n")
    builder.append(
      "        val fieldValue = readSchemaConstructorField(readContext, fieldInfo, fieldId)\n"
    )
    builder.append("        if (hasField(constructorFieldBits!!, fieldId)) {\n")
    builder.append(
      "          fieldValues[fieldId] = ctorFieldValue(readContext, fieldValue, type)\n"
    )
    builder.append("          remaining--\n")
    builder.append("          if (remaining == 0) {\n")
    builder.append("            checkNoUnresolvedReadRef(readContext)\n")
    builder.append("            val constructed = newConstructorObject(fieldValues)\n")
    builder.append("            value = constructed\n")
    builder.append("            referenceConstructorRef(readContext, constructed)\n")
    builder.append("            setBufferedFields(constructed, fieldValues, bufferedFields)\n")
    builder.append("          }\n")
    builder.append("        } else if (value == null) {\n")
    builder.append(
      "          fieldValues[fieldId] = bufferFieldValue(readContext, fieldValue, type)\n"
    )
    builder.append("          markField(bufferedFields, fieldId)\n")
    builder.append("        } else {\n")
    builder.append("          setFieldById(value!!, fieldInfo, fieldId, fieldValue)\n")
    builder.append("        }\n")
    builder.append("      }\n")
    builder.append("      if (value == null) {\n")
    builder.append("        checkNoUnresolvedReadRef(readContext)\n")
    builder.append("        val constructed = newConstructorObject(fieldValues)\n")
    builder.append("        value = constructed\n")
    builder.append("        referenceConstructorRef(readContext, constructed)\n")
    builder.append("        setBufferedFields(constructed, fieldValues, bufferedFields)\n")
    builder.append("      }\n")
    builder.append("      return value!!\n")
    builder.append("    } finally {\n")
    builder.append("      endConstructorRef(readContext)\n")
    builder.append("    }\n")
    builder.append("  }\n\n")

    builder.append(
      "  private fun readSchemaConstructorField(readContext: ReadContext, fieldInfo: SerializationFieldInfo, fieldId: Int): Any? {\n"
    )
    builder.append("    val buffer = readContext.buffer\n")
    builder.append("    return when (fieldId) {\n")
    for (field in struct.fields) {
      val direct = directReadExpression(field)
      val readExpression =
        direct ?: castReadExpression(field, "readFieldValue(readContext, fieldInfo)")
      val expression = constructorReadExpression(field, readExpression)
      if (field.trackingRef) {
        builder.append("      ").append(field.id).append(" -> {\n")
        builder.append("        trackConstructorRefRead(readContext, buffer)\n")
        builder.append("        ").append(expression).append("\n")
        builder.append("      }\n")
      } else {
        builder.append("      ").append(field.id).append(" -> ").append(expression).append("\n")
      }
    }
    builder.append(
      "      else -> throw IllegalStateException(\"Unknown generated field id \${fieldId}\")\n"
    )
    builder.append("    }\n")
    builder.append("  }\n\n")
  }

  private fun writeComparatorPreflight() {
    if (!hasComparatorGuards) {
      return
    }
    for (field in struct.fields) {
      appendComparatorGuard(
        field.type,
        "value.${field.name}",
        "    ",
        "${struct.qualifiedTypeName}.${field.name}",
        "guard${field.id}",
        0,
      )
    }
  }

  private fun needsComparatorGuard(type: KotlinSourceTypeNode): Boolean =
    when {
      type.nullable -> needsComparatorGuard(type.copy(nullable = false))
      type.collectionFactory == CollectionFactory.TREE_SET ||
        type.collectionFactory == CollectionFactory.CONCURRENT_SKIP_LIST_SET ||
        type.collectionFactory == CollectionFactory.TREE_MAP ||
        type.collectionFactory == CollectionFactory.CONCURRENT_SKIP_LIST_MAP -> true
      type.typeId == "Types.LIST" || type.typeId == "Types.SET" ->
        type.typeArguments.any { needsComparatorGuard(it) }
      type.typeId == "Types.MAP" -> type.typeArguments.any { needsComparatorGuard(it) }
      else -> false
    }

  private fun appendComparatorGuard(
    type: KotlinSourceTypeNode,
    expression: String,
    indent: String,
    path: String,
    prefix: String,
    depth: Int,
  ) {
    if (!needsComparatorGuard(type)) {
      return
    }
    if (type.nullable) {
      val nullableValue = "${prefix}Value$depth"
      builder
        .append(indent)
        .append("val ")
        .append(nullableValue)
        .append(" = ")
        .append(expression)
        .append("\n")
      builder.append(indent).append("if (").append(nullableValue).append(" != null) {\n")
      appendComparatorGuard(
        type.copy(nullable = false),
        nullableValue,
        "$indent  ",
        path,
        prefix,
        depth + 1,
      )
      builder.append(indent).append("}\n")
      return
    }
    when (type.collectionFactory) {
      CollectionFactory.TREE_SET,
      CollectionFactory.CONCURRENT_SKIP_LIST_SET ->
        builder
          .append(indent)
          .append("requireXlangNaturalOrdering(\"")
          .append(escape(path))
          .append("\", (")
          .append(expression)
          .append(" as java.util.SortedSet<*>).comparator())\n")
      CollectionFactory.TREE_MAP,
      CollectionFactory.CONCURRENT_SKIP_LIST_MAP ->
        builder
          .append(indent)
          .append("requireXlangNaturalOrdering(\"")
          .append(escape(path))
          .append("\", (")
          .append(expression)
          .append(" as java.util.SortedMap<*, *>).comparator())\n")
      else -> {}
    }
    when (type.typeId) {
      "Types.LIST" -> {
        val element = type.typeArguments[0]
        if (!needsComparatorGuard(element)) {
          return
        }
        val source = "${prefix}List$depth"
        val index = "${prefix}Index$depth"
        val elementName = "${prefix}Element$depth"
        builder
          .append(indent)
          .append("val ")
          .append(source)
          .append(" = ")
          .append(expression)
          .append("\n")
        builder
          .append(indent)
          .append("if (")
          .append(source)
          .append(" is java.util.RandomAccess) {\n")
        builder.append(indent).append("  var ").append(index).append(" = 0\n")
        builder
          .append(indent)
          .append("  ")
          .append("while (")
          .append(index)
          .append(" < ")
          .append(source)
          .append(".size) {\n")
        appendComparatorGuard(
          element,
          "$source[$index]",
          "$indent    ",
          "$path element",
          prefix,
          depth + 1,
        )
        builder.append(indent).append("    ").append(index).append("++\n")
        builder.append(indent).append("  }\n")
        builder.append(indent).append("} else {\n")
        builder
          .append(indent)
          .append("  for (")
          .append(elementName)
          .append(" in ")
          .append(source)
          .append(") {\n")
        appendComparatorGuard(
          element,
          elementName,
          "$indent    ",
          "$path element",
          prefix,
          depth + 1,
        )
        builder.append(indent).append("  }\n")
        builder.append(indent).append("}\n")
      }
      "Types.SET" -> {
        val element = type.typeArguments[0]
        if (!needsComparatorGuard(element)) {
          return
        }
        val elementName = "${prefix}Element$depth"
        builder
          .append(indent)
          .append("for (")
          .append(elementName)
          .append(" in ")
          .append(expression)
          .append(") {\n")
        appendComparatorGuard(
          element,
          elementName,
          "$indent  ",
          "$path element",
          prefix,
          depth + 1,
        )
        builder.append(indent).append("}\n")
      }
      "Types.MAP" -> {
        val key = type.typeArguments[0]
        val value = type.typeArguments[1]
        if (!needsComparatorGuard(key) && !needsComparatorGuard(value)) {
          return
        }
        val entry = "${prefix}Entry$depth"
        builder
          .append(indent)
          .append("for (")
          .append(entry)
          .append(" in ")
          .append(expression)
          .append(".entries) {\n")
        appendComparatorGuard(key, "$entry.key", "$indent  ", "$path key", prefix, depth + 1)
        appendComparatorGuard(value, "$entry.value", "$indent  ", "$path value", prefix, depth + 2)
        builder.append(indent).append("}\n")
      }
    }
  }

  private fun writeMutableReadBody() {
    builder.append("    val value = ").append(struct.typeName).append("()\n")
    builder.append("    if (readContext.hasPreservedRefId()) {\n")
    builder.append("      readContext.reference(value)\n")
    builder.append("    }\n")
    builder.append("    for (i in allFields.indices) {\n")
    builder.append("      val fieldInfo = allFields[i]\n")
    builder.append("      when (allFieldIds[i]) {\n")
    for (field in struct.fields) {
      val direct = directReadExpression(field)
      val expression = direct ?: castReadExpression(field, "readFieldValue(readContext, fieldInfo)")
      builder
        .append("        ")
        .append(field.id)
        .append(" -> value.")
        .append(field.name)
        .append(" = ")
        .append(expression)
        .append("\n")
    }
    builder.append(
      "        else -> throw IllegalStateException(\"Unknown generated field id \${allFieldIds[i]}\")\n"
    )
    builder.append("      }\n")
    builder.append("    }\n")
    builder.append("    return value\n")
  }

  private fun writeCompatibleRead() {
    builder
      .append("  override fun readCompatible(readContext: ReadContext): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    if (sameSchemaCompatible) {\n")
    builder.append("      return readSchemaConsistent(readContext)\n")
    builder.append("    }\n")
    if (struct.construction == KotlinStructConstruction.CONSTRUCTOR) {
      builder.append("    return readCompatibleConstructor(readContext)\n")
      builder.append("  }\n\n")
      writeCompatibleConstructorRead()
      return
    }
    if (struct.construction == KotlinStructConstruction.MUTABLE) {
      writeMutableCompatibleReadBody()
      builder.append("  }\n\n")
      return
    }
    writeCompatibleValueReadBody("    ", constructorRefs = false)
    builder.append("  }\n\n")
  }

  private fun writeCompatibleConstructorRead() {
    builder
      .append("  private fun readCompatibleConstructor(readContext: ReadContext): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    beginConstructorRef(readContext)\n")
    builder.append("    try {\n")
    writeCompatibleValueReadBody("      ", constructorRefs = true)
    builder.append("    } finally {\n")
    builder.append("      endConstructorRef(readContext)\n")
    builder.append("    }\n")
    builder.append("  }\n\n")
  }

  private fun writeCompatibleValueReadBody(indent: String, constructorRefs: Boolean) {
    writePresenceVars(indent)
    writeLocalDeclarations(indent)
    builder.append(indent).append("for (i in remoteFields.indices) {\n")
    builder.append(indent).append("  val remoteField = remoteFields[i]\n")
    builder.append(indent).append("  when (remoteField.matchedId) {\n")
    for (field in struct.fields) {
      builder.append(indent).append("    ").append(field.id * 2).append(" -> {\n")
      builder.append(indent).append("      val buffer = readContext.buffer\n")
      val directRead =
        directReadExpression(field)
          ?: castReadExpression(
            field,
            "readFieldValue(readContext, fieldsById[${field.id}]!!)",
            compatible = false,
          )
      val constructorDirectRead =
        if (constructorRefs && field.trackingRef) {
          "run { trackConstructorRefRead(readContext, buffer); ctorFieldValue(readContext, $directRead, type) }"
        } else if (constructorRefs) {
          "ctorFieldValue(readContext, $directRead, type)"
        } else {
          directRead
        }
      val directAssignment =
        if (constructorRefs) {
          localValueExpression(field, constructorDirectRead)
        } else {
          constructorDirectRead
        }
      builder
        .append(indent)
        .append("      ")
        .append(field.localName)
        .append(" = ")
        .append(directAssignment)
        .append("\n")
      builder.append(indent).append("      ")
      appendPresenceSet(field)
      builder.append("\n")
      builder.append(indent).append("    }\n")
      builder.append(indent).append("    ").append(field.id * 2 + 1).append(" -> {\n")
      builder
        .append(indent)
        .append("      val localField = fieldsById[")
        .append(field.id)
        .append("]!!\n")
      val readExpression =
        compatibleScalarReadExpression(field)
          ?: "readCompatibleFieldValue(readContext, remoteField, localField)"
      val constructorReadExpression =
        if (constructorRefs && field.trackingRef) {
          "run { trackConstructorRefRead(readContext, readContext.buffer); ctorFieldValue(readContext, $readExpression, type) }"
        } else if (constructorRefs) {
          "ctorFieldValue(readContext, $readExpression, type)"
        } else {
          readExpression
        }
      builder
        .append(indent)
        .append("        ")
        .append(field.localName)
        .append(" = ")
        .append(
          castReadExpression(
            field,
            constructorReadExpression,
            compatible = readExpression.startsWith("readCompatibleFieldValue"),
          )
        )
        .append("\n")
      builder.append(indent).append("      ")
      appendPresenceSet(field)
      builder.append("\n")
      builder.append(indent).append("    }\n")
    }
    builder.append(indent).append("    -1 -> skipField(readContext, remoteField)\n")
    builder
      .append(indent)
      .append(
        "    else -> throw IllegalStateException(\"Invalid compatible matched id \${remoteField.matchedId}\")\n"
      )
    builder.append(indent).append("  }\n")
    builder.append(indent).append("}\n")
    writeMissingRequiredChecks(indent)
    builder.append(indent).append("var missingDefaultMask = 0\n")
    val defaultFields = struct.fields.filter { it.hasDefault }
    for (field in struct.fields) {
      if (!field.hasDefault && field.nullable) {
        continue
      }
      builder.append(indent).append("if (")
      appendPresenceMissing(field)
      builder.append(") {\n")
      if (field.hasDefault) {
        builder
          .append(indent)
          .append("  missingDefaultMask = missingDefaultMask or ")
          .append(1 shl defaultFields.indexOf(field))
          .append("\n")
      }
      builder.append(indent).append("}\n")
    }
    if (constructorRefs) {
      builder.append(indent).append("checkNoUnresolvedReadRef(readContext)\n")
    }
    writeDefaultDispatch(indent, constructorRefs)
  }

  private fun writeMutableCompatibleReadBody() {
    writePresenceVars()
    builder.append("    val value = ").append(struct.typeName).append("()\n")
    builder.append("    if (readContext.hasPreservedRefId()) {\n")
    builder.append("      readContext.reference(value)\n")
    builder.append("    }\n")
    builder.append("    for (i in remoteFields.indices) {\n")
    builder.append("      val remoteField = remoteFields[i]\n")
    builder.append("      when (remoteField.matchedId) {\n")
    for (field in struct.fields) {
      builder.append("        ").append(field.id * 2).append(" -> {\n")
      builder.append("          val buffer = readContext.buffer\n")
      val directRead =
        directReadExpression(field)
          ?: castReadExpression(
            field,
            "readFieldValue(readContext, fieldsById[${field.id}]!!)",
            compatible = false,
          )
      builder
        .append("            value.")
        .append(field.name)
        .append(" = ")
        .append(directRead)
        .append("\n")
      builder.append("            ")
      appendPresenceSet(field)
      builder.append("\n")
      builder.append("        }\n")
      builder.append("        ").append(field.id * 2 + 1).append(" -> {\n")
      builder.append("          val localField = fieldsById[").append(field.id).append("]!!\n")
      builder
        .append("          value.")
        .append(field.name)
        .append(" = ")
        .append(
          castReadExpression(
            field,
            compatibleScalarReadExpression(field)
              ?: "readCompatibleFieldValue(readContext, remoteField, localField)",
            compatible = compatibleScalarReadExpression(field) == null,
          )
        )
        .append("\n")
      builder.append("          ")
      appendPresenceSet(field)
      builder.append("\n")
      builder.append("        }\n")
    }
    builder.append("        -1 -> skipField(readContext, remoteField)\n")
    builder.append(
      "        else -> throw IllegalStateException(\"Invalid compatible matched id \${remoteField.matchedId}\")\n"
    )
    builder.append("      }\n")
    builder.append("    }\n")
    writeMissingRequiredChecks("    ")
    builder.append("    return value\n")
  }

  private fun writeMissingRequiredChecks(indent: String) {
    for (field in struct.fields) {
      if (field.hasDefault || field.nullable) {
        continue
      }
      builder.append(indent).append("if (")
      appendPresenceMissing(field)
      builder.append(") {\n")
      builder
        .append(indent)
        .append("  throw DeserializationException(\"Required Kotlin field ")
        .append(struct.qualifiedTypeName)
        .append(".")
        .append(field.name)
        .append(" is missing\")\n")
      builder.append(indent).append("}\n")
    }
  }

  private fun writePresenceVars(indent: String = "    ") {
    val maxId = struct.fields.maxOfOrNull { it.id } ?: -1
    val chunks = maxId / java.lang.Long.SIZE + 1
    for (index in 0 until chunks) {
      builder.append(indent).append("var presentMask").append(index).append(" = 0L\n")
    }
  }

  private fun appendPresenceSet(field: KotlinSourceField) {
    val chunk = field.id / java.lang.Long.SIZE
    val bit = field.id % java.lang.Long.SIZE
    builder
      .append("presentMask")
      .append(chunk)
      .append(" = presentMask")
      .append(chunk)
      .append(" or (1L shl ")
      .append(bit)
      .append(")")
  }

  private fun appendPresenceMissing(field: KotlinSourceField) {
    val chunk = field.id / java.lang.Long.SIZE
    val bit = field.id % java.lang.Long.SIZE
    builder
      .append("(presentMask")
      .append(chunk)
      .append(" and (1L shl ")
      .append(bit)
      .append(")) == 0L")
  }

  private fun writeCopy() {
    builder
      .append("  override fun copy(copyContext: CopyContext, value: ")
      .append(struct.typeName)
      .append("): ")
      .append(struct.typeName)
      .append(" {\n")
    builder.append("    if (immutable) {\n")
    builder.append("      return value\n")
    builder.append("    }\n")
    if (struct.construction == KotlinStructConstruction.MUTABLE) {
      writeMutableCopyBody()
      builder.append("  }\n")
      return
    }
    builder.append("    return copyConstructorObject(copyContext, value)\n")
    builder.append("  }\n")
  }

  private fun writeMutableCopyBody() {
    builder.append("    val copy = ").append(struct.typeName).append("()\n")
    builder.append("    copyContext.reference(value, copy)\n")
    for (field in struct.fields) {
      builder.append("    copy.").append(field.name).append(" = ")
      if (isDirectCopyValue(field.type)) {
        builder.append("value.").append(field.name).append("\n")
      } else if (isDenseUnsignedArray(field)) {
        builder.append("value.").append(field.name)
        if (field.nullable) {
          builder.append("?.copyOf()\n")
        } else {
          builder.append(".copyOf()\n")
        }
      } else if (field.type.isCollectionOrMap()) {
        builder.append(copyContainerExpression(field.type, "value.${field.name}", 0)).append("\n")
      } else {
        builder
          .append(
            castReadExpression(
              field,
              "copyFieldValue(copyContext, value.${field.name}, fieldsById[${field.id}]!!)"
            )
          )
          .append("\n")
      }
    }
    builder.append("    return copy\n")
  }

  private fun copyExpression(field: KotlinSourceField): String =
    when {
      isDirectCopyValue(field.type) -> "value.${field.name}"
      isDenseUnsignedArray(field) ->
        if (field.nullable) "value.${field.name}?.copyOf()" else "value.${field.name}.copyOf()"
      field.type.isCollectionOrMap() ->
        copyContainerExpression(field.type, "value.${field.name}", 0)
      else ->
        castReadExpression(
          field,
          "copyFieldValue(copyContext, value.${field.name}, fieldsById[${field.id}]!!)"
        )
    }

  private fun constructorCopyExpression(field: KotlinSourceField): String {
    val expression =
      when {
        isDirectCopyValue(field.type) -> "value.${field.name}"
        isDenseUnsignedArray(field) ->
          if (field.nullable) "value.${field.name}?.copyOf()" else "value.${field.name}.copyOf()"
        field.type.isCollectionOrMap() ->
          return copyContainerExpression(field.type, "value.${field.name}", 0)
        else -> "copyFieldValue(copyContext, value.${field.name}, fieldsById[${field.id}]!!)"
      }
    return constructorReadExpression(field, expression)
  }

  private fun writeLocalDeclarations(indent: String = "    ") {
    for (field in struct.fields) {
      builder
        .append(indent)
        .append("var ")
        .append(field.localName)
        .append(": ")
        .append(localVariableType(field))
        .append(" = ")
        .append(defaultValue(field))
        .append("\n")
    }
  }

  private fun writeDefaultDispatch(indent: String = "    ", referenceConstructor: Boolean = false) {
    val defaultFields = struct.fields.filter { it.hasDefault }
    if (defaultFields.isEmpty()) {
      if (referenceConstructor) {
        builder.append(indent).append("val constructed = ")
        appendConstructorCall(defaultMask = 0)
        builder.append("\n")
        builder.append(indent).append("referenceConstructorRef(readContext, constructed)\n")
        builder.append(indent).append("return constructed\n")
      } else {
        builder.append(indent).append("return ")
        appendConstructorCall(defaultMask = 0)
        builder.append("\n")
      }
      return
    }
    if (referenceConstructor) {
      builder.append(indent).append("val constructed = when (missingDefaultMask) {\n")
    } else {
      builder.append(indent).append("return when (missingDefaultMask) {\n")
    }
    val combinations = 1 shl defaultFields.size
    for (combination in 0 until combinations) {
      var mask = 0
      for (i in defaultFields.indices) {
        if ((combination and (1 shl i)) != 0) {
          mask = mask or (1 shl i)
        }
      }
      builder.append(indent).append("  ").append(mask).append(" -> ")
      appendConstructorCall(defaultMask = mask)
      builder.append("\n")
    }
    builder.append(
      indent +
        "  else -> throw DeserializationException(\"Unsupported Kotlin default argument mask \${missingDefaultMask} for "
    )
    builder.append(struct.qualifiedTypeName).append("\")\n")
    builder.append(indent).append("}\n")
    if (referenceConstructor) {
      builder.append(indent).append("referenceConstructorRef(readContext, constructed)\n")
      builder.append(indent).append("return constructed\n")
    }
  }

  private fun appendConstructorCall(defaultMask: Int) {
    val defaultFields = struct.fields.filter { it.hasDefault }
    builder.append(struct.typeName).append("(")
    var first = true
    for (field in struct.fields) {
      if (field.hasDefault) {
        val defaultIndex = defaultFields.indexOf(field)
        if (defaultIndex >= 0 && (defaultMask and (1 shl defaultIndex)) != 0) {
          continue
        }
      }
      if (!first) {
        builder.append(", ")
      }
      builder
        .append(field.constructorParameterName)
        .append(" = ")
        .append(constructorValueExpression(field))
      first = false
    }
    builder.append(")")
  }

  private fun appendFieldValuesConstructorCall() {
    builder.append(struct.typeName).append("(")
    var first = true
    for (field in struct.fields) {
      if (!first) {
        builder.append(", ")
      }
      builder
        .append(field.constructorParameterName)
        .append(" = ")
        .append(constructorFieldValueExpression(field))
      first = false
    }
    builder.append(")")
  }

  private fun constructorFieldValueExpression(field: KotlinSourceField): String {
    val source = "fieldValues[${field.id}]"
    if (field.propertyTypeName == "Any?") {
      return source
    }
    return "($source as ${field.propertyTypeName})"
  }

  private fun localValueExpression(field: KotlinSourceField, expression: String): String {
    if (field.type.valueTypeName == "Any?") {
      return expression
    }
    return "($expression as ${field.type.valueTypeName})"
  }

  private fun constructorValueExpression(field: KotlinSourceField): String {
    val localValue =
      if (field.nullable || field.type.primitive || isScalarUnsigned(field)) {
        field.localName
      } else {
        "${field.localName}!!"
      }
    return constructorReadExpression(field, localValue)
  }

  private fun constructorReadExpression(field: KotlinSourceField, valueExpression: String): String {
    return collectionReadExpression(field.type, valueExpression, 0, erasedInput = false)
  }

  private fun collectionReadExpression(
    type: KotlinSourceTypeNode,
    valueExpression: String,
    depth: Int,
    erasedInput: Boolean
  ): String {
    if (!type.isCollectionOrMap()) {
      return valueExpression
    }
    if (type.nullable) {
      val value = "readValue$depth"
      return "$valueExpression?.let { $value -> ${collectionReadExpression(type.copy(nullable = false), value, depth + 1, erasedInput)} }"
    }
    if (type.typeArguments.any { needsCollectionReadAdaptation(it) }) {
      return readContainerExpression(type, valueExpression, depth)
    }
    return applyCollectionFactory(type, valueExpression, erasedInput)
  }

  private fun needsCollectionReadAdaptation(type: KotlinSourceTypeNode): Boolean =
    type.collectionFactory != CollectionFactory.NONE ||
      (type.isCollectionOrMap() && type.typeArguments.any { needsCollectionReadAdaptation(it) })

  private fun readContainerExpression(
    type: KotlinSourceTypeNode,
    expression: String,
    depth: Int
  ): String {
    val source = "readSource$depth"
    val target = "readTarget$depth"
    return when (type.typeId) {
      "Types.LIST" -> {
        val element = "readElement$depth"
        val adaptedElement = readElementExpression(type.typeArguments[0], element, depth + 1)
        val targetBuild = readTarget(type, source, target)
        "run { val $source = ${erasedCollectionExpression(type, expression)}; val $target = ${targetBuild.init}; for ($element in $source) { $target.add($adaptedElement) }; ${targetBuild.result} }"
      }
      "Types.SET" -> {
        val element = "readElement$depth"
        val adaptedElement = readElementExpression(type.typeArguments[0], element, depth + 1)
        val targetBuild = readTarget(type, source, target)
        "run { val $source = ${erasedCollectionExpression(type, expression)}; val $target = ${targetBuild.init}; for ($element in $source) { $target.add($adaptedElement) }; ${targetBuild.result} }"
      }
      "Types.MAP" -> {
        val entry = "readEntry$depth"
        val adaptedKey = readElementExpression(type.typeArguments[0], "$entry.key", depth + 1)
        val adaptedValue = readElementExpression(type.typeArguments[1], "$entry.value", depth + 2)
        val targetBuild = readTarget(type, source, target)
        "run { val $source = ${erasedCollectionExpression(type, expression)}; val $target = ${targetBuild.init}; for ($entry in $source.entries) { $target[$adaptedKey] = $adaptedValue }; ${targetBuild.result} }"
      }
      else -> expression
    }
  }

  private fun readElementExpression(
    type: KotlinSourceTypeNode,
    expression: String,
    depth: Int
  ): String {
    if (!needsCollectionReadAdaptation(type)) {
      return expression
    }
    return collectionReadExpression(type, expression, depth, erasedInput = true)
  }

  private fun erasedCollectionExpression(type: KotlinSourceTypeNode, expression: String): String =
    when (type.typeId) {
      "Types.LIST",
      "Types.SET" -> "($expression as Collection<*>)"
      "Types.MAP" -> "($expression as Map<*, *>)"
      else -> expression
    }

  private fun typedCollectionExpression(type: KotlinSourceTypeNode, expression: String): String {
    if (!type.isCollectionOrMap()) {
      return expression
    }
    return "(${erasedCollectionExpression(type, expression)} as ${type.valueTypeName.removeSuffix("?")})"
  }

  private fun applyCollectionFactory(
    type: KotlinSourceTypeNode,
    valueExpression: String,
    erasedInput: Boolean = false
  ): String {
    if (type.collectionFactory == CollectionFactory.NONE) {
      return valueExpression
    }
    val conversion = collectionConversionFunction(type.collectionFactory)
    if (!erasedInput) {
      return "$conversion($valueExpression)"
    }
    return typedCollectionExpression(
      type,
      "$conversion(${erasedCollectionExpression(type, valueExpression)})"
    )
  }

  private fun readTarget(
    type: KotlinSourceTypeNode,
    source: String,
    target: String
  ): ContainerTarget {
    val valueType = type.valueTypeName.removeSuffix("?")
    fun castTarget(): String = "$target as $valueType"
    return when (type.typeId) {
      "Types.LIST" ->
        when (type.collectionFactory) {
          CollectionFactory.LINKED_LIST ->
            ContainerTarget("java.util.LinkedList<Any?>()", castTarget())
          CollectionFactory.COPY_ON_WRITE_ARRAY_LIST ->
            ContainerTarget(
              "java.util.ArrayList<Any?>($source.size)",
              "java.util.concurrent.CopyOnWriteArrayList($target) as $valueType"
            )
          else -> ContainerTarget("java.util.ArrayList<Any?>($source.size)", castTarget())
        }
      "Types.SET" ->
        when (type.collectionFactory) {
          CollectionFactory.HASH_SET ->
            ContainerTarget("java.util.HashSet<Any?>($source.size)", castTarget())
          CollectionFactory.TREE_SET -> ContainerTarget("java.util.TreeSet<Any?>()", castTarget())
          CollectionFactory.COPY_ON_WRITE_ARRAY_SET ->
            ContainerTarget(
              "java.util.LinkedHashSet<Any?>($source.size)",
              "java.util.concurrent.CopyOnWriteArraySet($target) as $valueType"
            )
          CollectionFactory.CONCURRENT_SKIP_LIST_SET ->
            ContainerTarget("java.util.concurrent.ConcurrentSkipListSet<Any?>()", castTarget())
          else -> ContainerTarget("java.util.LinkedHashSet<Any?>($source.size)", castTarget())
        }
      "Types.MAP" ->
        when (type.collectionFactory) {
          CollectionFactory.HASH_MAP ->
            ContainerTarget("java.util.HashMap<Any?, Any?>($source.size)", castTarget())
          CollectionFactory.TREE_MAP ->
            ContainerTarget("java.util.TreeMap<Any?, Any?>()", castTarget())
          CollectionFactory.CONCURRENT_HASH_MAP ->
            ContainerTarget(
              "java.util.concurrent.ConcurrentHashMap<Any?, Any?>($source.size)",
              castTarget()
            )
          CollectionFactory.CONCURRENT_SKIP_LIST_MAP ->
            ContainerTarget(
              "java.util.concurrent.ConcurrentSkipListMap<Any?, Any?>()",
              castTarget()
            )
          else -> ContainerTarget("java.util.LinkedHashMap<Any?, Any?>($source.size)", castTarget())
        }
      else -> ContainerTarget("null", castTarget())
    }
  }

  private fun localVariableType(field: KotlinSourceField): String {
    if (field.nullable || field.type.primitive || isScalarUnsigned(field)) {
      return field.type.valueTypeName
    }
    return field.type.valueTypeName + "?"
  }

  private fun defaultValue(field: KotlinSourceField): String {
    if (field.nullable) {
      return "null"
    }
    return when (field.propertyTypeName) {
      "Boolean" -> "false"
      "Byte" -> "0"
      "Short" -> "0"
      "Int" -> "0"
      "Long" -> "0L"
      "UByte" -> "0u"
      "UShort" -> "0u"
      "UInt" -> "0u"
      "ULong" -> "0uL"
      "Float" -> "0.0f"
      "Double" -> "0.0"
      else -> "null"
    }
  }

  private fun castReadExpression(
    field: KotlinSourceField,
    expression: String,
    compatible: Boolean = false,
  ): String {
    val denseListConversion = denseArrayListConversion(field)
    if (denseListConversion != null) {
      if (field.nullable) {
        return "($expression as Collection<*>?)?.let { KotlinCollectionAdapters.$denseListConversion(it) }"
      }
      return "KotlinCollectionAdapters.$denseListConversion($expression as Collection<*>)"
    }
    val denseUnsigned = denseUnsignedArrayConversion(field)
    if (denseUnsigned != null) {
      if (field.nullable) {
        return "($expression as ${denseUnsignedDelegate(field)}?)?.let { KotlinXlangArrayEncoding.$denseUnsigned(it) }"
      }
      return "KotlinXlangArrayEncoding.$denseUnsigned($expression as ${denseUnsignedDelegate(field)})"
    }
    if (compatible && hasKotlinScalar(field.type)) {
      return fromJavaCompatExpr(field.type, expression)
    }
    if (field.type.valueTypeName == "Any?") {
      return expression
    }
    return "($expression as ${field.type.valueTypeName})"
  }

  private fun compatibleScalarReadExpression(field: KotlinSourceField): String? {
    if (field.type.componentType != null || field.type.typeArguments.isNotEmpty()) {
      return null
    }
    val nullable = field.type.nullable
    val helperCall =
      when (field.type.valueTypeName.removeSuffix("?")) {
        "Boolean" ->
          if (nullable) {
            "FieldConverters.readBoxedBooleanTarget(readContext, remoteField.serializationFieldInfo, localField)"
          } else {
            "FieldConverters.readBooleanTarget(readContext, remoteField.serializationFieldInfo, localField)"
          }
        "Byte" ->
          if (nullable) {
            "FieldConverters.readBoxedByteTarget(readContext, remoteField.serializationFieldInfo, localField)"
          } else {
            "FieldConverters.readByteTarget(readContext, remoteField.serializationFieldInfo, localField)"
          }
        "Short" ->
          if (nullable) {
            "FieldConverters.readBoxedShortTarget(readContext, remoteField.serializationFieldInfo, localField)"
          } else {
            "FieldConverters.readShortTarget(readContext, remoteField.serializationFieldInfo, localField)"
          }
        "Int" ->
          if (nullable) {
            "FieldConverters.readBoxedIntTarget(readContext, remoteField.serializationFieldInfo, localField)"
          } else {
            "FieldConverters.readIntTarget(readContext, remoteField.serializationFieldInfo, localField)"
          }
        "Long" ->
          if (nullable) {
            "FieldConverters.readBoxedLongTarget(readContext, remoteField.serializationFieldInfo, localField)"
          } else {
            "FieldConverters.readLongTarget(readContext, remoteField.serializationFieldInfo, localField)"
          }
        "Float" ->
          if (nullable) {
            "FieldConverters.readBoxedFloatTarget(readContext, remoteField.serializationFieldInfo, localField)"
          } else {
            "FieldConverters.readFloatTarget(readContext, remoteField.serializationFieldInfo, localField)"
          }
        "Double" ->
          if (nullable) {
            "FieldConverters.readBoxedDoubleTarget(readContext, remoteField.serializationFieldInfo, localField)"
          } else {
            "FieldConverters.readDoubleTarget(readContext, remoteField.serializationFieldInfo, localField)"
          }
        "String" ->
          "FieldConverters.readStringTarget(readContext, remoteField.serializationFieldInfo, localField)"
        "java.math.BigDecimal" ->
          "FieldConverters.readDecimalTarget(readContext, remoteField.serializationFieldInfo, localField)"
        "UByte" ->
          if (nullable) {
            "FieldConverters.readBoxedIntTarget(readContext, remoteField.serializationFieldInfo, localField)?.toUByte()"
          } else {
            "FieldConverters.readIntTarget(readContext, remoteField.serializationFieldInfo, localField).toUByte()"
          }
        "UShort" ->
          if (nullable) {
            "FieldConverters.readBoxedIntTarget(readContext, remoteField.serializationFieldInfo, localField)?.toUShort()"
          } else {
            "FieldConverters.readIntTarget(readContext, remoteField.serializationFieldInfo, localField).toUShort()"
          }
        "UInt" ->
          if (nullable) {
            "FieldConverters.readBoxedLongTarget(readContext, remoteField.serializationFieldInfo, localField)?.toUInt()"
          } else {
            "FieldConverters.readLongTarget(readContext, remoteField.serializationFieldInfo, localField).toUInt()"
          }
        "ULong" ->
          if (nullable) {
            "FieldConverters.readBoxedLongTarget(readContext, remoteField.serializationFieldInfo, localField)?.toULong()"
          } else {
            "FieldConverters.readLongTarget(readContext, remoteField.serializationFieldInfo, localField).toULong()"
          }
        else -> return null
      }
    return helperCall
  }

  private fun hasKotlinScalar(type: KotlinSourceTypeNode): Boolean =
    (type.unsigned && type.componentType == null) ||
      type.typeId == "Types.DURATION" ||
      type.typeArguments.any { hasKotlinScalar(it) }

  private fun needsScalarSerializer(type: KotlinSourceTypeNode): Boolean =
    (type.unsigned && type.componentType == null) ||
      type.typeId == "Types.DURATION" ||
      type.typeArguments.any { needsScalarSerializer(it) }

  private fun KotlinSourceTypeNode.isCollectionOrMap(): Boolean =
    typeId == "Types.LIST" || typeId == "Types.SET" || typeId == "Types.MAP"

  private fun isDirectCopyValue(type: KotlinSourceTypeNode): Boolean =
    type.typeArguments.isEmpty() &&
      type.componentType == null &&
      when (type.typeId) {
        "Types.BOOL",
        "Types.INT8",
        "Types.UINT8",
        "Types.INT16",
        "Types.UINT16",
        "Types.INT32",
        "Types.VARINT32",
        "Types.UINT32",
        "Types.VAR_UINT32",
        "Types.INT64",
        "Types.VARINT64",
        "Types.TAGGED_INT64",
        "Types.UINT64",
        "Types.VAR_UINT64",
        "Types.TAGGED_UINT64",
        "Types.FLOAT32",
        "Types.FLOAT64",
        "Types.STRING",
        "Types.DATE",
        "Types.TIMESTAMP",
        "Types.DURATION",
        "Types.DECIMAL",
        "Types.FLOAT16",
        "Types.BFLOAT16" -> true
        else -> false
      }

  private fun copiesWithContainerSerializer(type: KotlinSourceTypeNode): Boolean =
    when (type.collectionFactory) {
      CollectionFactory.NONE,
      CollectionFactory.MUTABLE_LIST,
      CollectionFactory.MUTABLE_SET,
      CollectionFactory.MUTABLE_MAP -> false
      else -> true
    }

  private fun copyContainerExpression(
    type: KotlinSourceTypeNode,
    expression: String,
    depth: Int
  ): String {
    if (type.nullable) {
      val source = "copySource$depth"
      return "$expression?.let { $source -> ${copyContainerExpression(type.copy(nullable = false), source, depth + 1)} }"
    }
    if (copiesWithContainerSerializer(type)) {
      return "(copyContext.copyObject($expression) as ${type.valueTypeName.removeSuffix("?")})"
    }
    val source = "copySource$depth"
    val target = "copyTarget$depth"
    val copied =
      when (type.typeId) {
        "Types.LIST" -> {
          val element = "copyElement$depth"
          val copiedElement = copyElementExpression(type.typeArguments[0], element, depth + 1)
          val targetBuild = copyTarget(type, source, target)
          "run { val $source = $expression; val $target = ${targetBuild.init}; copyContext.reference<Any>($source, $target); for ($element in $source) { $target.add($copiedElement) }; ${targetBuild.result} }"
        }
        "Types.SET" -> {
          val element = "copyElement$depth"
          val copiedElement = copyElementExpression(type.typeArguments[0], element, depth + 1)
          val targetBuild = copyTarget(type, source, target)
          "run { val $source = $expression; val $target = ${targetBuild.init}; copyContext.reference<Any>($source, $target); for ($element in $source) { $target.add($copiedElement) }; ${targetBuild.result} }"
        }
        "Types.MAP" -> {
          val entry = "copyEntry$depth"
          val copiedKey = copyElementExpression(type.typeArguments[0], "$entry.key", depth + 1)
          val copiedValue = copyElementExpression(type.typeArguments[1], "$entry.value", depth + 2)
          val targetBuild = copyTarget(type, source, target)
          "run { val $source = $expression; val $target = ${targetBuild.init}; copyContext.reference<Any>($source, $target); for ($entry in $source.entries) { $target[$copiedKey] = $copiedValue }; ${targetBuild.result} }"
        }
        else -> "copyContext.copyObject($expression) as ${type.valueTypeName.removeSuffix("?")}"
      }
    return copied
  }

  private fun copyTarget(
    type: KotlinSourceTypeNode,
    source: String,
    target: String
  ): ContainerTarget {
    val valueType = type.valueTypeName.removeSuffix("?")
    fun castTarget(): String = "$target as $valueType"
    return when (type.typeId) {
      "Types.LIST" -> ContainerTarget("java.util.ArrayList<Any?>($source.size)", castTarget())
      "Types.SET" -> ContainerTarget("java.util.LinkedHashSet<Any?>($source.size)", castTarget())
      "Types.MAP" ->
        ContainerTarget("java.util.LinkedHashMap<Any?, Any?>($source.size)", castTarget())
      else -> ContainerTarget("null", castTarget())
    }
  }

  private fun copyElementExpression(
    type: KotlinSourceTypeNode,
    expression: String,
    depth: Int
  ): String {
    if (type.nullable) {
      val value = "copyValue$depth"
      return "$expression?.let { $value -> ${copyElementExpression(type.copy(nullable = false), value, depth + 1)} }"
    }
    val arrayCopy = copyArrayExpression(type, expression)
    if (arrayCopy != null) {
      return arrayCopy
    }
    if (isDirectCopyValue(type)) {
      return expression
    }
    if (type.isCollectionOrMap()) {
      return copyContainerExpression(type, expression, depth + 1)
    }
    return "(copyContext.copyObject($expression) as ${type.valueTypeName.removeSuffix("?")})"
  }

  private fun copyArrayExpression(type: KotlinSourceTypeNode, expression: String): String? {
    val valueType = type.valueTypeName.removeSuffix("?")
    if (type.typeId == "Types.BINARY" && valueType == "ByteArray") {
      return "$expression.copyOf()"
    }
    if (type.componentType == null) {
      return null
    }
    return when (valueType) {
      "BooleanArray",
      "ByteArray",
      "ShortArray",
      "IntArray",
      "LongArray",
      "FloatArray",
      "DoubleArray",
      "UByteArray",
      "UShortArray",
      "UIntArray",
      "ULongArray" -> "$expression.copyOf()"
      else -> "(copyContext.copyObject($expression) as $valueType)"
    }
  }

  private fun fromJavaCompatExpr(
    type: KotlinSourceTypeNode,
    expression: String,
    depth: Int = 0
  ): String {
    if (type.nullable) {
      val valueName = "value$depth"
      return "($expression)?.let { $valueName -> ${fromJavaCompatExpr(type.copy(nullable = false), valueName, depth + 1)} }"
    }
    if (type.unsigned && type.componentType == null) {
      val compatibleValue = "compatibleValue$depth"
      return when (type.valueTypeName.removeSuffix("?")) {
        "UByte" -> unsignedCompatExpr(compatibleValue, expression, "UByte")
        "UShort" -> unsignedCompatExpr(compatibleValue, expression, "UShort")
        "UInt" -> unsignedCompatExpr(compatibleValue, expression, "UInt")
        "ULong" -> unsignedCompatExpr(compatibleValue, expression, "ULong")
        else -> expression
      }
    }
    if (type.typeId == "Types.DURATION") {
      val compatibleValue = "compatibleValue$depth"
      return "run { val $compatibleValue = $expression; if ($compatibleValue is kotlin.time.Duration) $compatibleValue else DurationEncoding.fromJava($compatibleValue as java.time.Duration) }"
    }
    if (type.typeArguments.isEmpty()) {
      return "($expression as ${type.valueTypeName})"
    }
    return when (type.typeId) {
      "Types.LIST",
      "Types.SET" -> {
        val element = type.typeArguments[0]
        if (hasKotlinScalar(element)) {
          val elementName = "element$depth"
          if (type.typeId == "Types.SET") {
            "run { val source$depth = ($expression as java.util.Collection<*>); val target$depth = java.util.LinkedHashSet<Any?>(source$depth.size()); for ($elementName in source$depth) { target$depth.add(${fromJavaCompatExpr(element, elementName, depth + 1)}) }; target$depth as ${type.valueTypeName} }"
          } else {
            "run { val source$depth = ($expression as java.util.Collection<*>); val target$depth = java.util.ArrayList<Any?>(source$depth.size()); for ($elementName in source$depth) { target$depth.add(${fromJavaCompatExpr(element, elementName, depth + 1)}) }; target$depth as ${type.valueTypeName} }"
          }
        } else {
          "($expression as ${type.valueTypeName})"
        }
      }
      "Types.MAP" -> {
        val key = type.typeArguments[0]
        val value = type.typeArguments[1]
        if (hasKotlinScalar(key) || hasKotlinScalar(value)) {
          val entryName = "entry$depth"
          "run { val source$depth = ($expression as kotlin.collections.Map<*, *>); val target$depth = java.util.LinkedHashMap<Any?, Any?>(source$depth.size); for ($entryName in source$depth.entries) { target$depth[${fromJavaCompatExpr(key, "$entryName.key", depth + 1)}] = ${fromJavaCompatExpr(value, "$entryName.value", depth + 1)} }; target$depth as ${type.valueTypeName} }"
        } else {
          "($expression as ${type.valueTypeName})"
        }
      }
      else -> "($expression as ${type.valueTypeName})"
    }
  }

  private fun unsignedCompatExpr(valueName: String, expression: String, target: String): String {
    val javaType =
      when (target) {
        "UByte" -> "org.apache.fory.type.unsigned.UInt8"
        "UShort" -> "org.apache.fory.type.unsigned.UInt16"
        "UInt" -> "org.apache.fory.type.unsigned.UInt32"
        "ULong" -> "org.apache.fory.type.unsigned.UInt64"
        else -> error("Unsupported Kotlin unsigned target $target")
      }
    val conversion =
      when (target) {
        "UByte" -> "$valueName.toInt().toUByte()"
        "UShort" -> "$valueName.toInt().toUShort()"
        "UInt" -> "$valueName.toLong().toUInt()"
        "ULong" -> "$valueName.toLong().toULong()"
        else -> error("Unsupported Kotlin unsigned target $target")
      }
    return "run { val $valueName = $expression; when ($valueName) { is $target -> $valueName; is $javaType -> $conversion; is Number -> $conversion; else -> run { val carrier: Any? = $valueName; throw DeserializationException(\"Compatible Kotlin $target field decoded unsupported value carrier \" + (carrier?.javaClass?.name ?: \"null\")) } } }"
  }

  private fun canDirect(field: KotlinSourceField): Boolean =
    !field.nullable &&
      !field.trackingRef &&
      field.type.typeArguments.isEmpty() &&
      field.type.componentType == null

  private fun isScalarUnsigned(field: KotlinSourceField): Boolean =
    field.type.unsigned && field.type.componentType == null

  private fun isDenseUnsignedArray(field: KotlinSourceField): Boolean =
    field.type.unsigned && field.type.componentType != null

  private fun directWriteStatement(field: KotlinSourceField, value: String): String? {
    val denseWrite = denseUnsignedArrayWrite(field)
    if (denseWrite != null && !field.nullable && !field.trackingRef) {
      return "KotlinXlangArrayEncoding.$denseWrite(writeContext, $value)"
    }
    if (denseWrite != null && field.nullable && !field.trackingRef) {
      val nullableArray = "nullableArray${field.id}"
      return "val $nullableArray = $value; if ($nullableArray == null) { buffer.writeByte(Fory.NULL_FLAG) } else { buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG); KotlinXlangArrayEncoding.$denseWrite(writeContext, $nullableArray) }"
    }
    if (isScalarUnsigned(field) && field.nullable && !field.trackingRef) {
      val nullableValue = "nullableValue${field.id}"
      val writeValue =
        when (field.type.typeId) {
          "Types.UINT8" -> "buffer.writeByte($nullableValue.toInt())"
          "Types.UINT16" -> "buffer.writeInt16($nullableValue.toShort())"
          "Types.UINT32" -> "buffer.writeInt32($nullableValue.toInt())"
          "Types.VAR_UINT32" -> "buffer.writeVarUInt32($nullableValue.toInt())"
          "Types.UINT64" -> "buffer.writeInt64($nullableValue.toLong())"
          "Types.VAR_UINT64" -> "buffer.writeVarUInt64($nullableValue.toLong())"
          "Types.TAGGED_UINT64" -> "buffer.writeTaggedUInt64($nullableValue.toLong())"
          else -> return null
        }
      return "val $nullableValue = $value; if ($nullableValue == null) { buffer.writeByte(Fory.NULL_FLAG) } else { buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG); $writeValue }"
    }
    if (field.type.typeId == "Types.DURATION" && !field.trackingRef) {
      if (field.nullable) {
        val nullableValue = "nullableValue${field.id}"
        return "val $nullableValue = $value; if ($nullableValue == null) { buffer.writeByte(Fory.NULL_FLAG) } else { buffer.writeByte(Fory.NOT_NULL_VALUE_FLAG); DurationEncoding.write(writeContext, $nullableValue) }"
      }
      return "DurationEncoding.write(writeContext, $value)"
    }
    if (!canDirect(field)) {
      return null
    }
    return when (field.type.typeId) {
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
      else -> null
    }
  }

  private fun directReadExpression(field: KotlinSourceField): String? {
    val denseRead = denseUnsignedArrayRead(field)
    if (denseRead != null && !field.nullable) {
      return "KotlinXlangArrayEncoding.$denseRead(readContext, typeResolver.config.maxBinarySize())"
    }
    if (denseRead != null && field.nullable && !field.trackingRef) {
      return "if (buffer.readByte() == Fory.NULL_FLAG) null else KotlinXlangArrayEncoding.$denseRead(readContext, typeResolver.config.maxBinarySize())"
    }
    if (isScalarUnsigned(field) && field.nullable && !field.trackingRef) {
      val readValue =
        when (field.type.typeId) {
          "Types.UINT8" -> "buffer.readByte().toUByte()"
          "Types.UINT16" -> "buffer.readInt16().toUShort()"
          "Types.UINT32" -> "buffer.readInt32().toUInt()"
          "Types.VAR_UINT32" -> "buffer.readVarUInt32().toUInt()"
          "Types.UINT64" -> "buffer.readInt64().toULong()"
          "Types.VAR_UINT64" -> "buffer.readVarUInt64().toULong()"
          "Types.TAGGED_UINT64" -> "buffer.readTaggedUInt64().toULong()"
          else -> return null
        }
      return "if (buffer.readByte() == Fory.NULL_FLAG) null else $readValue"
    }
    if (field.type.typeId == "Types.DURATION" && !field.trackingRef) {
      if (field.nullable) {
        return "if (buffer.readByte() == Fory.NULL_FLAG) null else DurationEncoding.read(readContext)"
      }
      return "DurationEncoding.read(readContext)"
    }
    if (!canDirect(field)) {
      return null
    }
    return when (field.type.typeId) {
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
      else -> null
    }
  }

  private fun denseUnsignedArrayRead(field: KotlinSourceField): String? =
    when (field.type.valueTypeName.removeSuffix("?")) {
      "UByteArray" -> "readUByteArray"
      "UShortArray" -> "readUShortArray"
      "UIntArray" -> "readUIntArray"
      "ULongArray" -> "readULongArray"
      else -> null
    }

  private fun denseArrayListConversion(field: KotlinSourceField): String? {
    if (!field.arrayType || field.type.typeArguments.size != 1) {
      return null
    }
    return when (field.type.typeArguments.single().valueTypeName.removeSuffix("?")) {
      "Byte" -> "toByteList"
      "Short" -> "toShortList"
      "Int" -> "toIntList"
      "Long" -> "toLongList"
      "UByte" -> "toUByteList"
      "UShort" -> "toUShortList"
      "UInt" -> "toUIntList"
      "ULong" -> "toULongList"
      "Float" -> "toFloatList"
      "Double" -> "toDoubleList"
      else -> null
    }
  }

  private fun denseUnsignedArrayConversion(field: KotlinSourceField): String? =
    when (field.type.valueTypeName.removeSuffix("?")) {
      "UByteArray" -> "toUByteArray"
      "UShortArray" -> "toUShortArray"
      "UIntArray" -> "toUIntArray"
      "ULongArray" -> "toULongArray"
      else -> null
    }

  private fun denseUnsignedArrayWrite(field: KotlinSourceField): String? =
    when (field.type.valueTypeName.removeSuffix("?")) {
      "UByteArray" -> "writeUByteArray"
      "UShortArray" -> "writeUShortArray"
      "UIntArray" -> "writeUIntArray"
      "ULongArray" -> "writeULongArray"
      else -> null
    }

  private fun denseUnsignedDelegate(field: KotlinSourceField): String =
    when (field.type.valueTypeName.removeSuffix("?")) {
      "UByteArray" -> "ByteArray"
      "UShortArray" -> "ShortArray"
      "UIntArray" -> "IntArray"
      "ULongArray" -> "LongArray"
      else -> error("No dense unsigned array delegate for ${field.type.valueTypeName}")
    }

  private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

  private fun collectionConversionFunction(factory: CollectionFactory): String =
    when (factory) {
      CollectionFactory.MUTABLE_LIST -> "KotlinCollectionAdapters.toMutableList"
      CollectionFactory.ARRAY_LIST -> "KotlinCollectionAdapters.toArrayList"
      CollectionFactory.LINKED_LIST -> "KotlinCollectionAdapters.toLinkedList"
      CollectionFactory.COPY_ON_WRITE_ARRAY_LIST ->
        "KotlinCollectionAdapters.toCopyOnWriteArrayList"
      CollectionFactory.MUTABLE_SET -> "KotlinCollectionAdapters.toMutableSet"
      CollectionFactory.HASH_SET -> "KotlinCollectionAdapters.toHashSet"
      CollectionFactory.LINKED_HASH_SET -> "KotlinCollectionAdapters.toLinkedHashSet"
      CollectionFactory.TREE_SET -> "KotlinCollectionAdapters.toTreeSet"
      CollectionFactory.COPY_ON_WRITE_ARRAY_SET -> "KotlinCollectionAdapters.toCopyOnWriteArraySet"
      CollectionFactory.CONCURRENT_SKIP_LIST_SET ->
        "KotlinCollectionAdapters.toConcurrentSkipListSet"
      CollectionFactory.MUTABLE_MAP -> "KotlinCollectionAdapters.toMutableMap"
      CollectionFactory.HASH_MAP -> "KotlinCollectionAdapters.toHashMap"
      CollectionFactory.LINKED_HASH_MAP -> "KotlinCollectionAdapters.toLinkedHashMap"
      CollectionFactory.TREE_MAP -> "KotlinCollectionAdapters.toTreeMap"
      CollectionFactory.CONCURRENT_HASH_MAP -> "KotlinCollectionAdapters.toConcurrentHashMap"
      CollectionFactory.CONCURRENT_SKIP_LIST_MAP ->
        "KotlinCollectionAdapters.toConcurrentSkipListMap"
      CollectionFactory.NONE -> error("No conversion function for NONE")
    }
}
