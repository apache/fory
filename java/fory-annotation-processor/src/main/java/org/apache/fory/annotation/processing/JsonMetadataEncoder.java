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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.json.meta.JsonMetadataFormat;

/** Encodes one collected section into the compact generated transport. */
final class JsonMetadataEncoder {
  static final int FACT_CHUNK_BYTES = 8192;
  static final int NAME_CHUNK_SIZE = 128;

  EncodedSection encode(JsonSourceModel.Section section) {
    assignNames(section);
    Bytes output = new Bytes();
    output.byteValue(JsonMetadataFormat.MAGIC_0);
    output.byteValue(JsonMetadataFormat.MAGIC_1);
    output.byteValue(JsonMetadataFormat.MAGIC_2);
    output.byteValue(JsonMetadataFormat.MAGIC_3);
    output.unsigned(JsonMetadataFormat.VERSION);
    output.byteValue(section.id);
    output.unsigned(
        section.tokens.size()
            + section.typeParameters.size()
            + section.typeNodes.size()
            + section.operations.size()
            + section.facts.size());
    for (JsonSourceModel.Token token : section.tokens) {
      record(output, JsonMetadataFormat.TOKEN, tokenBody(token));
    }
    for (JsonSourceModel.TypeParameter parameter : section.typeParameters) {
      record(output, JsonMetadataFormat.TYPE_PARAMETER, parameterBody(parameter));
    }
    for (JsonSourceModel.TypeNode node : section.typeNodes) {
      record(output, JsonMetadataFormat.TYPE_NODE, typeBody(node));
    }
    for (JsonSourceModel.Operation operation : section.operations) {
      record(output, JsonMetadataFormat.OPERATION, operationBody(operation));
    }
    for (JsonSourceModel.Fact fact : section.facts) {
      record(output, fact.tag, factBody(fact));
    }
    return new EncodedSection(chunks(output.bytes()), nameChunks(section));
  }

  private static Bytes tokenBody(JsonSourceModel.Token token) {
    Bytes body = new Bytes();
    body.unsigned(token.id);
    if (token.primitive()) {
      body.byteValue(JsonMetadataFormat.TOKEN_PRIMITIVE);
      body.byteValue(token.primitiveKind);
    } else if (token.direct) {
      body.byteValue(JsonMetadataFormat.TOKEN_DIRECT);
      body.unsigned(token.directIndex);
    } else {
      body.byteValue(JsonMetadataFormat.TOKEN_INACCESSIBLE);
      body.unsigned(token.nameChunk);
      body.unsigned(token.nameSlot);
    }
    return body;
  }

  private static Bytes parameterBody(JsonSourceModel.TypeParameter parameter) {
    Bytes body = new Bytes();
    body.unsigned(parameter.id);
    body.byteValue(parameter.ownerKind);
    body.unsigned(parameter.ownerToken);
    body.string(parameter.memberName);
    body.string(parameter.descriptor);
    body.unsigned(parameter.parameterIndex);
    body.indexes(parameter.bounds);
    return body;
  }

  private static Bytes typeBody(JsonSourceModel.TypeNode node) {
    Bytes body = new Bytes();
    body.unsigned(node.id);
    body.byteValue(node.kind);
    body.signed(node.codecToken);
    body.signed(node.codecOperation);
    body.string(node.codecSource);
    if (node.kind == JsonMetadataFormat.TYPE_PRIMITIVE) {
      body.byteValue(node.token);
    } else if (node.kind == JsonMetadataFormat.TYPE_DECLARED) {
      body.unsigned(node.token);
      body.signed(node.ownerNode);
      body.indexes(node.children);
    } else if (node.kind == JsonMetadataFormat.TYPE_ARRAY) {
      body.unsigned(node.token);
    } else if (node.kind == JsonMetadataFormat.TYPE_VARIABLE) {
      body.unsigned(node.variableKey);
    } else {
      body.indexes(node.children);
      body.indexes(node.lowerBounds);
    }
    return body;
  }

