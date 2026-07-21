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

import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.reflect.TypeRef;

final class Utf16ReaderCodegen extends JsonReaderCodegen {
  Utf16ReaderCodegen(JsonCodegen codegen) {
    super(codegen);
  }

  @Override
  Class<?> codecFieldType(JsonFieldInfo property) {
    return codegen.utf16ReaderFieldType(property.readTypeInfo());
  }

  @Override
  Class<?> readerType() {
    return Utf16JsonReader.class;
  }

  @Override
  Class<?> readerCapabilityType() {
    return Utf16ReaderCodec.class;
  }

  @Override
  Class<?> readerArrayType() {
    return Utf16ReaderCodec[].class;
  }

  @Override
  String readMethod() {
    return "readUtf16";
  }

  @Override
  String readEnumMethod(boolean tokenValueRead, boolean hashFallback) {
    return "readNextUtf16Enum";
  }

  @Override
  String readObjectMethod() {
    return "readUtf16";
  }

  @Override
  String readFieldMethod() {
    return "readUtf16";
  }

  @Override
  boolean isDirectName(String name, boolean tokenValueRead) {
    return tokenValueRead ? isUtf16FieldNameToken(name) : isPackedName(name);
  }

  @Override
  Expression tryReadNextFieldNameColon(JsonFieldInfo property, boolean tokenValueRead) {
    return tryReadUtf16FieldNameColon(property, tokenValueRead);
  }

  @Override
  Expression readEnumField(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean tokenValueRead) {
    return readEnumFallback(builder, property, id, object, tokenValueRead);
  }

  @Override
  Reference readerRef() {
    return new Reference("reader", TypeRef.of(Utf16JsonReader.class));
  }
}
