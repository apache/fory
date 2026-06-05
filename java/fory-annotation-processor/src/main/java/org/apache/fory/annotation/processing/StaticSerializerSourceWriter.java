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

package org.apache.fory.annotation.processing;

final class StaticSerializerSourceWriter {
  private static final int DISPATCH_GROUP_SIZE = 8;

  private final SourceStruct struct;
  private final StringBuilder builder = new StringBuilder(16384);

  StaticSerializerSourceWriter(SourceStruct struct) {
    this.struct = struct;
  }

  String write() {
    writeHeader();
    writeClassStart();
    writeDescriptors();
    writeConstructors();
    writeSerializerMethods();
    writeSchemaConsistentRead();
    writeWriteGroups();
    writeReadGroups();
    writeCompatibleRead();
    writeCopy();
    writeDescriptorHelpers();
    builder.append("}\n");
    return builder.toString();
  }

  private void writeHeader() {
    if (!struct.packageName.isEmpty()) {
      builder.append("package ").append(struct.packageName).append(";\n\n");
    }
    builder.append("import java.util.ArrayList;\n");
    builder.append("import java.util.Arrays;\n");
    builder.append("import java.util.Collections;\n");
    builder.append("import java.util.List;\n");
    builder.append("import org.apache.fory.annotation.ForyField;\n");
    builder.append("import org.apache.fory.context.CopyContext;\n");
    builder.append("import org.apache.fory.context.ReadContext;\n");
    builder.append("import org.apache.fory.context.WriteContext;\n");
    builder.append("import org.apache.fory.memory.MemoryBuffer;\n");
    builder.append("import org.apache.fory.meta.TypeDef;\n");
    builder.append("import org.apache.fory.meta.TypeExtMeta;\n");
    builder.append("import org.apache.fory.resolver.TypeResolver;\n");
    builder.append("import org.apache.fory.serializer.FieldGroups;\n");
    builder.append("import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;\n");
    builder.append("import org.apache.fory.serializer.StaticGeneratedStructSerializer;\n");
    builder.append("import org.apache.fory.type.Descriptor;\n");
    builder.append("import org.apache.fory.type.DispatchId;\n");
    builder.append("import org.apache.fory.type.Types;\n\n");
  }

