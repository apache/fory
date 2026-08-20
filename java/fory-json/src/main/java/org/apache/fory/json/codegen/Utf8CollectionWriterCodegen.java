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
import org.apache.fory.json.codec.ArrayListCodecSupport;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Generates one exact declared UTF-8 collection capability. */
final class Utf8CollectionWriterCodegen {
  String genCode(String generatedPackage, String className, boolean stringElements) {
    CodegenContext ctx = new CodegenContext();
    ctx.setPackage(generatedPackage);
    ctx.setClassName(className);
    ctx.setClassModifiers("final");
    ctx.addImports(
        ArrayList.class, ArrayListCodecSupport.class, Utf8JsonWriter.class, Utf8WriterCodec.class);
    ctx.implementsInterfaces(ctx.type(Utf8WriterCodec.class));
    ctx.addField(true, ctx.type(Utf8WriterCodec.class), "fallback", null);
    if (stringElements) {
      ctx.addConstructor("this.fallback = fallback;", Utf8WriterCodec.class, "fallback");
    } else {
      ctx.addField(true, ctx.type(Utf8WriterCodec.class), "elementWriter", null);
      ctx.addConstructor(
          "this.fallback = fallback;\nthis.elementWriter = elementWriter;",
          Utf8WriterCodec.class,
          "fallback",
          Utf8WriterCodec.class,
          "elementWriter");
    }
    ctx.addMethod(
        "@Override public final",
        "writeUtf8",
        writeBody(stringElements, ArrayListCodecSupport.isAvailable()),
        void.class,
        Utf8JsonWriter.class,
        "writer",
        Object.class,
        "value");
    return ctx.genCode();
  }

  private static String writeBody(boolean stringElements, boolean directElements) {
    // The collection owns iteration and calls the final element codec's ordinary writeUtf8 entry.
    // That entry owns null handling and any generated object-group boundaries; do not copy the
    // element object's schema work into this loop.
    //
    // Exact ArrayList source snapshots its backing array before the loop when the current runtime
    // supports a VarHandle to it. ArrayList.get must reload both mutable size and elementData after
    // every arbitrary element-writer call, which C2 cannot hoist. Keep the fallback decision here
    // on the cold source-generation path rather than adding an availability branch to generated
    // code, and do not apply this shape to subclasses or handwritten collection codecs.
    String element = directElements ? "elements[index]" : "list.get(index)";
    String elementWrite =
        stringElements
            ? "String element = (String) "
                + element
                + ";\n"
                + "  writer.writeComma(index);\n"
                + "  if (element == null) {\n"
                + "    writer.writeNull();\n"
                + "  } else {\n"
                + "    writer.writeString(element);\n"
                + "  }"
            : "writer.writeComma(index);\n" + "  elementWriter.writeUtf8(writer, " + element + ");";
    String elements =
        directElements
            ? "int size = list.size();\n"
                + "Object[] elements = ArrayListCodecSupport.elements(list);\n"
            : "";
    String loop =
        directElements
            ? "for (int index = 0; index < size; index++) {\n"
            : "for (int index = 0, size = list.size(); index < size; index++) {\n";
    return "if (value == null) {\n"
        + "  writer.writeNull();\n"
        + "  return;\n"
        + "}\n"
        + "if (value.getClass() != ArrayList.class) {\n"
        + "  fallback.writeUtf8(writer, value);\n"
        + "  return;\n"
        + "}\n"
        + "ArrayList list = (ArrayList) value;\n"
        + elements
        + "writer.writeArrayStart();\n"
        + loop
        + "  "
        + elementWrite
        + "\n"
        + "}\n"
        + "writer.writeArrayEnd();";
  }
}
