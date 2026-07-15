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
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.reflect.TypeRef;

final class Latin1ReaderCodegen extends JsonReaderCodegen {
  Latin1ReaderCodegen(JsonCodegen codegen) {
    super(codegen);
  }

  @Override
  Class<?> codecFieldType(JsonFieldInfo property) {
    return codegen.latin1ReaderFieldType(property.readTypeInfo());
  }

  @Override
  Class<?> readerType() {
    return Latin1JsonReader.class;
  }

  @Override
  Class<?> readerCapabilityType() {
    return Latin1ReaderCodec.class;
  }

  @Override
  Class<?> readerArrayType() {
    return Latin1ReaderCodec[].class;
  }

  @Override
  String readMethod() {
    return "readLatin1";
  }

  @Override
  String readEnumMethod(boolean tokenValueRead, boolean hashFallback) {
    return tokenValueRead
        ? (hashFallback ? "readLatin1EnumHashToken" : "readLatin1EnumToken")
        : "readNextLatin1Enum";
  }

  @Override
  String readObjectMethod() {
    return "readLatin1";
  }

  @Override
  String readFieldMethod() {
    return "readLatin1";
  }

  @Override
  boolean isDirectName(String name, boolean tokenValueRead) {
    return JsonAsciiToken.isLongPackable(fieldNameToken(name));
  }

  @Override
  Expression tryReadNextFieldNameColon(JsonFieldInfo property, boolean tokenValueRead) {
    return tryReadAsciiFieldNameColon(property);
  }

  @Override
  Expression readEnumField(
      JsonGeneratedCodecBuilder builder,
      JsonFieldInfo property,
      int id,
      Expression object,
      boolean tokenValueRead) {
    return readAsciiEnumField(builder, property, id, object, tokenValueRead);
  }

  @Override
  Reference readerRef() {
    return new Reference("reader", TypeRef.of(Latin1JsonReader.class));
  }
}