  private void writeClassStart() {
    builder.append("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
    builder
        .append("public final class ")
        .append(struct.serializerName)
        .append(" extends StaticGeneratedStructSerializer<")
        .append(struct.typeName)
        .append("> {\n");
    builder
        .append("  private static final boolean HAS_NESTED_COMPATIBLE_STRUCT_FIELDS = ")
        .append(struct.hasNestedCompatibleStructFields)
        .append(";\n");
    if (struct.debug) {
      builder.append("  private static final boolean FORY_STRUCT_DEBUG = true;\n");
    }
    builder.append("  private static final List<Descriptor> DESCRIPTORS = buildDescriptors();\n\n");
    builder.append("  private final SerializationFieldInfo[] allFields;\n");
    builder.append("  private final int[] allFieldIds;\n");
    builder.append("  private final SerializationFieldInfo[] fieldsById;\n");
    builder.append("  private final int classVersionHash;\n");
    builder.append("  private final boolean sameSchemaCompatible;\n\n");
  }

  private void writeDescriptors() {
    builder.append("  private static List<Descriptor> buildDescriptors() {\n");
    builder
        .append("    ArrayList<Descriptor> descriptors = new ArrayList<Descriptor>(")
        .append(struct.fields.size())
        .append(");\n");
    for (SourceField field : struct.fields) {
      builder
          .append("    descriptors.add(new Descriptor(")
          .append(field.typeNode.generatedTypeExpression())
          .append(", \"")
          .append(escape(field.typeNode.typeName))
          .append("\", \"")
          .append(escape(field.name))
          .append("\", ")
          .append(field.modifiers)
          .append(", \"")
          .append(escape(field.declaringClass))
          .append("\", ")
          .append(field.hasForyField)
          .append(", ")
          .append(field.foryFieldId)
          .append(", ")
          .append(field.nullable)
          .append(", ")
          .append(field.trackingRef)
          .append(", ")
          .append(field.hasTrackingRefMetadata)
          .append(", ForyField.Dynamic.")
          .append(field.dynamic)
          .append(", ")
          .append(field.arrayType)
          .append("));\n");
    }
    builder.append("    return Collections.unmodifiableList(descriptors);\n");
    builder.append("  }\n\n");
    builder.append("  @Override\n");
    builder.append("  public List<Descriptor> getGeneratedDescriptors() {\n");
    builder.append("    return DESCRIPTORS;\n");
    builder.append("  }\n\n");
  }

  private void writeConstructors() {
    builder.append("  public ").append(struct.serializerName).append("() {\n");
    builder.append("    super();\n");
    builder.append("    this.allFields = null;\n");
    builder.append("    this.allFieldIds = null;\n");
    builder.append("    this.fieldsById = null;\n");
    builder.append("    this.classVersionHash = 0;\n");
    builder.append("    this.sameSchemaCompatible = false;\n");
    builder.append("  }\n\n");
    builder
        .append("  public ")
        .append(struct.serializerName)
        .append("(TypeResolver typeResolver, Class<?> type) {\n");
    builder.append("    super(typeResolver, type);\n");
    writeConstructorBody("buildFieldGroups(DESCRIPTORS)", "false");
    builder.append("  }\n\n");
    builder
        .append("  public ")
        .append(struct.serializerName)
        .append("(TypeResolver typeResolver, Class<?> type, TypeDef typeDef) {\n");
    builder.append("    super(typeResolver, type, typeDef, DESCRIPTORS);\n");
    writeConstructorBody(
        "buildLocalFieldGroups(DESCRIPTORS)",
        "typeDef != null && !HAS_NESTED_COMPATIBLE_STRUCT_FIELDS && typeDef.getId() == TypeDef.buildTypeDef(typeResolver, type).getId()");
    builder.append("  }\n\n");
  }

  private void writeConstructorBody(String fieldGroupsExpression, String sameSchemaExpression) {
    builder.append("    FieldGroups fieldGroups = ").append(fieldGroupsExpression).append(";\n");
    builder.append("    this.allFields = fieldGroups.allFields;\n");
    builder.append("    this.allFieldIds = localFieldIds(allFields, DESCRIPTORS);\n");
    builder.append("    this.fieldsById = new SerializationFieldInfo[DESCRIPTORS.size()];\n");
    builder.append("    SerializationFieldInfo[] allFields = fieldGroups.allFields;\n");
    builder.append("    int[] allFieldIds = localFieldIds(allFields, DESCRIPTORS);\n");
    builder.append("    for (int i = 0; i < allFields.length; i++) {\n");
    builder.append("      this.fieldsById[allFieldIds[i]] = allFields[i];\n");
    builder.append("    }\n");
    builder.append(
        "    this.classVersionHash = typeResolver.checkClassVersion() ? computeClassVersionHash(DESCRIPTORS) : 0;\n");
    builder.append("    this.sameSchemaCompatible = ").append(sameSchemaExpression).append(";\n");
  }

  private void writeSerializerMethods() {
    builder.append("  @Override\n");
    builder
        .append("  public void write(WriteContext writeContext, ")
        .append(struct.typeName)
        .append(" value) {\n");
    builder.append("    MemoryBuffer buffer = writeContext.getBuffer();\n");
    builder.append("    if (typeResolver.checkClassVersion()) {\n");
    builder.append("      buffer.writeInt32(classVersionHash);\n");
    builder.append("    }\n");
    builder.append("    writeFields(writeContext, value);\n");
    builder.append("  }\n\n");
    builder.append("  @Override\n");
    builder
        .append("  public ")
        .append(struct.typeName)
        .append(" read(ReadContext readContext) {\n");
    builder.append("    if (typeDef != null) {\n");
    builder.append(
        "      return sameSchemaCompatible ? readSchemaConsistent(readContext) : readCompatible(readContext);\n");
    builder.append("    }\n");
    builder.append("    return readSchemaConsistent(readContext);\n");
    builder.append("  }\n\n");
  }

  private void writeSchemaConsistentRead() {
    builder
        .append("  private ")
        .append(struct.typeName)
        .append(" readSchemaConsistent(ReadContext readContext) {\n");
    builder.append("    MemoryBuffer buffer = readContext.getBuffer();\n");
    builder.append("    if (typeResolver.checkClassVersion()) {\n");
    builder.append("      checkClassVersion(buffer.readInt32(), classVersionHash);\n");
    builder.append("    }\n");
    if (struct.record) {
      for (SourceField field : struct.fields) {
        builder
            .append("    ")
            .append(field.erasedType)
            .append(" field")
            .append(field.id)
            .append(" = ")
            .append(field.defaultValue())
            .append(";\n");
      }
      builder.append("    Object[] values = new Object[DESCRIPTORS.size()];\n");
      builder.append("    readRecordFields(readContext, values);\n");
      for (SourceField field : struct.fields) {
        builder
            .append("    field")
            .append(field.id)
            .append(" = ")
            .append(field.castExpression("values[" + field.id + "]"))
            .append(";\n");
      }
      builder.append("    return new ").append(struct.typeName).append("(");
      appendRecordConstructorArguments("field");
      builder.append(");\n");
    } else {
      builder.append("    ").append(struct.typeName).append(" value = newBean();\n");
      builder.append("    readContext.reference(value);\n");
      builder.append("    readFields(readContext, value);\n");
      builder.append("    return value;\n");
    }
    builder.append("  }\n\n");
  }

  private void writeWriteGroups() {
    writeWriteGroup("", "allFields", "allFieldIds", "writeFieldValue");
  }

  private void writeWriteGroup(
      String groupName, String fieldsName, String idsName, String helperName) {
    builder
        .append("  private void write")
        .append(groupName)
        .append("Fields(WriteContext writeContext, ")
        .append(struct.typeName)
        .append(" value) {\n");
    if (hasDirectWriteField()) {
      builder.append("    MemoryBuffer buffer = writeContext.getBuffer();\n");
    }
    builder.append("    for (int i = 0; i < ").append(fieldsName).append(".length; i++) {\n");
    builder.append("      SerializationFieldInfo fieldInfo = ").append(fieldsName).append("[i];\n");
    builder.append("      switch (").append(idsName).append("[i]) {\n");
    for (SourceField field : struct.fields) {
      builder.append("        case ").append(field.id).append(":\n");
      appendDebugWrite("before", "fieldInfo", 10);
      if (canEmitDirectWriteField(field)) {
        appendDirectWrite(field);
      } else {
        builder
            .append("          ")
            .append(helperName)
            .append("(writeContext, fieldInfo, ")
            .append(field.readExpression("value"))
            .append(");\n");
      }
      appendDebugWrite("after", "fieldInfo", 10);
      builder.append("          break;\n");
    }
    builder.append("        default:\n");
    builder
        .append("          throw new IllegalStateException(\"Unknown generated field id \" + ")
        .append(idsName)
        .append("[i]);\n");
    builder.append("      }\n");
    builder.append("    }\n");
    builder.append("  }\n\n");
  }

  private void writeReadGroups() {
    if (struct.record) {
      writeReadRecordGroup("", "allFields", "allFieldIds", "readFieldValue");
    } else {
      writeReadBeanGroup("", "allFields", "allFieldIds", "readFieldValue");
    }
  }

  private void writeReadBeanGroup(
      String groupName, String fieldsName, String idsName, String helperName) {
    builder
        .append("  private void read")
        .append(groupName)
        .append("Fields(ReadContext readContext, ")
        .append(struct.typeName)
        .append(" value) {\n");
    if (hasDirectReadField()) {
      builder.append("    MemoryBuffer buffer = readContext.getBuffer();\n");
    }
    builder.append("    for (int i = 0; i < ").append(fieldsName).append(".length; i++) {\n");
    builder.append("      SerializationFieldInfo fieldInfo = ").append(fieldsName).append("[i];\n");
    appendDebugRead("before", "fieldInfo", 6);
    if (!hasDirectReadField()) {
      builder
          .append("      Object fieldValue = ")
          .append(helperName)
          .append("(readContext, fieldInfo);\n");
    }
    appendDebugRead("after", "fieldInfo", 6);
    builder.append("      switch (").append(idsName).append("[i]) {\n");
    for (SourceField field : struct.fields) {
      builder.append("        case ").append(field.id).append(":\n");
      if (canEmitDirectReadField(field)) {
        appendDirectRead(field);
      } else {
        String fieldValueName = "fieldValue" + field.id;
        if (hasDirectReadField()) {
          builder
              .append("          Object ")
              .append(fieldValueName)
              .append(" = ")
              .append(helperName)
              .append("(readContext, fieldInfo);\n");
        } else {
          fieldValueName = "fieldValue";
        }
        builder
            .append("          ")
            .append(field.writeStatement("value", field.castExpression(fieldValueName)))
            .append("\n");
      }
      builder.append("          break;\n");
    }
    builder.append("        default:\n");
    builder
        .append("          throw new IllegalStateException(\"Unknown generated field id \" + ")
        .append(idsName)
        .append("[i]);\n");
    builder.append("      }\n");
    builder.append("    }\n");
    builder.append("  }\n\n");
  }

  private boolean hasDirectWriteField() {
    for (SourceField field : struct.fields) {
      if (canEmitDirectWriteField(field)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasDirectReadField() {
    for (SourceField field : struct.fields) {
      if (canEmitDirectReadField(field)) {
        return true;
      }
    }
    return false;
  }

  private boolean canEmitDirectWriteField(SourceField field) {
    if (canEmitDirectStringField(field)) {
      return true;
    }
    if (canEmitDirectArrayField(field)) {
      return true;
    }
    return field.typeNode.primitive && primitiveWriteCases(field) != null;
  }

  private boolean canEmitDirectReadField(SourceField field) {
    if (canEmitDirectStringField(field)) {
      return true;
    }
    if (canEmitDirectArrayField(field)) {
      return true;
    }
    return field.typeNode.primitive
        && (exactPrimitiveReadExpression(field) != null || primitiveReadCases(field) != null);
  }

  private boolean canEmitDirectStringField(SourceField field) {
    return field.erasedType.equals("java.lang.String") && !field.nullable && !field.trackingRef;
  }

  private boolean canEmitDirectArrayField(SourceField field) {
    SourceTypeNode componentType = field.typeNode.componentType;
    return field.arrayType
        && componentType != null
        && componentType.primitive
        && !field.nullable
        && !field.trackingRef;
  }

  private void appendDirectWrite(SourceField field) {
    if (canEmitDirectStringField(field)) {
      builder
          .append("          writeContext.writeString(")
          .append(field.readExpression("value"))
          .append(");\n");
      return;
    }
    if (canEmitDirectArrayField(field)) {
      builder
          .append("          fieldInfo.serializer.write(writeContext, ")
          .append(field.readExpression("value"))
          .append(");\n");
      return;
    }
    String exactWrite = exactPrimitiveWriteStatement(field, field.readExpression("value"));
    if (exactWrite != null) {
      builder.append("          ").append(exactWrite).append("\n");
      return;
    }
    builder.append("          switch (fieldInfo.dispatchId) {\n");
    String[][] cases = primitiveWriteCases(field);
    for (String[] writeCase : cases) {
      builder.append("            case DispatchId.").append(writeCase[0]).append(":\n");
      builder
          .append("              buffer.")
          .append(writeCase[1])
          .append("(")
          .append(writeCase[2].replace("$value", field.readExpression("value")))
          .append(");\n");
      builder.append("              break;\n");
    }
    builder.append("            default:\n");
    builder
        .append("              writeBuildInFieldValue(writeContext, fieldInfo, ")
        .append(field.readExpression("value"))
        .append(");\n");
    builder.append("          }\n");
  }

  private void appendDirectRead(SourceField field) {
    if (canEmitDirectStringField(field)) {
      builder
          .append("          ")
          .append(field.writeStatement("value", "readContext.readString()"))
          .append("\n");
      return;
    }
    if (canEmitDirectArrayField(field)) {
      builder.append("          readContext.preserveRefId(-1);\n");
      builder
          .append("          ")
          .append(
              field.writeStatement(
                  "value", "(" + field.erasedType + ") readContext.readNonRef(fieldInfo.typeInfo)"))
          .append("\n");
      return;
    }
    String exactRead = exactPrimitiveReadExpression(field);
    if (exactRead == null) {
      appendPrimitiveReadSwitch(field);
      return;
    }
    builder.append("          ").append(field.writeStatement("value", exactRead)).append("\n");
  }

  private void appendPrimitiveReadSwitch(SourceField field) {
    builder.append("          switch (fieldInfo.dispatchId) {\n");
    String[][] cases = primitiveReadCases(field);
    for (String[] readCase : cases) {
      builder.append("            case DispatchId.").append(readCase[0]).append(":\n");
      builder
          .append("              ")
          .append(field.writeStatement("value", readCase[1]))
          .append("\n");
      builder.append("              break;\n");
    }
    builder.append("            default:\n");
    builder
        .append("              Object fieldValue")
        .append(field.id)
        .append(" = readBuildInFieldValue(readContext, fieldInfo);\n");
    builder
        .append("              ")
        .append(field.writeStatement("value", field.castExpression("fieldValue" + field.id)))
        .append("\n");
    builder.append("          }\n");
  }

  private String[][] primitiveWriteCases(SourceField field) {
    switch (field.erasedType) {
      case "boolean":
        return new String[][] {{"BOOL", "writeBoolean", "$value"}};
      case "byte":
        return new String[][] {{"INT8", "writeByte", "$value"}};
      case "char":
        return new String[][] {{"CHAR", "writeChar", "$value"}};
      case "short":
        return new String[][] {{"INT16", "writeInt16", "$value"}};
      case "int":
        return new String[][] {
          {"INT32", "writeInt32", "$value"},
          {"VARINT32", "writeVarInt32", "$value"},
          {"UINT8", "writeByte", "$value"},
          {"UINT16", "writeInt16", "(short) $value"},
          {"UINT32", "writeInt32", "$value"},
          {"VAR_UINT32", "writeVarUInt32", "$value"}
        };
      case "long":
        return new String[][] {
          {"INT64", "writeInt64", "$value"},
          {"UINT64", "writeInt64", "$value"},
          {"VARINT64", "writeVarInt64", "$value"},
          {"TAGGED_INT64", "writeTaggedInt64", "$value"},
          {"VAR_UINT64", "writeVarUInt64", "$value"},
          {"TAGGED_UINT64", "writeTaggedUInt64", "$value"}
        };
      case "float":
        return new String[][] {{"FLOAT32", "writeFloat32", "$value"}};
      case "double":
        return new String[][] {{"FLOAT64", "writeFloat64", "$value"}};
      default:
        return null;
    }
  }

  private String[][] primitiveReadCases(SourceField field) {
    switch (field.erasedType) {
      case "boolean":
        return new String[][] {{"BOOL", "buffer.readBoolean()"}};
      case "byte":
        return new String[][] {{"INT8", "buffer.readByte()"}};
      case "char":
        return new String[][] {{"CHAR", "buffer.readChar()"}};
      case "short":
        return new String[][] {{"INT16", "buffer.readInt16()"}};
      case "int":
        return new String[][] {
          {"INT32", "buffer.readInt32()"},
          {"VARINT32", "buffer.readVarInt32()"},
          {"UINT8", "buffer.readByte() & 0xFF"},
          {"UINT16", "buffer.readInt16() & 0xFFFF"},
          {"UINT32", "buffer.readInt32()"},
          {"VAR_UINT32", "buffer.readVarUInt32()"}
        };
      case "long":
        return new String[][] {
          {"INT64", "buffer.readInt64()"},
          {"UINT64", "buffer.readInt64()"},
          {"VARINT64", "buffer.readVarInt64()"},
          {"TAGGED_INT64", "buffer.readTaggedInt64()"},
          {"VAR_UINT64", "buffer.readVarUInt64()"},
          {"TAGGED_UINT64", "buffer.readTaggedUInt64()"}
        };
      case "float":
        return new String[][] {{"FLOAT32", "buffer.readFloat32()"}};
      case "double":
        return new String[][] {{"FLOAT64", "buffer.readFloat64()"}};
      default:
        return null;
    }
  }

  private String exactPrimitiveWriteStatement(SourceField field, String valueExpression) {
    String typeId = exactPrimitiveTypeId(field);
    if (typeId == null) {
      return null;
    }
    switch (typeId) {
      case "BOOL":
        return "buffer.writeBoolean(" + valueExpression + ");";
      case "INT8":
        return "buffer.writeByte(" + valueExpression + ");";
      case "INT16":
        return "buffer.writeInt16(" + valueExpression + ");";
      case "INT32":
        return "buffer.writeInt32(" + valueExpression + ");";
      case "VARINT32":
        return "buffer.writeVarInt32(" + valueExpression + ");";
      case "UINT8":
        return unsignedRangeCheck(valueExpression, "255")
            + " buffer.writeByte("
            + valueExpression
            + ");";
      case "UINT16":
        return unsignedRangeCheck(valueExpression, "65535")
            + " buffer.writeInt16((short) "
            + valueExpression
            + ");";
      case "UINT32":
        return unsignedInt32RangeCheck(valueExpression)
            + " buffer.writeInt32((int) "
            + valueExpression
            + ");";
      case "VAR_UINT32":
        return unsignedInt32RangeCheck(valueExpression)
            + " buffer.writeVarUInt32((int) "
            + valueExpression
            + ");";
      case "INT64":
      case "UINT64":
        return "buffer.writeInt64(" + valueExpression + ");";
      case "VARINT64":
        return "buffer.writeVarInt64(" + valueExpression + ");";
      case "TAGGED_INT64":
        return "buffer.writeTaggedInt64(" + valueExpression + ");";
      case "VAR_UINT64":
        return "buffer.writeVarUInt64(" + valueExpression + ");";
      case "TAGGED_UINT64":
        return "buffer.writeTaggedUInt64(" + valueExpression + ");";
      case "FLOAT32":
        return "buffer.writeFloat32(" + valueExpression + ");";
      case "FLOAT64":
        return "buffer.writeFloat64(" + valueExpression + ");";
      default:
        return null;
    }
  }

  private String unsignedRangeCheck(String valueExpression, String maxValue) {
    return "if (("
        + valueExpression
        + ") < 0 || ("
        + valueExpression
        + ") > "
        + maxValue
        + ") { throw new IllegalArgumentException(\"Unsigned value out of range\"); }";
  }

  private String unsignedInt32RangeCheck(String valueExpression) {
    return "if ((("
        + valueExpression
        + ") & 0xffffffff00000000L) != 0) { throw new IllegalArgumentException(\"Unsigned value out of range\"); }";
  }

  private String exactPrimitiveReadExpression(SourceField field) {
    String typeId = exactPrimitiveTypeId(field);
    if (typeId == null) {
      return null;
    }
    switch (typeId) {
      case "BOOL":
        return "buffer.readBoolean()";
      case "INT8":
        return "buffer.readByte()";
      case "INT16":
        return "buffer.readInt16()";
      case "INT32":
        return "buffer.readInt32()";
      case "VARINT32":
        return "buffer.readVarInt32()";
      case "UINT8":
        return "buffer.readByte() & 0xFF";
      case "UINT16":
        return "buffer.readInt16() & 0xFFFF";
      case "UINT32":
        return field.erasedType.equals("long")
            ? "Integer.toUnsignedLong(buffer.readInt32())"
            : "buffer.readInt32()";
      case "VAR_UINT32":
        return field.erasedType.equals("long")
            ? "Integer.toUnsignedLong(buffer.readVarUInt32())"
            : "buffer.readVarUInt32()";
      case "INT64":
      case "UINT64":
        return "buffer.readInt64()";
      case "VARINT64":
        return "buffer.readVarInt64()";
      case "TAGGED_INT64":
        return "buffer.readTaggedInt64()";
      case "VAR_UINT64":
        return "buffer.readVarUInt64()";
      case "TAGGED_UINT64":
        return "buffer.readTaggedUInt64()";
      case "FLOAT32":
        return "buffer.readFloat32()";
      case "FLOAT64":
        return "buffer.readFloat64()";
      default:
        return null;
    }
  }

  private String exactPrimitiveTypeId(SourceField field) {
    String meta = field.typeNode.typeExtMeta;
    if (meta == null) {
      return null;
    }
    String prefix = "meta(Types.";
    int start = meta.indexOf(prefix);
    if (start < 0) {
      return null;
    }
    int end = meta.indexOf(',', start + prefix.length());
    if (end < 0) {
      return null;
    }
    return meta.substring(start + prefix.length(), end);
  }

  private void writeReadRecordGroup(
      String groupName, String fieldsName, String idsName, String helperName) {
    builder
        .append("  private void read")
        .append(groupName)
        .append("RecordFields(ReadContext readContext, Object[] values) {\n");
    builder.append("    for (int i = 0; i < ").append(fieldsName).append(".length; i++) {\n");
    builder.append("      SerializationFieldInfo fieldInfo = ").append(fieldsName).append("[i];\n");
    appendDebugRead("before", "fieldInfo", 6);
    builder
        .append("      values[")
        .append(idsName)
        .append("[i]] = ")
        .append(helperName)
        .append("(readContext, fieldInfo);\n");
    appendDebugRead("after", "fieldInfo", 6);
    builder.append("    }\n");
    builder.append("  }\n\n");
  }

  private void writeCompatibleRead() {
    builder.append("  @Override\n");
    builder
        .append("  public ")
        .append(struct.typeName)
        .append(" readCompatible(ReadContext readContext) {\n");
    builder.append("    if (sameSchemaCompatible) {\n");
    builder.append("      return readSchemaConsistent(readContext);\n");
    builder.append("    }\n");
    if (struct.record) {
      builder.append("    Object[] values = new Object[DESCRIPTORS.size()];\n");
      for (SourceField field : struct.fields) {
        builder
            .append("    values[")
            .append(field.id)
            .append("] = ")
            .append(field.defaultValue())
            .append(";\n");
      }
      builder.append("    for (int i = 0; i < remoteFields.size(); i++) {\n");
      builder.append("      RemoteFieldInfo remoteField = remoteFields.get(i);\n");
      builder.append("      readCompatibleRecordField(readContext, values, remoteField);\n");
      builder.append("    }\n");
      for (SourceField field : struct.fields) {
        builder
            .append("    ")
            .append(field.erasedType)
            .append(" field")
            .append(field.id)
            .append(" = ")
            .append(field.castExpression("values[" + field.id + "]"))
            .append(";\n");
      }
      builder.append("    return new ").append(struct.typeName).append("(");
      appendRecordConstructorArguments("field");
      builder.append(");\n");
    } else {
      builder.append("    ").append(struct.typeName).append(" value = newBean();\n");
      builder.append("    readContext.reference(value);\n");
      builder.append("    for (int i = 0; i < remoteFields.size(); i++) {\n");
      builder.append("      RemoteFieldInfo remoteField = remoteFields.get(i);\n");
      builder.append("      readCompatibleField(readContext, value, remoteField);\n");
      builder.append("    }\n");
      builder.append("    return value;\n");
    }
    builder.append("  }\n\n");
    writeCompatibleDispatchMethods();
  }

  private void writeCompatibleDispatchMethods() {
    int groupCount = (struct.fields.size() + DISPATCH_GROUP_SIZE - 1) / DISPATCH_GROUP_SIZE;
    if (struct.record) {
      writeCompatibleDispatchRouter("readCompatibleRecordField", true, groupCount);
      for (int group = 0; group < groupCount; group++) {
        writeCompatibleRecordDispatchGroup(group);
      }
    } else {
      writeCompatibleDispatchRouter("readCompatibleField", false, groupCount);
      for (int group = 0; group < groupCount; group++) {
        writeCompatibleBeanDispatchGroup(group);
      }
    }
  }

  private void writeCompatibleDispatchRouter(String methodName, boolean record, int groupCount) {
    builder.append("  private void ").append(methodName).append("(");
    appendCompatibleDispatchParameters(record);
    builder.append(") {\n");
    for (int group = 0; group < groupCount; group++) {
      int upperBound = Math.min(struct.fields.size(), (group + 1) * DISPATCH_GROUP_SIZE);
      if (group == 0) {
        builder
            .append("    if (remoteField.matchedId >= 0 && remoteField.matchedId < ")
            .append(upperBound)
            .append(") {\n");
      } else {
        builder.append("    if (remoteField.matchedId < ").append(upperBound).append(") {\n");
      }
      builder.append("      ").append(methodName).append(group).append("(");
      appendCompatibleDispatchArguments(record);
      builder.append(");\n");
      builder.append("      return;\n");
      builder.append("    }\n");
    }
    appendDebugRemoteRead("before skip", "remoteField", 4);
    builder.append("    skipField(readContext, remoteField);\n");
    appendDebugRemoteRead("after skip", "remoteField", 4);
    builder.append("  }\n\n");
  }

  private void writeCompatibleBeanDispatchGroup(int group) {
    int start = group * DISPATCH_GROUP_SIZE;
    int end = Math.min(struct.fields.size(), start + DISPATCH_GROUP_SIZE);
    builder.append("  private void readCompatibleField").append(group).append("(");
    appendCompatibleDispatchParameters(false);
    builder.append(") {\n");
    builder.append("    switch (remoteField.matchedId) {\n");
    for (int i = start; i < end; i++) {
      SourceField field = struct.fields.get(i);
      builder.append("      case ").append(field.id).append(":\n");
      appendDebugRemoteRead("before read", "remoteField", 8);
      builder
          .append("        if (canReadGeneratedField(remoteField, fieldsById[")
          .append(field.id)
          .append("])) {\n");
      builder
          .append(
              "          Object fieldValue = readCompatibleFieldValue(readContext, remoteField, fieldsById[")
          .append(field.id)
          .append("]);\n");
      appendDebugRemoteRead("after read", "remoteField", 10);
      builder
          .append("          ")
          .append(field.writeStatement("value", field.castExpression("fieldValue")))
          .append("\n");
      builder.append("        } else {\n");
      appendDebugRemoteRead("before skip", "remoteField", 10);
      builder.append("          skipField(readContext, remoteField);\n");
      appendDebugRemoteRead("after skip", "remoteField", 10);
      builder.append("        }\n");
      builder.append("        return;\n");
    }
    builder.append("      default:\n");
    appendDebugRemoteRead("before skip", "remoteField", 8);
    builder.append("        skipField(readContext, remoteField);\n");
    appendDebugRemoteRead("after skip", "remoteField", 8);
    builder.append("    }\n");
    builder.append("  }\n\n");
  }

  private void writeCompatibleRecordDispatchGroup(int group) {
    int start = group * DISPATCH_GROUP_SIZE;
    int end = Math.min(struct.fields.size(), start + DISPATCH_GROUP_SIZE);
    builder.append("  private void readCompatibleRecordField").append(group).append("(");
    appendCompatibleDispatchParameters(true);
    builder.append(") {\n");
    builder.append("    switch (remoteField.matchedId) {\n");
    for (int i = start; i < end; i++) {
      SourceField field = struct.fields.get(i);
      builder.append("      case ").append(field.id).append(":\n");
      appendDebugRemoteRead("before read", "remoteField", 8);
      builder
          .append("        if (canReadGeneratedField(remoteField, fieldsById[")
          .append(field.id)
          .append("])) {\n");
      builder
          .append("          values[")
          .append(field.id)
          .append("] = readCompatibleFieldValue(readContext, remoteField, fieldsById[")
          .append(field.id)
          .append("]);\n");
      appendDebugRemoteRead("after read", "remoteField", 10);
      builder.append("        } else {\n");
      appendDebugRemoteRead("before skip", "remoteField", 10);
      builder.append("          skipField(readContext, remoteField);\n");
      appendDebugRemoteRead("after skip", "remoteField", 10);
      builder.append("        }\n");
      builder.append("        return;\n");
    }
    builder.append("      default:\n");
    appendDebugRemoteRead("before skip", "remoteField", 8);
    builder.append("        skipField(readContext, remoteField);\n");
    appendDebugRemoteRead("after skip", "remoteField", 8);
    builder.append("    }\n");
    builder.append("  }\n\n");
  }

  private void appendCompatibleDispatchParameters(boolean record) {
    builder.append("ReadContext readContext, ");
    if (record) {
      builder.append("Object[] values, ");
    } else {
      builder.append(struct.typeName).append(" value, ");
    }
    builder.append("RemoteFieldInfo remoteField");
  }

  private void appendCompatibleDispatchArguments(boolean record) {
    builder.append("readContext, ");
    if (record) {
      builder.append("values, ");
    } else {
      builder.append("value, ");
    }
    builder.append("remoteField");
  }

  private void appendDebugWrite(String stage, String fieldInfoName, int indent) {
    if (!struct.debug) {
      return;
    }
    appendIndent(indent);
    builder
        .append("if (FORY_STRUCT_DEBUG) { debugWriteField(\"")
        .append(stage)
        .append("\", ")
        .append(fieldInfoName)
        .append(", writeContext); }\n");
  }

  private void appendDebugRead(String stage, String fieldInfoName, int indent) {
    if (!struct.debug) {
      return;
    }
    appendIndent(indent);
    builder
        .append("if (FORY_STRUCT_DEBUG) { debugReadField(\"")
        .append(stage)
        .append("\", ")
        .append(fieldInfoName)
        .append(", readContext); }\n");
  }

  private void appendDebugRemoteRead(String stage, String remoteFieldName, int indent) {
    if (!struct.debug) {
      return;
    }
    appendIndent(indent);
    builder
        .append("if (FORY_STRUCT_DEBUG) { debugRemoteReadField(\"")
        .append(stage)
        .append("\", ")
        .append(remoteFieldName)
        .append(", readContext); }\n");
  }

  private void appendIndent(int spaces) {
    for (int i = 0; i < spaces; i++) {
      builder.append(' ');
    }
  }

  private void writeCopy() {
    builder.append("  @Override\n");
    builder
        .append("  public ")
        .append(struct.typeName)
        .append(" copy(CopyContext copyContext, ")
        .append(struct.typeName)
        .append(" value) {\n");
    builder.append("    if (immutable) {\n");
    builder.append("      return value;\n");
    builder.append("    }\n");
    if (struct.record) {
      for (SourceField field : struct.fields) {
        builder
            .append("    ")
            .append(field.erasedType)
            .append(" field")
            .append(field.id)
            .append(" = ")
            .append(
                field.castExpression(
                    "copyFieldValue(copyContext, "
                        + field.readExpression("value")
                        + ", fieldsById["
                        + field.id
                        + "])"))
            .append(";\n");
      }
      builder
          .append("    ")
          .append(struct.typeName)
          .append(" copied = new ")
          .append(struct.typeName)
          .append("(");
      appendRecordConstructorArguments("field");
      builder.append(");\n");
      builder.append("    copyContext.reference(value, copied);\n");
      builder.append("    return copied;\n");
    } else {
      builder.append("    ").append(struct.typeName).append(" copied = newBean();\n");
      builder.append("    copyContext.reference(value, copied);\n");
      for (SourceField field : struct.fields) {
        builder
            .append("    ")
            .append(
                field.writeStatement(
                    "copied",
                    field.castExpression(
                        "copyFieldValue(copyContext, "
                            + field.readExpression("value")
                            + ", fieldsById["
                            + field.id
                            + "])")))
            .append("\n");
      }
      builder.append("    return copied;\n");
    }
    builder.append("  }\n\n");
  }

  private void writeDescriptorHelpers() {
    builder.append(
        "  private static TypeExtMeta meta(int typeId, boolean nullable, boolean trackingRef) {\n");
    builder.append("    return TypeExtMeta.of(typeId, nullable, trackingRef);\n");
    builder.append("  }\n");
  }

  private void appendRecordConstructorArguments(String prefix) {
    for (int i = 0; i < struct.recordConstructorFields.size(); i++) {
      if (i > 0) {
        builder.append(", ");
      }
      SourceField field = struct.recordConstructorFields.get(i);
      if (field.serialized) {
        builder.append(prefix).append(field.id);
      } else {
        builder.append(field.defaultValue());
      }
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
