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
    builder.append("import org.apache.fory.reflect.FieldAccessor;\n");
    if (!struct.record) {
      builder.append("import org.apache.fory.reflect.ObjectInstantiator;\n");
    }
    builder.append("import org.apache.fory.resolver.TypeResolver;\n");
    builder.append("import org.apache.fory.serializer.FieldGroups;\n");
    builder.append("import org.apache.fory.serializer.FieldGroups.SerializationFieldInfo;\n");
    builder.append("import org.apache.fory.serializer.StaticGeneratedStructSerializer;\n");
    builder.append("import org.apache.fory.serializer.converter.FieldConverters;\n");
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
    if (!struct.record) {
      builder.append("  private final ObjectInstantiator generatedObjectInstantiator;\n");
    }
    for (SourceField field : struct.fields) {
      if (field.usesFieldAccessor()) {
        builder
            .append("  private final FieldAccessor ")
            .append(field.fieldAccessorName())
            .append(";\n");
      }
    }
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
    if (!struct.record) {
      builder.append("    this.generatedObjectInstantiator = null;\n");
    }
    for (SourceField field : struct.fields) {
      if (field.usesFieldAccessor()) {
        builder.append("    this.").append(field.fieldAccessorName()).append(" = null;\n");
      }
    }
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
    if (!struct.record) {
      builder.append(
          "    this.generatedObjectInstantiator = typeResolver.getObjectInstantiator(type);\n");
    }
    builder.append("    SerializationFieldInfo[] allFields = fieldGroups.allFields;\n");
    builder.append("    int[] allFieldIds = localFieldIds(allFields, DESCRIPTORS);\n");
    builder.append("    for (int i = 0; i < allFields.length; i++) {\n");
    builder.append("      this.fieldsById[allFieldIds[i]] = allFields[i];\n");
    builder.append("    }\n");
    for (SourceField field : struct.fields) {
      if (field.usesFieldAccessor()) {
        builder
            .append("    this.")
            .append(field.fieldAccessorName())
            .append(" = FieldAccessor.createAccessor(declaredField(type, \"")
            .append(escape(field.declaringClass))
            .append("\", \"")
            .append(escape(field.name))
            .append("\"));\n");
      }
    }
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
    builder.append("    reserveObjectGraphMemory(readContext);\n");
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
      appendSchemaConsistentRecordLoop();
      appendRecordConstruction("record", "field", 4);
      builder.append("    return record;\n");
    } else {
      builder
          .append("    ")
          .append(struct.typeName)
          .append(" value = ")
          .append(newGeneratedBeanExpression())
          .append(";\n");
      builder.append("    readContext.reference(value);\n");
      builder.append("    readFields(readContext, value);\n");
      builder.append("    return value;\n");
    }
    builder.append("  }\n\n");
  }

  private void writeWriteGroups() {
    writeWriteGroup("", "allFields", "allFieldIds", "writeFieldValue");
  }

  private void appendSchemaConsistentRecordLoop() {
    builder.append("    for (int i = 0; i < allFields.length; i++) {\n");
    builder.append("      SerializationFieldInfo fieldInfo = allFields[i];\n");
    builder.append("      switch (allFieldIds[i]) {\n");
    for (SourceField field : struct.fields) {
      builder.append("        case ").append(field.id).append(":\n");
      appendDebugRead("before", "fieldInfo", 10);
      if (canEmitDirectReadField(field)) {
        appendDirectReadLocal(field, "field" + field.id, "fieldInfo", 10);
      } else {
        builder
            .append("          Object fieldValue")
            .append(field.id)
            .append(" = readFieldValue(readContext, fieldInfo);\n");
        builder
            .append("          field")
            .append(field.id)
            .append(" = ")
            .append(field.castExpression("fieldValue" + field.id))
            .append(";\n");
      }
      appendDebugRead("after", "fieldInfo", 10);
      builder.append("          break;\n");
    }
    builder.append("        default:\n");
    builder.append(
        "          throw new IllegalStateException(\"Unknown generated field id \" + allFieldIds[i]);\n");
    builder.append("      }\n");
    builder.append("    }\n");
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
    if (!struct.record) {
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
    if (field.readAccessKind == SourceField.AccessKind.ACCESSOR) {
      return false;
    }
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
    appendDirectReadTarget(field, "value", "fieldInfo", 10, false);
  }

  private void appendDirectReadLocal(
      SourceField field, String localName, String fieldInfoName, int indent) {
    appendDirectReadTarget(field, localName, fieldInfoName, indent, true);
  }

  private void appendDirectReadTarget(
      SourceField field, String target, String fieldInfoName, int indent, boolean localTarget) {
    if (canEmitDirectStringField(field)) {
      appendIndented(indent, assignment(field, target, "readContext.readString()", localTarget));
      return;
    }
    if (canEmitDirectArrayField(field)) {
      appendIndented(indent, "readContext.preserveRefId(-1);");
      appendIndented(
          indent,
          assignment(
              field,
              target,
              "(" + field.erasedType + ") readContext.readNonRef(" + fieldInfoName + ".typeInfo)",
              localTarget));
      return;
    }
    String exactRead = exactPrimitiveReadExpression(field);
    if (exactRead == null) {
      appendPrimitiveReadSwitch(field, target, fieldInfoName, indent, localTarget);
      return;
    }
    appendIndented(indent, assignment(field, target, exactRead, localTarget));
  }

  private void appendPrimitiveReadSwitch(SourceField field) {
    appendPrimitiveReadSwitch(field, "value", "fieldInfo", 10, false);
  }

  private void appendPrimitiveReadSwitch(
      SourceField field, String target, String fieldInfoName, int indent, boolean localTarget) {
    appendIndented(indent, "switch (" + fieldInfoName + ".dispatchId) {");
    String[][] cases = primitiveReadCases(field);
    for (String[] readCase : cases) {
      appendIndented(indent + 2, "case DispatchId." + readCase[0] + ":");
      appendIndented(indent + 4, assignment(field, target, readCase[1], localTarget));
      appendIndented(indent + 4, "break;");
    }
    appendIndented(indent + 2, "default:");
    appendIndented(
        indent + 4,
        "Object fieldValue"
            + field.id
            + " = readBuildInFieldValue(readContext, "
            + fieldInfoName
            + ");");
    appendIndented(
        indent + 4,
        assignment(field, target, field.castExpression("fieldValue" + field.id), localTarget));
    appendIndented(indent, "}");
  }

  private String assignment(
      SourceField field, String target, String valueExpression, boolean localTarget) {
    if (localTarget) {
      return target + " = " + valueExpression + ";";
    }
    return field.writeStatement(target, valueExpression);
  }

  private void appendIndented(int spaces, String code) {
    appendIndent(spaces);
    builder.append(code).append("\n");
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

  private void writeCompatibleRead() {
    builder.append("  @Override\n");
    builder
        .append("  public ")
        .append(struct.typeName)
        .append(" readCompatible(ReadContext readContext) {\n");
    builder.append("    if (sameSchemaCompatible) {\n");
    builder.append("      return readSchemaConsistent(readContext);\n");
    builder.append("    }\n");
    builder.append("    reserveObjectGraphMemory(readContext);\n");
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
      builder.append("    for (int i = 0; i < remoteFields.size(); i++) {\n");
      builder.append("      RemoteFieldInfo remoteField = remoteFields.get(i);\n");
      builder.append("      if (remoteField.matchedId == -1) {\n");
      appendDebugRemoteRead("before skip", "remoteField", 8);
      builder.append("        skipField(readContext, remoteField);\n");
      appendDebugRemoteRead("after skip", "remoteField", 8);
      builder.append("        continue;\n");
      builder.append("      }\n");
      appendCompatibleRecordSwitch();
      builder.append("    }\n");
      appendRecordConstruction("record", "field", 4);
      builder.append("    return record;\n");
    } else {
      builder
          .append("    ")
          .append(struct.typeName)
          .append(" value = ")
          .append(newGeneratedBeanExpression())
          .append(";\n");
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

  private void appendCompatibleRecordSwitch() {
    builder.append("      switch (remoteField.matchedId) {\n");
    for (int matchedId = 0; matchedId < struct.fields.size() * 2; matchedId++) {
      SourceField field = struct.fields.get(matchedId >> 1);
      builder.append("        case ").append(matchedId).append(": {\n");
      if ((matchedId & 1) == 0) {
        String fieldInfoName = "fieldInfo" + matchedId;
        builder
            .append("          SerializationFieldInfo ")
            .append(fieldInfoName)
            .append(" = fieldsById[")
            .append(field.id)
            .append("];\n");
        appendDebugRead("before", fieldInfoName, 10);
        if (canEmitDirectReadField(field)) {
          builder.append("          MemoryBuffer buffer = readContext.getBuffer();\n");
          appendDirectReadLocal(field, "field" + field.id, fieldInfoName, 10);
        } else {
          String fieldValueName = "fieldValue" + matchedId;
          builder
              .append("          Object ")
              .append(fieldValueName)
              .append(" = readFieldValue(readContext, ")
              .append(fieldInfoName)
              .append(");\n");
          builder
              .append("          field")
              .append(field.id)
              .append(" = ")
              .append(field.castExpression(fieldValueName))
              .append(";\n");
        }
        appendDebugRead("after", fieldInfoName, 10);
        builder.append("          break;\n");
        builder.append("        }\n");
        continue;
      }
      appendDebugRemoteRead("before read", "remoteField", 10);
      String scalarRead =
          compatibleScalarReadExpression(
              field, "remoteField.serializationFieldInfo", "fieldsById[" + field.id + "]");
      if (scalarRead != null) {
        builder
            .append("          field")
            .append(field.id)
            .append(" = ")
            .append(scalarRead)
            .append(";\n");
        appendDebugRemoteRead("after read", "remoteField", 10);
      } else {
        String fieldValueName = "fieldValue" + matchedId;
        builder
            .append("          Object ")
            .append(fieldValueName)
            .append(" = readCompatibleFieldValue(readContext, remoteField, fieldsById[")
            .append(field.id)
            .append("]);\n");
        appendDebugRemoteRead("after read", "remoteField", 10);
        builder
            .append("          field")
            .append(field.id)
            .append(" = ")
            .append(field.castExpression(fieldValueName))
            .append(";\n");
      }
      builder.append("          break;\n");
      builder.append("        }\n");
    }
    builder.append("        default:\n");
    builder.append(
        "          throw new IllegalStateException(\"Invalid compatible matched id \" + remoteField.matchedId);\n");
    builder.append("      }\n");
  }

  private void writeCompatibleDispatchMethods() {
    int caseCount = struct.fields.size() * 2;
    int groupCount = (caseCount + DISPATCH_GROUP_SIZE - 1) / DISPATCH_GROUP_SIZE;
    if (struct.record) {
      return;
    } else {
      writeCompatibleDispatchRouter("readCompatibleField", groupCount);
      for (int group = 0; group < groupCount; group++) {
        writeCompatibleBeanDispatchGroup(group);
      }
    }
  }

  private void writeCompatibleDispatchRouter(String methodName, int groupCount) {
    builder.append("  private void ").append(methodName).append("(");
    appendCompatibleDispatchParameters();
    builder.append(") {\n");
    builder.append("    if (remoteField.matchedId == -1) {\n");
    appendDebugRemoteRead("before skip", "remoteField", 6);
    builder.append("      skipField(readContext, remoteField);\n");
    appendDebugRemoteRead("after skip", "remoteField", 6);
    builder.append("      return;\n");
    builder.append("    }\n");
    for (int group = 0; group < groupCount; group++) {
      int upperBound = Math.min(struct.fields.size() * 2, (group + 1) * DISPATCH_GROUP_SIZE);
      if (group == 0) {
        builder
            .append("    if (remoteField.matchedId >= 0 && remoteField.matchedId < ")
            .append(upperBound)
            .append(") {\n");
      } else {
        builder.append("    if (remoteField.matchedId < ").append(upperBound).append(") {\n");
      }
      builder.append("      ").append(methodName).append(group).append("(");
      appendCompatibleDispatchArguments();
      builder.append(");\n");
      builder.append("      return;\n");
      builder.append("    }\n");
    }
    builder.append(
        "    throw new IllegalStateException(\"Invalid compatible matched id \" + remoteField.matchedId);\n");
    builder.append("  }\n\n");
  }

  private void writeCompatibleBeanDispatchGroup(int group) {
    int start = group * DISPATCH_GROUP_SIZE;
    int end = Math.min(struct.fields.size() * 2, start + DISPATCH_GROUP_SIZE);
    builder.append("  private void readCompatibleField").append(group).append("(");
    appendCompatibleDispatchParameters();
    builder.append(") {\n");
    builder.append("    switch (remoteField.matchedId) {\n");
    for (int matchedId = start; matchedId < end; matchedId++) {
      SourceField field = struct.fields.get(matchedId >> 1);
      builder.append("      case ").append(matchedId).append(": {\n");
      if ((matchedId & 1) == 0) {
        String fieldInfoName = "fieldInfo";
        builder
            .append("        SerializationFieldInfo ")
            .append(fieldInfoName)
            .append(" = fieldsById[")
            .append(field.id)
            .append("];\n");
        appendDebugRead("before", fieldInfoName, 8);
        if (canEmitDirectReadField(field)) {
          builder.append("        MemoryBuffer buffer = readContext.getBuffer();\n");
          appendDirectRead(field);
        } else {
          String fieldValueName = "fieldValue" + matchedId;
          builder
              .append("        Object ")
              .append(fieldValueName)
              .append(" = readFieldValue(readContext, ")
              .append(fieldInfoName)
              .append(");\n");
          builder
              .append("        ")
              .append(field.writeStatement("value", field.castExpression(fieldValueName)))
              .append("\n");
        }
        appendDebugRead("after", fieldInfoName, 8);
        builder.append("        return;\n");
        builder.append("      }\n");
        continue;
      }
      appendDebugRemoteRead("before read", "remoteField", 8);
      String scalarRead =
          compatibleScalarReadExpression(
              field, "remoteField.serializationFieldInfo", "fieldsById[" + field.id + "]");
      if (scalarRead != null) {
        builder.append("        ").append(field.writeStatement("value", scalarRead)).append("\n");
        appendDebugRemoteRead("after read", "remoteField", 8);
      } else {
        String fieldValueName = "fieldValue" + matchedId;
        builder
            .append("        Object ")
            .append(fieldValueName)
            .append(" = readCompatibleFieldValue(readContext, remoteField, fieldsById[")
            .append(field.id)
            .append("]);\n");
        appendDebugRemoteRead("after read", "remoteField", 8);
        builder
            .append("        ")
            .append(field.writeStatement("value", field.castExpression(fieldValueName)))
            .append("\n");
      }
      builder.append("        return;\n");
      builder.append("      }\n");
    }
    builder.append("      default:\n");
    builder.append(
        "        throw new IllegalStateException(\"Invalid compatible matched id \" + remoteField.matchedId);\n");
    builder.append("    }\n");
    builder.append("  }\n\n");
  }

  private void appendCompatibleDispatchParameters() {
    builder.append("ReadContext readContext, ");
    builder.append(struct.typeName).append(" value, ");
    builder.append("RemoteFieldInfo remoteField");
  }

  private void appendCompatibleDispatchArguments() {
    builder.append("readContext, ");
    builder.append("value, ");
    builder.append("remoteField");
  }

  private String compatibleScalarReadExpression(
      SourceField field, String remoteFieldInfo, String localFieldInfo) {
    String helper;
    switch (field.erasedType) {
      case "boolean":
        helper = "readBooleanTarget";
        break;
      case "java.lang.Boolean":
        helper = "readBoxedBooleanTarget";
        break;
      case "byte":
        helper = "readByteTarget";
        break;
      case "java.lang.Byte":
        helper = "readBoxedByteTarget";
        break;
      case "short":
        helper = "readShortTarget";
        break;
      case "java.lang.Short":
        helper = "readBoxedShortTarget";
        break;
      case "int":
        helper = "readIntTarget";
        break;
      case "java.lang.Integer":
        helper = "readBoxedIntTarget";
        break;
      case "long":
        helper = "readLongTarget";
        break;
      case "java.lang.Long":
        helper = "readBoxedLongTarget";
        break;
      case "float":
        helper = "readFloatTarget";
        break;
      case "java.lang.Float":
        helper = "readBoxedFloatTarget";
        break;
      case "double":
        helper = "readDoubleTarget";
        break;
      case "java.lang.Double":
        helper = "readBoxedDoubleTarget";
        break;
      case "java.lang.String":
        helper = "readStringTarget";
        break;
      case "java.math.BigDecimal":
        helper = "readDecimalTarget";
        break;
      case "org.apache.fory.type.unsigned.UInt8":
        helper = "readUInt8Target";
        break;
      case "org.apache.fory.type.unsigned.UInt16":
        helper = "readUInt16Target";
        break;
      case "org.apache.fory.type.unsigned.UInt32":
        helper = "readUInt32Target";
        break;
      case "org.apache.fory.type.unsigned.UInt64":
        helper = "readUInt64Target";
        break;
      case "org.apache.fory.type.Float16":
        helper = "readFloat16Target";
        break;
      case "org.apache.fory.type.BFloat16":
        helper = "readBFloat16Target";
        break;
      default:
        return null;
    }
    return "FieldConverters."
        + helper
        + "(readContext, "
        + remoteFieldInfo
        + ", "
        + localFieldInfo
        + ")";
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
      appendRecordConstruction("copied", "field", 4);
      builder.append("    copyContext.reference(value, copied);\n");
      builder.append("    return copied;\n");
    } else {
      builder
          .append("    ")
          .append(struct.typeName)
          .append(" copied = ")
          .append(newGeneratedBeanExpression())
          .append(";\n");
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
    builder.append(
        "  private static java.lang.reflect.Field declaredField(Class<?> type, String declaringClassName, String fieldName) {\n");
    builder.append("    try {\n");
    builder.append(
        "      return Class.forName(declaringClassName, false, type.getClassLoader()).getDeclaredField(fieldName);\n");
    builder.append("    } catch (ReflectiveOperationException e) {\n");
    builder.append("      throw new IllegalStateException(e);\n");
    builder.append("    }\n");
    builder.append("  }\n");
  }

  private void appendRecordConstruction(String variableName, String prefix, int indent) {
    appendIndent(indent);
    builder
        .append(struct.typeName)
        .append(" ")
        .append(variableName)
        .append(" = new ")
        .append(struct.typeName)
        .append("(");
    for (int i = 0; i < struct.recordConstructorFields.size(); i++) {
      if (i != 0) {
        builder.append(", ");
      }
      SourceField field = struct.recordConstructorFields.get(i);
      if (field.serialized) {
        builder.append(prefix).append(field.id);
      } else {
        builder.append(field.defaultValue());
      }
    }
    builder.append(");\n");
  }

  private String newGeneratedBeanExpression() {
    return "(" + struct.typeName + ") generatedObjectInstantiator.newInstance()";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
