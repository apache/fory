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

import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.apache.fory.json.meta.JsonMetadataFormat;
import org.apache.fory.json.meta.JsonTypeMetadata;

/** Writes one Java 8-compatible generated JSON metadata companion. */
final class JsonMetadataSourceWriter {
  private static final int SWITCH_CHUNK_SIZE = 128;

  private final JsonSourceModel model;
  private final JsonMetadataEncoder.EncodedSection[] encoded;
  private final Types types;
  private final StringBuilder builder = new StringBuilder(32768);

  JsonMetadataSourceWriter(
      ProcessingEnvironment environment,
      JsonSourceModel model,
      JsonMetadataEncoder.EncodedSection[] encoded) {
    this.model = model;
    this.encoded = encoded;
    types = environment.getTypeUtils();
  }

  String write() {
    if (!model.packageName.isEmpty()) {
      builder.append("package ").append(model.packageName).append(";\n\n");
    }
    builder.append("import org.apache.fory.json.ForyJsonException;\n");
    builder.append("import org.apache.fory.json.meta.JsonAnySetterInvoker;\n");
    builder.append("import org.apache.fory.json.meta.JsonCodecFactory;\n");
    builder.append("import org.apache.fory.json.meta.JsonCreatorInvoker;\n");
    builder.append("import org.apache.fory.json.meta.JsonFieldAccessor;\n");
    builder.append("import org.apache.fory.json.meta.JsonTypeMetadata;\n");
    builder.append("import org.apache.fory.json.meta.JsonTypeMetadataData;\n");
    builder.append("import org.apache.fory.reflect.ObjectInstantiator;\n\n");
    builder.append("@SuppressWarnings({\"unchecked\", \"rawtypes\"})\n");
    builder
        .append("public final class ")
        .append(model.metadataSimpleName)
        .append(" extends JsonTypeMetadata {\n");
    builder
        .append("  public ")
        .append(model.metadataSimpleName)
        .append("(Class<?> requestedType) {\n")
        .append("    super(requestedType, \"")
        .append(escape(model.targetBinaryName))
        .append("\", ")
        .append(JsonTypeMetadata.ABI_VERSION)
        .append(");\n  }\n\n");
    writeMetadata();
    writeTypeBootstrap();
    writeOperationBootstrap();
    writeSectionHolders();
    writeTypeChunks();
    writeOperationChunks();
    writeOperations();
    builder.append(
        "  private static ForyJsonException operationFailure(String operation, Throwable cause) {\n");
    builder.append("    if (cause instanceof Error) { throw (Error) cause; }\n");
    builder.append(
        "    return new ForyJsonException(\"Generated JSON operation failed: \" + operation, cause);\n");
    builder.append("  }\n\n");
    builder.append(
        "  private static IllegalArgumentException invalidIndex(String kind, int section, int index) {\n");
    builder.append(
        "    return new IllegalArgumentException(\"Invalid generated JSON \" + kind + \" index \" + index + \" in section \" + section);\n");
    builder.append("  }\n");
    builder.append("}\n");
    return builder.toString();
  }