  private static Bytes operationBody(JsonSourceModel.Operation operation) {
    Bytes body = new Bytes();
    body.unsigned(operation.id);
    body.byteValue(operation.shape);
    body.byteValue(operation.direct ? JsonMetadataFormat.OP_DIRECT : JsonMetadataFormat.OP_HANDLE);
    body.byteValue(operation.directionMask);
    if (operation.direct) {
      body.unsigned(operation.directIndex);
    } else {
      body.unsigned(operation.ownerToken);
      body.string(operation.memberName);
      body.string(operation.descriptor);
    }
    return body;
  }

  private static Bytes factBody(JsonSourceModel.Fact fact) {
    if (fact instanceof JsonSourceModel.DeclarationFact) {
      JsonSourceModel.DeclarationFact value = (JsonSourceModel.DeclarationFact) fact;
      Bytes body = new Bytes();
      body.unsigned(value.typeToken);
      body.byteValue(value.kind);
      body.unsigned(value.modifiers);
      body.signed(value.superclassToken);
      body.indexes(value.interfaceTokens);
      body.signed(value.codecToken);
      body.signed(value.codecOperation);
      return body;
    }
    if (fact instanceof JsonSourceModel.SubtypesFact) {
      JsonSourceModel.SubtypesFact value = (JsonSourceModel.SubtypesFact) fact;
      Bytes body = new Bytes();
      body.byteValue(value.inclusion);
      body.string(value.property);
      body.unsigned(value.entries.size());
      for (JsonSourceModel.SubtypeEntry entry : value.entries) {
        body.signed(entry.typeToken);
        body.string(entry.className);
        body.string(entry.name);
      }
      return body;
    }
    if (fact instanceof JsonSourceModel.FieldFact) {
      JsonSourceModel.FieldFact value = (JsonSourceModel.FieldFact) fact;
      Bytes body = new Bytes();
      body.unsigned(value.declaringToken);
      body.string(value.name);
      body.string(value.descriptor);
      body.unsigned(value.modifiers);
      body.unsigned(value.typeNode);
      body.unsigned(value.flags);
      body.property(value.property);
      body.signed(value.operation);
      return body;
    }
    if (fact instanceof JsonSourceModel.MethodFact) {
      JsonSourceModel.MethodFact value = (JsonSourceModel.MethodFact) fact;
      Bytes body = new Bytes();
      body.unsigned(value.declaringToken);
      body.string(value.name);
      body.string(value.descriptor);
      body.unsigned(value.modifiers);
      body.unsigned(value.returnNode);
      body.indexes(value.parameters);
      body.unsigned(value.flags);
      body.property(value.property);
      body.signed(value.operation);
      return body;
    }
    if (fact instanceof JsonSourceModel.CreatorFact) {
      JsonSourceModel.CreatorFact value = (JsonSourceModel.CreatorFact) fact;
      Bytes body = new Bytes();
      body.byteValue(value.executableKind);
      body.unsigned(value.declaringToken);
      body.string(value.name);
      body.string(value.descriptor);
      body.unsigned(value.modifiers);
      body.strings(value.propertyNames);
      body.indexes(value.parameterNodes);
      body.unsigned(value.parameterProperties.size());
      for (JsonSourceModel.Property property : value.parameterProperties) {
        body.creatorProperty(property);
      }
      body.unsigned(value.operation);
      return body;
    }
    if (fact instanceof JsonSourceModel.RecordFact) {
      JsonSourceModel.RecordFact value = (JsonSourceModel.RecordFact) fact;
      Bytes body = new Bytes();
      body.unsigned(value.declaringToken);
      body.string(value.name);
      body.unsigned(value.typeNode);
      body.string(value.accessorDescriptor);
      body.unsigned(value.flags);
      body.property(value.property);
      return body;
    }
    if (fact instanceof JsonSourceModel.OrderFact) {
      JsonSourceModel.OrderFact value = (JsonSourceModel.OrderFact) fact;
      Bytes body = new Bytes();
      body.unsigned(value.declaringToken);
      body.strings(value.names);
      body.byteValue(value.alphabetic ? 1 : 0);
      return body;
    }
    if (fact instanceof JsonSourceModel.InstantiatorFact) {
      JsonSourceModel.InstantiatorFact value = (JsonSourceModel.InstantiatorFact) fact;
      Bytes body = new Bytes();
      body.byteValue(value.kind);
      body.unsigned(value.operation);
      return body;
    }
    JsonSourceModel.HierarchyFact value = (JsonSourceModel.HierarchyFact) fact;
    Bytes body = new Bytes();
    body.unsigned(value.declaringToken);
    body.signed(value.superclassNode);
    body.indexes(value.interfaceNodes);
    return body;
  }

