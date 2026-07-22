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
import org.apache.fory.codegen.CodegenContext;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.reader.Utf8JsonReader;

/** Generates one exact declared ArrayList-backed UTF-8 collection capability. */
final class Utf8CollectionReaderCodegen {
  String genCode(String generatedPackage, String className) {
    CodegenContext ctx = new CodegenContext();
    ctx.setPackage(generatedPackage);
    ctx.setClassName(className);
    ctx.setClassModifiers("final");
    ctx.addImports(ArrayList.class, Utf8JsonReader.class, Utf8ReaderCodec.class);
    ctx.implementsInterfaces(ctx.type(Utf8ReaderCodec.class));
    ctx.addField(true, ctx.type(Utf8ReaderCodec.class), "elementReader", null);
    ctx.addConstructor(
        "this.elementReader = elementReader;", Utf8ReaderCodec.class, "elementReader");
    ctx.addMethod(
        "@Override public final",
        "readUtf8",
        readBody(),
        Object.class,
        Utf8JsonReader.class,
        "reader");
    return ctx.genCode();
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