  private void writeMetadata() {
    builder.append("  @Override\n  public Object metadata(int section) {\n");
    builder.append("    switch (section) {\n");
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      builder
          .append("      case ")
          .append(section)
          .append(": return Section")
          .append(section)
          .append(".DATA;\n");
    }
    builder.append("      default: throw invalidIndex(\"section\", section, -1);\n");
    builder.append("    }\n  }\n\n");
  }

  private void writeTypeBootstrap() {
    builder.append("  @Override\n  public Class<?> metadataType(int section, int index) {\n");
    builder.append("    switch (section) {\n");
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      if (hasDirectTokens(model.section(section))) {
        builder
            .append("      case ")
            .append(section)
            .append(": return type")
            .append(section)
            .append("(index);\n");
      }
    }
    builder.append("      default: throw invalidIndex(\"type\", section, index);\n");
    builder.append("    }\n  }\n\n");
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      List<JsonSourceModel.Token> direct = directTokens(model.section(section));
      if (direct.isEmpty()) {
        continue;
      }
      builder.append("  private static Class<?> type").append(section).append("(int index) {\n");
      for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < direct.size(); chunk++) {
        int start = chunk * SWITCH_CHUNK_SIZE;
        int end = start + SWITCH_CHUNK_SIZE;
        builder
            .append("    if (index >= ")
            .append(start)
            .append(" && index < ")
            .append(end)
            .append(") return Types")
            .append(section)
            .append('_')
            .append(chunk)
            .append(".get(index);\n");
      }
      builder
          .append("    throw invalidIndex(\"type\", ")
          .append(section)
          .append(", index);\n  }\n\n");
    }
  }

  private void writeOperationBootstrap() {
    builder.append("  @Override\n  public Object metadataOperation(int section, int index) {\n");
    builder.append("    switch (section) {\n");
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      if (hasDirectOperations(model.section(section))) {
        builder
            .append("      case ")
            .append(section)
            .append(": return operation")
            .append(section)
            .append("(index);\n");
      }
    }
    builder.append("      default: throw invalidIndex(\"operation\", section, index);\n");
    builder.append("    }\n  }\n\n");
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      JsonSourceModel.Section value = model.section(section);
      if (!hasDirectOperations(value)) {
        continue;
      }
      builder.append("  private static Object operation").append(section).append("(int index) {\n");
      for (int chunk = 0; chunk * SWITCH_CHUNK_SIZE < value.directOperationCount; chunk++) {
        int start = chunk * SWITCH_CHUNK_SIZE;
        int end = start + SWITCH_CHUNK_SIZE;
        builder
            .append("    if (index >= ")
            .append(start)
            .append(" && index < ")
            .append(end)
            .append(") return Operations")
            .append(section)
            .append('_')
            .append(chunk)
            .append(".get(index);\n");
      }
      builder
          .append("    throw invalidIndex(\"operation\", ")
          .append(section)
          .append(", index);\n  }\n\n");
    }
  }

  private void writeSectionHolders() {
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      JsonMetadataEncoder.EncodedSection value = encoded[section];
      builder.append("  private static final class Section").append(section).append(" {\n");
      builder.append(
          "    private static final JsonTypeMetadataData DATA = new JsonTypeMetadataData(\n");
      builder.append("        new byte[][] {");
      for (int i = 0; i < value.facts.length; i++) {
        if (i != 0) {
          builder.append(", ");
        }
        builder.append("facts").append(i).append("()");
      }
      builder.append("},\n        new String[][] {");
      for (int i = 0; i < value.inaccessibleNames.length; i++) {
        if (i != 0) {
          builder.append(", ");
        }
        builder.append("names").append(i).append("()");
      }
      builder.append("});\n");
      for (int i = 0; i < value.facts.length; i++) {
        builder
            .append("    private static byte[] facts")
            .append(i)
            .append("() { return new byte[] {");
        byte[] bytes = value.facts[i];
        for (int j = 0; j < bytes.length; j++) {
          if (j != 0) {
            builder.append(',');
          }
          builder.append(bytes[j]);
        }
        builder.append("}; }\n");
      }
      for (int i = 0; i < value.inaccessibleNames.length; i++) {
        builder
            .append("    private static String[] names")
            .append(i)
            .append("() { return new String[] {");
        for (int j = 0; j < value.inaccessibleNames[i].length; j++) {
          if (j != 0) {
            builder.append(',');
          }
          builder.append('"').append(escape(value.inaccessibleNames[i][j])).append('"');
        }
        builder.append("}; }\n");
      }
      builder.append("  }\n\n");
    }
  }

  private void writeTypeChunks() {
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      JsonSourceModel.Section value = model.section(section);
      List<JsonSourceModel.Token> direct = directTokens(value);
      for (int start = 0; start < direct.size(); start += SWITCH_CHUNK_SIZE) {
        int chunk = start / SWITCH_CHUNK_SIZE;
        int end = Math.min(start + SWITCH_CHUNK_SIZE, direct.size());
        builder
            .append("  private static final class Types")
            .append(section)
            .append('_')
            .append(chunk)
            .append(" {\n    private static Class<?> get(int index) {\n      switch (index) {\n");
        for (int i = start; i < end; i++) {
          JsonSourceModel.Token token = direct.get(i);
          builder
              .append("        case ")
              .append(token.directIndex)
              .append(": return ")
              .append(sourceType(token.type))
              .append(".class;\n");
        }
        builder
            .append("        default: throw invalidIndex(\"type\", ")
            .append(section)
            .append(", index);\n");
        builder.append("      }\n    }\n  }\n\n");
      }
    }
  }

  private void writeOperationChunks() {
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      JsonSourceModel.Section value = model.section(section);
      List<JsonSourceModel.Operation> direct = directOperations(value);
      for (int start = 0; start < direct.size(); start += SWITCH_CHUNK_SIZE) {
        int chunk = start / SWITCH_CHUNK_SIZE;
        int end = Math.min(start + SWITCH_CHUNK_SIZE, direct.size());
        builder
            .append("  private static final class Operations")
            .append(section)
            .append('_')
            .append(chunk)
            .append(" {\n    private static Object get(int index) {\n      switch (index) {\n");
        for (int i = start; i < end; i++) {
          JsonSourceModel.Operation operation = direct.get(i);
          builder
              .append("        case ")
              .append(operation.directIndex)
              .append(": return Op")
              .append(section)
              .append('_')
              .append(operation.id)
              .append(".INSTANCE;\n");
        }
        builder
            .append("        default: throw invalidIndex(\"operation\", ")
            .append(section)
            .append(", index);\n");
        builder.append("      }\n    }\n  }\n\n");
      }
    }
  }

  private void writeOperations() {
    for (int section = 0; section < JsonTypeMetadata.SECTION_COUNT; section++) {
      for (JsonSourceModel.Operation operation : model.section(section).operations) {
        if (operation.direct) {
          writeOperation(section, operation);
        }
      }
    }
  }

  private void writeOperation(int section, JsonSourceModel.Operation operation) {
    String name = "Op" + section + '_' + operation.id;
    if (operation.shape == JsonMetadataFormat.FIELD_ACCESS
        || operation.shape == JsonMetadataFormat.GETTER
        || operation.shape == JsonMetadataFormat.SETTER) {
      builder
          .append("  private static final class ")
          .append(name)
          .append(" extends JsonFieldAccessor {\n");
      builder
          .append("    private static final ")
          .append(name)
          .append(" INSTANCE = new ")
          .append(name)
          .append("();\n");
      writeAccessor(operation);
      builder.append("  }\n\n");
      return;
    }
    if (operation.shape == JsonMetadataFormat.ANY_SETTER) {
      ExecutableElement method = (ExecutableElement) operation.member;
      builder
          .append("  private static final class ")
          .append(name)
          .append(" extends JsonAnySetterInvoker {\n");
      builder
          .append("    private static final ")
          .append(name)
          .append(" INSTANCE = new ")
          .append(name)
          .append("();\n");
      builder
          .append(
              "    @Override public void set(Object target, String property, Object value) {\n      try {\n        ((")
          .append(operation.ownerSourceName)
          .append(") target).")
          .append(operation.memberName)
          .append("(property, ")
          .append(argument(method.getParameters().get(1).asType(), "value"))
          .append(");\n      } catch (Throwable cause) { throw operationFailure(\"")
          .append(escape(operation.memberName))
          .append("\", cause); }\n    }\n  }\n\n");
      return;
    }
    if (operation.shape == JsonMetadataFormat.CODEC_FACTORY) {
      builder
          .append("  private static final class ")
          .append(name)
          .append(" extends JsonCodecFactory {\n");
      builder
          .append("    private static final ")
          .append(name)
          .append(" INSTANCE = new ")
          .append(name)
          .append("();\n");
      builder
          .append(
              "    @Override public org.apache.fory.json.codec.JsonValueCodec<?> create() {\n      try { return new ")
          .append(sourceType(operation.valueType))
          .append(
              "(); } catch (Throwable cause) { throw operationFailure(\"codec factory\", cause); }\n    }\n  }\n\n");
      return;
    }
    if (operation.shape == JsonMetadataFormat.CREATOR_CALL) {
      writeCreator(name, operation);
      return;
    }
    writeInstantiator(name, operation);
  }

  private void writeAccessor(JsonSourceModel.Operation operation) {
    String primitive = primitiveSuffix(operation.valueType.getKind());
    String getMethod = primitive == null ? "getObject" : "get" + primitive;
    String putMethod = primitive == null ? "putObject" : "put" + primitive;
    String returnType = primitive == null ? "Object" : sourceType(operation.valueType);
    String valueType = primitive == null ? "Object" : sourceType(operation.valueType);
    if ((operation.directionMask & JsonMetadataFormat.WRITE) != 0) {
      builder
          .append("    @Override public ")
          .append(returnType)
          .append(' ')
          .append(getMethod)
          .append("(Object target) {");
      if (operation.shape == JsonMetadataFormat.FIELD_ACCESS) {
        builder
            .append(" return ((")
            .append(operation.ownerSourceName)
            .append(") target).")
            .append(operation.memberName)
            .append("; }\n");
      } else {
        builder
            .append(" try { return ((")
            .append(operation.ownerSourceName)
            .append(") target).")
            .append(operation.memberName)
            .append("(); } catch (Throwable cause) { throw operationFailure(\"")
            .append(escape(operation.memberName))
            .append("\", cause); } }\n");
      }
    }
    if ((operation.directionMask & JsonMetadataFormat.READ) != 0) {
      builder
          .append("    @Override public void ")
          .append(putMethod)
          .append("(Object target, ")
          .append(valueType)
          .append(" value) {");
      if (operation.shape == JsonMetadataFormat.FIELD_ACCESS) {
        builder
            .append(" ((")
            .append(operation.ownerSourceName)
            .append(") target).")
            .append(operation.memberName)
            .append(" = ")
            .append(primitive == null ? argument(operation.valueType, "value") : "value")
            .append("; }\n");
      } else {
        builder
            .append(" try { ((")
            .append(operation.ownerSourceName)
            .append(") target).")
            .append(operation.memberName)
            .append('(')
            .append(primitive == null ? argument(operation.valueType, "value") : "value")
            .append("); } catch (Throwable cause) { throw operationFailure(\"")
            .append(escape(operation.memberName))
            .append("\", cause); } }\n");
      }
    }
  }

  private void writeCreator(String name, JsonSourceModel.Operation operation) {
    ExecutableElement executable = (ExecutableElement) operation.member;
    builder
        .append("  private static final class ")
        .append(name)
        .append(" extends JsonCreatorInvoker {\n");
    builder
        .append("    private static final ")
        .append(name)
        .append(" INSTANCE = new ")
        .append(name)
        .append("();\n");
    builder.append("    @Override public Object create(Object[] arguments) {\n      try { return ");
    if (executable.getKind() == ElementKind.CONSTRUCTOR) {
      builder.append("new ").append(operation.ownerSourceName);
    } else {
      builder.append(operation.ownerSourceName).append('.').append(operation.memberName);
    }
    builder.append('(');
    appendArguments(executable.getParameters());
    builder.append(
        "); } catch (Throwable cause) { throw operationFailure(\"creator\", cause); }\n    }\n  }\n\n");
  }

  private void writeInstantiator(String name, JsonSourceModel.Operation operation) {
    ExecutableElement constructor = (ExecutableElement) operation.member;
    String owner = operation.ownerSourceName;
    builder
        .append("  private static final class ")
        .append(name)
        .append(" extends ObjectInstantiator<")
        .append(owner)
        .append("> {\n");
    builder
        .append("    private static final ")
        .append(name)
        .append(" INSTANCE = new ")
        .append(name)
        .append("();\n");
    builder
        .append("    private ")
        .append(name)
        .append("() { super(")
        .append(owner)
        .append(".class); }\n");
    if (operation.shape == JsonMetadataFormat.NO_ARG_CONSTRUCTOR) {
      builder
          .append("    @Override public ")
          .append(owner)
          .append(" newInstance() { try { return new ")
          .append(owner)
          .append(
              "(); } catch (Throwable cause) { throw operationFailure(\"constructor\", cause); } }\n");
      builder
          .append("    @Override public ")
          .append(owner)
          .append(
              " newInstanceWithArguments(Object... arguments) { throw new UnsupportedOperationException(); }\n");
    } else {
      builder
          .append("    @Override public ")
          .append(owner)
          .append(" newInstance() { throw new UnsupportedOperationException(); }\n");
      builder
          .append("    @Override public ")
          .append(owner)
          .append(" newInstanceWithArguments(Object... arguments) { try { return new ")
          .append(owner)
          .append('(');
      appendArguments(constructor.getParameters());
      builder.append(
          "); } catch (Throwable cause) { throw operationFailure(\"record constructor\", cause); } }\n");
    }
    builder.append("  }\n\n");
  }

  private void appendArguments(List<? extends VariableElement> parameters) {
    for (int i = 0; i < parameters.size(); i++) {
      if (i != 0) {
        builder.append(", ");
      }
      builder.append(argument(parameters.get(i).asType(), "arguments[" + i + "]"));
    }
  }

  private String argument(TypeMirror type, String expression) {
    switch (type.getKind()) {
      case BOOLEAN:
        return "((Boolean) " + expression + ").booleanValue()";
      case BYTE:
        return "((Byte) " + expression + ").byteValue()";
      case SHORT:
        return "((Short) " + expression + ").shortValue()";
      case INT:
        return "((Integer) " + expression + ").intValue()";
      case LONG:
        return "((Long) " + expression + ").longValue()";
      case FLOAT:
        return "((Float) " + expression + ").floatValue()";
      case DOUBLE:
        return "((Double) " + expression + ").doubleValue()";
      case CHAR:
        return "((Character) " + expression + ").charValue()";
      default:
        return "(" + sourceType(type) + ") " + expression;
    }
  }

  private String sourceType(TypeMirror type) {
    TypeKind kind = type.getKind();
    if (kind.isPrimitive() || kind == TypeKind.VOID) {
      return type.toString();
    }
    if (kind == TypeKind.ARRAY) {
      return sourceType(((javax.lang.model.type.ArrayType) type).getComponentType()) + "[]";
    }
    TypeMirror erased = types.erasure(type);
    TypeElement element = (TypeElement) types.asElement(erased);
    return element.getQualifiedName().toString();
  }

  private static String primitiveSuffix(TypeKind kind) {
    switch (kind) {
      case BOOLEAN:
        return "Boolean";
      case BYTE:
        return "Byte";
      case SHORT:
        return "Short";
      case INT:
        return "Int";
      case LONG:
        return "Long";
      case FLOAT:
        return "Float";
      case DOUBLE:
        return "Double";
      case CHAR:
        return "Char";
      default:
        return null;
    }
  }

  private static boolean hasDirectTokens(JsonSourceModel.Section section) {
    return !directTokens(section).isEmpty();
  }

  private static List<JsonSourceModel.Token> directTokens(JsonSourceModel.Section section) {
    List<JsonSourceModel.Token> result = new ArrayList<>();
    for (JsonSourceModel.Token token : section.tokens) {
      if (!token.primitive() && token.direct) {
        result.add(token);
      }
    }
    return result;
  }

  private static boolean hasDirectOperations(JsonSourceModel.Section section) {
    for (JsonSourceModel.Operation operation : section.operations) {
      if (operation.direct) {
        return true;
      }
    }
    return false;
  }

  private static List<JsonSourceModel.Operation> directOperations(JsonSourceModel.Section section) {
    List<JsonSourceModel.Operation> result = new ArrayList<>();
    for (JsonSourceModel.Operation operation : section.operations) {
      if (operation.direct) {
        result.add(operation);
      }
    }
    return result;
  }

  private static String escape(String value) {
    StringBuilder result = new StringBuilder(value.length() + 16);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c == '"') {
        result.append('\\').append(c);
      } else if (c == '\n') {
        result.append("\\n");
      } else if (c == '\r') {
        result.append("\\r");
      } else if (c == '\t') {
        result.append("\\t");
      } else {
        result.append(c);
      }
    }
    return result.toString();
  }
}