  private static void record(Bytes output, int tag, Bytes body) {
    output.byteValue(tag);
    byte[] bytes = body.bytes();
    output.unsigned(bytes.length);
    output.bytes(bytes);
  }

  private static void assignNames(JsonSourceModel.Section section) {
    int index = 0;
    for (JsonSourceModel.Token token : section.tokens) {
      if (!token.primitive() && !token.direct) {
        token.nameChunk = index / NAME_CHUNK_SIZE;
        token.nameSlot = index % NAME_CHUNK_SIZE;
        index++;
      }
    }
  }

  private static String[][] nameChunks(JsonSourceModel.Section section) {
    List<String> names = new ArrayList<>();
    for (JsonSourceModel.Token token : section.tokens) {
      if (!token.primitive() && !token.direct) {
        names.add(token.binaryName);
      }
    }
    if (names.isEmpty()) {
      return new String[0][];
    }
    int count = (names.size() + NAME_CHUNK_SIZE - 1) / NAME_CHUNK_SIZE;
    String[][] chunks = new String[count][];
    for (int i = 0; i < count; i++) {
      int start = i * NAME_CHUNK_SIZE;
      int end = Math.min(start + NAME_CHUNK_SIZE, names.size());
      chunks[i] = names.subList(start, end).toArray(new String[0]);
    }
    return chunks;
  }

  private static byte[][] chunks(byte[] bytes) {
    int count = (bytes.length + FACT_CHUNK_BYTES - 1) / FACT_CHUNK_BYTES;
    byte[][] chunks = new byte[count][];
    for (int i = 0; i < count; i++) {
      int start = i * FACT_CHUNK_BYTES;
      int length = Math.min(FACT_CHUNK_BYTES, bytes.length - start);
      byte[] chunk = new byte[length];
      System.arraycopy(bytes, start, chunk, 0, length);
      chunks[i] = chunk;
    }
    return chunks;
  }

  static final class EncodedSection {
    final byte[][] facts;
    final String[][] inaccessibleNames;

    EncodedSection(byte[][] facts, String[][] inaccessibleNames) {
      this.facts = facts;
      this.inaccessibleNames = inaccessibleNames;
    }
  }

  private static final class Bytes {
    private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

    void byteValue(int value) {
      stream.write(value & 0xff);
    }

    void unsigned(int value) {
      if (value < 0) {
        throw new IllegalArgumentException("Negative unsigned value " + value);
      }
      int remaining = value;
      while ((remaining & ~0x7f) != 0) {
        byteValue((remaining & 0x7f) | 0x80);
        remaining >>>= 7;
      }
      byteValue(remaining);
    }

    void signed(int value) {
      unsigned((value << 1) ^ (value >> 31));
    }

    void string(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      unsigned(bytes.length);
      bytes(bytes);
    }

    void strings(List<String> values) {
      unsigned(values.size());
      for (String value : values) {
        string(value);
      }
    }

    void indexes(List<Integer> values) {
      unsigned(values.size());
      for (Integer value : values) {
        unsigned(value);
      }
    }

    void property(JsonSourceModel.Property property) {
      if (!property.present) {
        return;
      }
      string(property.name);
      signed(property.index);
      byteValue(property.include);
    }

    void creatorProperty(JsonSourceModel.Property property) {
      byteValue(property.present ? 1 : 0);
      string(property.name);
      signed(property.index);
      byteValue(property.include);
    }

    void bytes(byte[] bytes) {
      stream.write(bytes, 0, bytes.length);
    }

    byte[] bytes() {
      return stream.toByteArray();
    }
  }
}
