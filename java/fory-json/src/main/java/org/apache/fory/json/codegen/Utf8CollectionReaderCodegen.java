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

package org.apache.fory.json.codegen;

import java.util.ArrayList;
import org.apache.fory.codegen.Code;
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.reader.Utf8JsonReader;

/** Generates one exact declared ArrayList-backed UTF-8 collection capability. */
final class Utf8CollectionReaderCodegen {
  // The ninth value remains scalar until the following separator proves whether the list ends or
  // continues. Exact size nine therefore allocates nine slots, while a continuation starts at the
  // same capacity 13 that ArrayList growth would have selected, without creating and copying a
  // discarded nine-slot array.
  private static final int ARRAY_LIST_PREFIX_SIZE = 9;
  private static final int ARRAY_LIST_FIRST_GROWTH =
      ARRAY_LIST_PREFIX_SIZE + (ARRAY_LIST_PREFIX_SIZE >> 1);

  String genCode(String generatedPackage, String className, boolean stringElements) {
    CodegenContext ctx = new CodegenContext();
    ctx.setPackage(generatedPackage);
    ctx.setClassName(className);
    ctx.setClassModifiers("final");
    ctx.addImports(ArrayList.class, Utf8JsonReader.class, Utf8ReaderCodec.class);
    ctx.implementsInterfaces(ctx.type(Utf8ReaderCodec.class));
    ctx.addField(true, ctx.type(Utf8ReaderCodec.class), "elementReader", null);
    ctx.addConstructor(
        "this.elementReader = elementReader;", Utf8ReaderCodec.class, "elementReader");
    if (stringElements) {
      addStringArrayListMethods(ctx);
    } else {
      ctx.addMethod(
          "@Override public final",
          "readUtf8",
          readBody(),
          Object.class,
          Utf8JsonReader.class,
          "reader");
    }
    return ctx.genCode();
  }

  private static void addStringArrayListMethods(CodegenContext ctx) {
    ctx.addMethod(
        "@Override public final",
        "readUtf8",
        readStringArrayListCode(ctx),
        Object.class,
        Utf8JsonReader.class,
        "reader");
    ctx.addMethod(
        "private final",
        "readArrayListBody",
        readStringArrayListBodyCode(ctx),
        ArrayList.class,
        Utf8JsonReader.class,
        "reader",
        String.class,
        "e0",
        String.class,
        "e1");
    ctx.addMethod(
        "private final",
        "readArrayListTail",
        readStringArrayListTailCode(ctx),
        ArrayList.class,
        Utf8JsonReader.class,
        "reader",
        String.class,
        "e0",
        String.class,
        "e1",
        String.class,
        "e2",
        String.class,
        "e3");
    ctx.addMethod(
        "private final",
        "readArrayListLongTail",
        readStringArrayListLongTailCode(ctx),
        ArrayList.class,
        Utf8JsonReader.class,
        "reader",
        String.class,
        "e0",
        String.class,
        "e1",
        String.class,
        "e2",
        String.class,
        "e3",
        String.class,
        "e4",
        String.class,
        "e5");
    ctx.addMethod(
        "private final",
        "readArrayListLoop",
        readStringArrayListLoopCode(ctx),
        ArrayList.class,
        Utf8JsonReader.class,
        "reader",
        String.class,
        "e0",
        String.class,
        "e1",
        String.class,
        "e2",
        String.class,
        "e3",
        String.class,
        "e4",
        String.class,
        "e5",
        String.class,
        "e6",
        String.class,
        "e7");
  }

  private static String readStringArrayListCode(CodegenContext ctx) {
    return "if (reader.tryReadNullToken()) {\n"
        + "  return null;\n"
        + "}\n"
        + "reader.enterDepth();\n"
        + "reader.expectNextToken('[');\n"
        + "if (reader.consumeNextToken(']')) {\n"
        + "  reader.exitDepth();\n"
        + "  return new ArrayList(0);\n"
        + "}\n"
        + readStringElement(ctx, "e0", "E0")
        + "int nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(1);\n"
        + "  list.add(e0);\n"
        + "  return list;\n"
        + "}\n"
        + readStringArrayElement(ctx, "e1", "E1")
        + "return readArrayListBody(reader, e0, e1);";
  }

  private static String readStringArrayListBodyCode(CodegenContext ctx) {
    // Each prefix method consumes its incoming separator state locally. This keeps cursor
    // publication and the following quote probe in one generated compilation unit.
    return "int nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(2);\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  return list;\n"
        + "}\n"
        + readStringArrayElement(ctx, "e2", "B2")
        + "nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(3);\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  list.add(e2);\n"
        + "  return list;\n"
        + "}\n"
        + readStringArrayElement(ctx, "e3", "B3")
        + "return readArrayListTail(reader, e0, e1, e2, e3);";
  }

  private static String readStringArrayListTailCode(CodegenContext ctx) {
    return "int nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(4);\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  list.add(e2);\n"
        + "  list.add(e3);\n"
        + "  return list;\n"
        + "}\n"
        + readStringArrayElement(ctx, "e4", "T4")
        + "nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(5);\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  list.add(e2);\n"
        + "  list.add(e3);\n"
        + "  list.add(e4);\n"
        + "  return list;\n"
        + "}\n"
        + readStringArrayElement(ctx, "e5", "T5")
        + "return readArrayListLongTail(reader, e0, e1, e2, e3, e4, e5);";
  }

  private static String readStringArrayListLongTailCode(CodegenContext ctx) {
    return "int nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(6);\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  list.add(e2);\n"
        + "  list.add(e3);\n"
        + "  list.add(e4);\n"
        + "  list.add(e5);\n"
        + "  return list;\n"
        + "}\n"
        + readStringArrayElement(ctx, "e6", "L6")
        + "nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(7);\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  list.add(e2);\n"
        + "  list.add(e3);\n"
        + "  list.add(e4);\n"
        + "  list.add(e5);\n"
        + "  list.add(e6);\n"
        + "  return list;\n"
        + "}\n"
        + readStringArrayElement(ctx, "e7", "L7")
        + "if (!reader.consumeNextCommaOrEndArray()) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList(8);\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  list.add(e2);\n"
        + "  list.add(e3);\n"
        + "  list.add(e4);\n"
        + "  list.add(e5);\n"
        + "  list.add(e6);\n"
        + "  list.add(e7);\n"
        + "  return list;\n"
        + "}\n"
        + "return readArrayListLoop(reader, e0, e1, e2, e3, e4, e5, e6, e7);";
  }

  private static String readStringArrayListLoopCode(CodegenContext ctx) {
    return "String e8 = (String) elementReader.readUtf8(reader);\n"
        + "int nextElement = reader.consumeNextStringArrayElement();\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_END) {\n"
        + "  reader.exitDepth();\n"
        + "  ArrayList list = new ArrayList("
        + ARRAY_LIST_PREFIX_SIZE
        + ");\n"
        + "  list.add(e0);\n"
        + "  list.add(e1);\n"
        + "  list.add(e2);\n"
        + "  list.add(e3);\n"
        + "  list.add(e4);\n"
        + "  list.add(e5);\n"
        + "  list.add(e6);\n"
        + "  list.add(e7);\n"
        + "  list.add(e8);\n"
        + "  return list;\n"
        + "}\n"
        + "ArrayList list = new ArrayList("
        + ARRAY_LIST_FIRST_GROWTH
        + ");\n"
        + "list.add(e0);\n"
        + "list.add(e1);\n"
        + "list.add(e2);\n"
        + "list.add(e3);\n"
        + "list.add(e4);\n"
        + "list.add(e5);\n"
        + "list.add(e6);\n"
        + "list.add(e7);\n"
        + "list.add(e8);\n"
        + "do {\n"
        + "  String element;\n"
        + "  if (nextElement == Utf8JsonReader.STRING_ARRAY_QUOTED) {\n"
        + readQuotedStringElement(ctx, "quotedElement", "LoopQuoted")
        + "    element = quotedElement;\n"
        + "  } else {\n"
        + "    element = (String) elementReader.readUtf8(reader);\n"
        + "  }\n"
        + "  list.add(element);\n"
        + "  nextElement = reader.consumeNextStringArrayElement();\n"
        + "} while (nextElement != Utf8JsonReader.STRING_ARRAY_END);\n"
        + "reader.exitDepth();\n"
        + "return list;";
  }

  private static String readStringElement(CodegenContext ctx, String variable, String id) {
    ctx.clearExprState();
    Code.ExprCode expression = Utf8ReaderCodegen.readStringElement(id).genCode(ctx);
    String expressionCode = expression.code();
    return (expressionCode == null ? "" : expressionCode + "\n")
        + "String "
        + variable
        + " = "
        + expression.value()
        + ";\n";
  }

  private static String readStringArrayElement(CodegenContext ctx, String variable, String id) {
    String quoted = variable + "Quoted";
    return "String "
        + variable
        + ";\n"
        + "if (nextElement == Utf8JsonReader.STRING_ARRAY_QUOTED) {\n"
        + readQuotedStringElement(ctx, quoted, id)
        + "  "
        + variable
        + " = "
        + quoted
        + ";\n"
        + "} else {\n"
        + "  "
        + variable
        + " = (String) elementReader.readUtf8(reader);\n"
        + "}\n";
  }

  private static String readQuotedStringElement(CodegenContext ctx, String variable, String id) {
    ctx.clearExprState();
    Code.ExprCode expression = Utf8ReaderCodegen.readQuotedStringElement(id).genCode(ctx);
    String expressionCode = expression.code();
    return (expressionCode == null ? "" : expressionCode + "\n")
        + "String "
        + variable
        + " = "
        + expression.value()
        + ";\n";
  }

  private static String readBody() {
    StringBuilder code = new StringBuilder();
    code.append("if (reader.tryReadNullToken()) {\n  return null;\n}\n");
    code.append("reader.enterDepth();\n");
    code.append("reader.expectNextToken('[');\n");
    code.append("if (reader.consumeNextToken(']')) {\n");
    code.append("  reader.exitDepth();\n  return new ArrayList(0);\n}\n");
    for (int i = 0; i < 8; i++) {
      code.append("Object e").append(i).append(" = null;\n");
    }
    code.append("ArrayList list = null;\nint size = 0;\n");
    code.append("do {\n");
    code.append("  Object element = elementReader.readUtf8(reader);\n");
    code.append("  if (list == null) {\n    switch (size) {\n");
    for (int i = 0; i < 8; i++) {
      code.append("      case ").append(i).append(": e").append(i).append(" = element; break;\n");
    }
    code.append("      default:\n        list = new ArrayList(9);\n");
    for (int i = 0; i < 8; i++) {
      code.append("        list.add(e").append(i).append(");\n");
    }
    code.append("        list.add(element);\n    }\n");
    code.append("  } else {\n    list.add(element);\n  }\n");
    code.append("  size++;\n} while (reader.consumeNextCommaOrEndArray());\n");
    code.append("reader.exitDepth();\n");
    code.append("if (list != null) {\n  return list;\n}\n");
    code.append("list = new ArrayList(size);\n");
    code.append("switch (size) {\n");
    for (int size = 1; size <= 8; size++) {
      code.append("  case ").append(size).append(":\n");
      for (int i = 0; i < size; i++) {
        code.append("    list.add(e").append(i).append(");\n");
      }
      code.append("    break;\n");
    }
    code.append("  default: throw new IllegalStateException();\n}\n");
    code.append("return list;\n");
    return code.toString();
  }
}
