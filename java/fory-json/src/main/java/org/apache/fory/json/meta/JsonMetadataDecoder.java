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

package org.apache.fory.json.meta;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;

/** Strict streaming decoder for one generated metadata fact section. */
@Internal
public final class JsonMetadataDecoder {
  private static final int MAX_RECORDS = 1 << 20;
  private static final int ALL_FLAGS = (1 << 12) - 1;

  private JsonMetadataDecoder() {}

  public static JsonDecodedMetadata decode(
      Class<?> target, int expectedSection, JsonTypeMetadataData data) {
    if (target == null) {
      throw new ForyJsonException("Generated JSON metadata target must not be null");
    }
    if (expectedSection < 0 || expectedSection >= JsonTypeMetadata.SECTION_COUNT) {
      throw invalid(target, expectedSection, "invalid requested section");
    }
    if (data == null) {
      throw invalid(target, expectedSection, "transport is null");
    }
    Reader reader = new Reader(target, expectedSection, data.facts());
    requireByte(reader, JsonMetadataFormat.MAGIC_0, "magic byte 0");
    requireByte(reader, JsonMetadataFormat.MAGIC_1, "magic byte 1");
    requireByte(reader, JsonMetadataFormat.MAGIC_2, "magic byte 2");
    requireByte(reader, JsonMetadataFormat.MAGIC_3, "magic byte 3");
    int version = reader.readIndex("format version");
    if (version != JsonMetadataFormat.VERSION) {
      throw reader.failure(
          "format version " + version + " does not match " + JsonMetadataFormat.VERSION);
    }
    int section = reader.readByte("section");
    if (section != expectedSection) {
      throw reader.failure("stream section " + section + " does not match " + expectedSection);
    }
    int recordCount = reader.readIndex("record count");
    if (recordCount > MAX_RECORDS || recordCount > reader.remaining() / 2) {
      throw reader.failure("record count " + recordCount + " exceeds the bounded fact stream");
    }

    Lists lists = new Lists();
    for (int record = 0; record < recordCount; record++) {
      int tag = reader.readByte("record tag");
      validateTag(reader, section, tag);
      int bodyLength = reader.readIndex("record body length");
      int end = reader.enter(bodyLength, "record body");
      readRecord(reader, tag, lists);
      reader.leave(end, "record tag " + tag);
    }
    if (reader.remaining() != 0) {
      throw reader.failure("trailing bytes after " + recordCount + " records");
    }
    lists.resolveNames(data.inaccessibleTypeNames(), target, section);
    JsonDecodedMetadata decoded = lists.build(section);
    validate(decoded, target);
    return decoded;
  }

  private static void readRecord(Reader reader, int tag, Lists lists) {
    switch (tag) {
      case JsonMetadataFormat.TOKEN:
        readToken(reader, lists.tokens);
        return;
      case JsonMetadataFormat.TYPE_PARAMETER:
        readTypeParameter(reader, lists.typeParameters);
        return;
      case JsonMetadataFormat.TYPE_NODE:
        readTypeNode(reader, lists.typeNodes);
        return;
      case JsonMetadataFormat.OPERATION:
        readOperation(reader, lists.operations);
        return;
      case JsonMetadataFormat.DECLARATION:
        readDeclaration(reader, lists.declarations);
        return;
      case JsonMetadataFormat.SUBTYPE_TABLE:
        readSubtypes(reader, lists.subtypes);
        return;
      case JsonMetadataFormat.FIELD:
        readField(reader, lists.fields);
        return;
      case JsonMetadataFormat.METHOD:
        readMethod(reader, lists.methods);
        return;
      case JsonMetadataFormat.CREATOR:
        readCreator(reader, lists.creators);
        return;
      case JsonMetadataFormat.RECORD_COMPONENT:
        readRecordComponent(reader, lists.recordComponents);
        return;
      case JsonMetadataFormat.PROPERTY_ORDER:
        readPropertyOrder(reader, lists.propertyOrders);
        return;
      case JsonMetadataFormat.INSTANTIATOR:
        readInstantiator(reader, lists.instantiators);
        return;
      case JsonMetadataFormat.HIERARCHY:
        readHierarchy(reader, lists.hierarchies);
        return;
      default:
        throw reader.failure("unknown record tag " + tag);
    }
  }

  private static void readToken(Reader reader, List<JsonDecodedMetadata.Token> tokens) {
    requireDenseId(reader, "token", tokens.size());
    int mode = reader.readByte("token mode");
    switch (mode) {
      case JsonMetadataFormat.TOKEN_PRIMITIVE:
        tokens.add(
            new JsonDecodedMetadata.Token(
                mode, reader.readByte("primitive kind"), -1, null, -1, -1));
        return;
      case JsonMetadataFormat.TOKEN_DIRECT:
        tokens.add(
            new JsonDecodedMetadata.Token(
                mode, -1, reader.readIndex("direct type index"), null, -1, -1));
        return;
      case JsonMetadataFormat.TOKEN_INACCESSIBLE:
        int chunk = reader.readIndex("type-name chunk");
        int slot = reader.readIndex("type-name slot");
        tokens.add(new JsonDecodedMetadata.Token(mode, -1, -1, null, chunk, slot));
        return;
      default:
        throw reader.failure("invalid token mode " + mode);
    }
  }

  private static void readTypeParameter(
      Reader reader, List<JsonDecodedMetadata.TypeParameter> parameters) {
    requireDenseId(reader, "type parameter", parameters.size());
    parameters.add(
        new JsonDecodedMetadata.TypeParameter(
            reader.readByte("type-parameter owner kind"),
            reader.readIndex("type-parameter owner token"),
            reader.readString("type-parameter member name"),
            reader.readString("type-parameter descriptor"),
            reader.readIndex("type-parameter index"),
            reader.readIndexes("type-parameter bounds")));
  }

  private static void readTypeNode(Reader reader, List<JsonTypeNode> nodes) {
    requireDenseId(reader, "type node", nodes.size());
    int kind = reader.readByte("type-node kind");
    int codecToken = reader.readOptionalIndex("codec token");
    int codecOperation = reader.readOptionalIndex("codec operation");
    String codecSource = reader.readString("codec source");
    int token;
    int ownerNode = -1;
    int[] children = null;
    int[] lowerBounds = null;
    int variableKey = -1;
    switch (kind) {
      case JsonMetadataFormat.TYPE_PRIMITIVE:
        token = reader.readByte("primitive type-node kind");
        break;
      case JsonMetadataFormat.TYPE_DECLARED:
        token = reader.readIndex("declared raw token");
        ownerNode = reader.readOptionalIndex("declared owner node");
        children = reader.readIndexes("declared arguments");
        break;
      case JsonMetadataFormat.TYPE_ARRAY:
        token = reader.readIndex("array component node");
        break;
      case JsonMetadataFormat.TYPE_VARIABLE:
        token = -1;
        variableKey = reader.readIndex("type-variable declaration key");
        break;
      case JsonMetadataFormat.TYPE_WILDCARD:
        token = -1;
        children = reader.readIndexes("wildcard upper bounds");
        lowerBounds = reader.readIndexes("wildcard lower bounds");
        break;
      default:
        throw reader.failure("invalid type-node kind " + kind);
    }
    nodes.add(
        new JsonTypeNode(
            kind,
            codecToken,
            codecOperation,
            codecSource,
            token,
            ownerNode,
            children,
            lowerBounds,
            variableKey));
  }

  private static void readOperation(Reader reader, List<JsonDecodedMetadata.Operation> operations) {
    requireDenseId(reader, "operation", operations.size());
    int shape = reader.readByte("operation shape");
    int mode = reader.readByte("operation mode");
    int directionMask = reader.readByte("operation direction mask");
    if (mode == JsonMetadataFormat.OP_DIRECT) {
      operations.add(
          new JsonDecodedMetadata.Operation(
              shape,
              mode,
              directionMask,
              reader.readIndex("direct operation index"),
              -1,
              null,
              null));
    } else if (mode == JsonMetadataFormat.OP_HANDLE) {
      operations.add(
          new JsonDecodedMetadata.Operation(
              shape,
              mode,
              directionMask,
              -1,
              reader.readIndex("operation owner token"),
              reader.readString("operation member name"),
              reader.readString("operation descriptor")));
    } else {
      throw reader.failure("invalid operation mode " + mode);
    }
  }

  private static void readDeclaration(
      Reader reader, List<JsonDecodedMetadata.Declaration> declarations) {
    declarations.add(
        new JsonDecodedMetadata.Declaration(
            reader.readIndex("declaration type token"),
            reader.readByte("declaration kind"),
            reader.readIndex("declaration modifiers"),
            reader.readOptionalIndex("declaration superclass token"),
            reader.readIndexes("declaration interface tokens"),
            reader.readOptionalIndex("declaration codec token"),
            reader.readOptionalIndex("declaration codec operation")));
  }

  private static void readSubtypes(Reader reader, List<JsonDecodedMetadata.SubtypeTable> tables) {
    int inclusion = reader.readByte("subtype inclusion");
    String property = reader.readString("subtype property");
    int count = reader.readCount("subtype entries");
    JsonDecodedMetadata.SubtypeEntry[] entries = new JsonDecodedMetadata.SubtypeEntry[count];
    for (int i = 0; i < count; i++) {
      entries[i] =
          new JsonDecodedMetadata.SubtypeEntry(
              reader.readOptionalIndex("subtype token"),
              reader.readString("subtype class name"),
              reader.readString("subtype logical name"));
    }
    tables.add(new JsonDecodedMetadata.SubtypeTable(inclusion, property, entries));
  }

  private static void readField(Reader reader, List<JsonDecodedMetadata.Field> fields) {
    int declaringToken = reader.readIndex("field declaring token");
    String name = reader.readString("field name");
    String descriptor = reader.readString("field descriptor");
    int modifiers = reader.readIndex("field modifiers");
    int typeNode = reader.readIndex("field type node");
    int flags = reader.readIndex("field flags");
    JsonDecodedMetadata.Property property = readProperty(reader, flags);
    int operation = reader.readOptionalIndex("field operation");
    fields.add(
        new JsonDecodedMetadata.Field(
            declaringToken, name, descriptor, modifiers, typeNode, flags, property, operation));
  }

  private static void readMethod(Reader reader, List<JsonDecodedMetadata.Method> methods) {
    int declaringToken = reader.readIndex("method declaring token");
    String name = reader.readString("method name");
    String descriptor = reader.readString("method descriptor");
    int modifiers = reader.readIndex("method modifiers");
    int returnNode = reader.readIndex("method return node");
    int[] parameters = reader.readIndexes("method parameter nodes");
    int flags = reader.readIndex("method flags");
    JsonDecodedMetadata.Property property = readProperty(reader, flags);
    int operation = reader.readOptionalIndex("method operation");
    methods.add(
        new JsonDecodedMetadata.Method(
            declaringToken,
            name,
            descriptor,
            modifiers,
            returnNode,
            parameters,
            flags,
            property,
            operation));
  }

  private static void readCreator(Reader reader, List<JsonDecodedMetadata.Creator> creators) {
    int executableKind = reader.readByte("creator executable kind");
    int declaringToken = reader.readIndex("creator declaring token");
    String name = reader.readString("creator name");
    String descriptor = reader.readString("creator descriptor");
    int modifiers = reader.readIndex("creator modifiers");
    String[] names = reader.readStrings("creator property names");
    int[] parameters = reader.readIndexes("creator parameter nodes");
    int propertyCount = reader.readCount("creator parameter properties");
    if (propertyCount != parameters.length) {
      throw reader.failure(
          "creator has "
              + parameters.length
              + " parameters but "
              + propertyCount
              + " property tuples");
    }
    JsonDecodedMetadata.Property[] properties = new JsonDecodedMetadata.Property[propertyCount];
    for (int i = 0; i < propertyCount; i++) {
      boolean present = reader.readBoolean("creator parameter property presence");
      String propertyName = reader.readString("creator parameter property name");
      int propertyIndex = reader.readSigned("creator parameter property index");
      int propertyInclude = reader.readByte("creator parameter property include");
      if (!present && (!propertyName.isEmpty() || propertyIndex != -1 || propertyInclude != 0)) {
        throw reader.failure("absent creator parameter property has non-default values");
      }
      properties[i] =
          present
              ? new JsonDecodedMetadata.Property(true, propertyName, propertyIndex, propertyInclude)
              : JsonDecodedMetadata.Property.ABSENT;
    }
    int operation = reader.readIndex("creator operation");
    creators.add(
        new JsonDecodedMetadata.Creator(
            executableKind,
            declaringToken,
            name,
            descriptor,
            modifiers,
            names,
            parameters,
            properties,
            operation));
  }

  private static void readRecordComponent(
      Reader reader, List<JsonDecodedMetadata.RecordComponent> components) {
    int declaringToken = reader.readIndex("record-component declaring token");
    String name = reader.readString("record-component name");
    int typeNode = reader.readIndex("record-component type node");
    String descriptor = reader.readString("record accessor descriptor");
    int flags = reader.readIndex("record-component flags");
    components.add(
        new JsonDecodedMetadata.RecordComponent(
            declaringToken, name, typeNode, descriptor, flags, readProperty(reader, flags)));
  }

  private static void readPropertyOrder(
      Reader reader, List<JsonDecodedMetadata.PropertyOrder> orders) {
    orders.add(
        new JsonDecodedMetadata.PropertyOrder(
            reader.readIndex("property-order declaring token"),
            reader.readStrings("property-order names"),
            reader.readBoolean("property-order alphabetic")));
  }

  private static void readInstantiator(
      Reader reader, List<JsonDecodedMetadata.Instantiator> instantiators) {
    instantiators.add(
        new JsonDecodedMetadata.Instantiator(
            reader.readByte("instantiator kind"), reader.readIndex("instantiator operation")));
  }

  private static void readHierarchy(
      Reader reader, List<JsonDecodedMetadata.Hierarchy> hierarchies) {
    hierarchies.add(
        new JsonDecodedMetadata.Hierarchy(
            reader.readIndex("hierarchy declaring token"),
            reader.readOptionalIndex("hierarchy superclass node"),
            reader.readIndexes("hierarchy interface nodes")));
  }

  private static JsonDecodedMetadata.Property readProperty(Reader reader, int flags) {
    if ((flags & JsonMetadataFormat.HAS_JSON_PROPERTY) == 0) {
      return JsonDecodedMetadata.Property.ABSENT;
    }
    return readPropertyTuple(reader);
  }

  private static JsonDecodedMetadata.Property readPropertyTuple(Reader reader) {
    return new JsonDecodedMetadata.Property(
        true,
        reader.readString("property name"),
        reader.readSigned("property index"),
        reader.readByte("property include"));
  }

  private static void validate(JsonDecodedMetadata data, Class<?> target) {
    validateCounts(data, target);
    int directTypeIndex = 0;
    for (int i = 0; i < data.tokenCount(); i++) {
      JsonDecodedMetadata.Token token = data.token(i);
      if (token.mode() == JsonMetadataFormat.TOKEN_PRIMITIVE) {
        requireRange(
            data,
            token.primitiveKind(),
            JsonMetadataFormat.BOOLEAN,
            JsonMetadataFormat.VOID,
            "token " + i + " primitive kind");
      } else if (token.mode() == JsonMetadataFormat.TOKEN_DIRECT) {
        if (token.directIndex() != directTypeIndex++) {
          throw invalid(target, data.section(), "direct type indexes are not dense at token " + i);
        }
      } else {
        JsonClassNames.requireBinaryName(
            token.inaccessibleName(), metadataSource(target, data.section()));
      }
    }
    validateReferences(data, target);
  }

  private static void validateCounts(JsonDecodedMetadata data, Class<?> target) {
    if (data.subtypeTableCount() > 1) {
      throw invalid(target, data.section(), "more than one subtype table");
    }
    if (data.propertyOrderCount() > 1) {
      throw invalid(target, data.section(), "more than one property-order declaration");
    }
    if (data.instantiatorCount() > 1) {
      throw invalid(target, data.section(), "more than one instantiator");
    }
  }

  private static void validateReferences(JsonDecodedMetadata data, Class<?> target) {
    int directOperationIndex = 0;
    for (int i = 0; i < data.operationCount(); i++) {
      JsonDecodedMetadata.Operation operation = data.operation(i);
      requireRange(
          data,
          operation.shape(),
          JsonMetadataFormat.FIELD_ACCESS,
          JsonMetadataFormat.RECORD_CONSTRUCTOR,
          "operation " + i + " shape");
      if (operation.directionMask() < JsonMetadataFormat.READ
          || operation.directionMask() > (JsonMetadataFormat.READ | JsonMetadataFormat.WRITE)) {
        throw invalid(target, data.section(), "invalid direction mask at operation " + i);
      }
      if (operation.mode() == JsonMetadataFormat.OP_DIRECT) {
        if (operation.directIndex() != directOperationIndex++) {
          throw invalid(target, data.section(), "direct operation indexes are not dense at " + i);
        }
      } else {
        requireToken(data, operation.ownerToken(), "operation " + i + " owner", target);
        requireMember(operation.memberName(), data, "operation " + i + " member", target);
        requireDescriptor(
            operation.descriptor(),
            operation.shape() != JsonMetadataFormat.FIELD_ACCESS,
            data,
            "operation " + i + " descriptor",
            target);
      }
    }
    for (int i = 0; i < data.typeParameterCount(); i++) {
      JsonDecodedMetadata.TypeParameter parameter = data.typeParameter(i);
      requireRange(
          data,
          parameter.ownerKind(),
          JsonMetadataFormat.OWNER_TYPE,
          JsonMetadataFormat.OWNER_CONSTRUCTOR,
          "type parameter " + i + " owner kind");
      requireToken(data, parameter.ownerToken(), "type parameter " + i + " owner", target);
      if (parameter.ownerKind() == JsonMetadataFormat.OWNER_TYPE) {
        if (!parameter.memberName().isEmpty() || !parameter.descriptor().isEmpty()) {
          throw invalid(target, data.section(), "type-owned parameter " + i + " has a member key");
        }
      } else {
        requireMember(parameter.memberName(), data, "type parameter " + i + " member", target);
        requireDescriptor(
            parameter.descriptor(), true, data, "type parameter " + i + " descriptor", target);
      }
      for (int j = 0; j < parameter.boundCount(); j++) {
        requireTypeNode(data, parameter.bound(j), "type parameter " + i + " bound", target);
      }
    }
    for (int i = 0; i < data.typeNodeCount(); i++) {
      validateTypeNode(data, data.typeNode(i), i, target);
    }
    for (int i = 0; i < data.declarationCount(); i++) {
      JsonDecodedMetadata.Declaration declaration = data.declaration(i);
      requireToken(data, declaration.typeToken(), "declaration " + i + " type", target);
      requireRange(
          data,
          declaration.kind(),
          JsonMetadataFormat.DECL_CLASS,
          JsonMetadataFormat.DECL_ABSTRACT,
          "declaration " + i + " kind");
      requireOptionalToken(
          data, declaration.superclassToken(), "declaration " + i + " superclass", target);
      for (int j = 0; j < declaration.interfaceCount(); j++) {
        requireToken(
            data, declaration.interfaceToken(j), "declaration " + i + " interface", target);
      }
      requireCodecPair(
          data, declaration.codecToken(), declaration.codecOperation(), "declaration " + i, target);
    }
    if (data.subtypeTableCount() == 1) {
      validateSubtypes(data, data.subtypeTable(0), target);
    }
    for (int i = 0; i < data.fieldCount(); i++) {
      JsonDecodedMetadata.Field field = data.field(i);
      requireToken(data, field.declaringToken(), "field " + i + " declaring type", target);
      requireMember(field.name(), data, "field " + i + " name", target);
      requireDescriptor(field.descriptor(), false, data, "field " + i + " descriptor", target);
      requireTypeNode(data, field.typeNode(), "field " + i + " type", target);
      validateFlags(data, field.flags(), field.property(), "field " + i, target);
      requireOptionalOperation(data, field.operation(), "field " + i, target);
    }
    for (int i = 0; i < data.methodCount(); i++) {
      JsonDecodedMetadata.Method method = data.method(i);
      requireToken(data, method.declaringToken(), "method " + i + " declaring type", target);
      requireMember(method.name(), data, "method " + i + " name", target);
      requireDescriptor(method.descriptor(), true, data, "method " + i + " descriptor", target);
      requireTypeNode(data, method.returnNode(), "method " + i + " return", target);
      for (int j = 0; j < method.parameterCount(); j++) {
        requireTypeNode(data, method.parameterNode(j), "method " + i + " parameter", target);
      }
      validateFlags(data, method.flags(), method.property(), "method " + i, target);
      requireOptionalOperation(data, method.operation(), "method " + i, target);
    }
    for (int i = 0; i < data.creatorCount(); i++) {
      validateCreator(data, data.creator(i), i, target);
    }
    for (int i = 0; i < data.recordComponentCount(); i++) {
      JsonDecodedMetadata.RecordComponent component = data.recordComponent(i);
      requireToken(data, component.declaringToken(), "record component " + i + " owner", target);
      requireMember(component.name(), data, "record component " + i + " name", target);
      requireTypeNode(data, component.typeNode(), "record component " + i + " type", target);
      requireDescriptor(
          component.accessorDescriptor(),
          true,
          data,
          "record component " + i + " descriptor",
          target);
      validateFlags(data, component.flags(), component.property(), "record component " + i, target);
    }
    if (data.propertyOrderCount() == 1) {
      JsonDecodedMetadata.PropertyOrder order = data.propertyOrder(0);
      requireToken(data, order.declaringToken(), "property order owner", target);
      for (int i = 0; i < order.nameCount(); i++) {
        if (order.name(i).isEmpty()) {
          throw invalid(target, data.section(), "empty property-order name at " + i);
        }
      }
    }
    if (data.instantiatorCount() == 1) {
      JsonDecodedMetadata.Instantiator instantiator = data.instantiator(0);
      requireRange(
          data,
          instantiator.kind(),
          JsonMetadataFormat.INSTANTIATOR_NO_ARG,
          JsonMetadataFormat.INSTANTIATOR_RECORD,
          "instantiator kind");
      requireOperation(data, instantiator.operation(), "instantiator", target);
    }
    for (int i = 0; i < data.hierarchyCount(); i++) {
      JsonDecodedMetadata.Hierarchy hierarchy = data.hierarchy(i);
      requireToken(data, hierarchy.declaringToken(), "hierarchy " + i + " declaration", target);
      requireOptionalTypeNode(
          data, hierarchy.superclassNode(), "hierarchy " + i + " superclass", target);
      for (int j = 0; j < hierarchy.interfaceCount(); j++) {
        requireTypeNode(data, hierarchy.interfaceNode(j), "hierarchy " + i + " interface", target);
      }
    }
  }

  private static void validateTypeNode(
      JsonDecodedMetadata data, JsonTypeNode node, int index, Class<?> target) {
    requireCodecPair(data, node.codecToken(), node.codecOperation(), "type node " + index, target);
    if (node.hasCodec() != !node.codecSource().isEmpty()) {
      throw invalid(
          target, data.section(), "type node " + index + " has inconsistent codec source");
    }
    switch (node.kind()) {
      case JsonMetadataFormat.TYPE_PRIMITIVE:
        requireRange(
            data,
            node.token(),
            JsonMetadataFormat.BOOLEAN,
            JsonMetadataFormat.VOID,
            "type node " + index + " primitive kind");
        break;
      case JsonMetadataFormat.TYPE_DECLARED:
        requireToken(data, node.token(), "type node " + index + " raw type", target);
        requireOptionalTypeNode(data, node.ownerNode(), "type node " + index + " owner", target);
        validateChildren(data, node, index, target);
        break;
      case JsonMetadataFormat.TYPE_ARRAY:
        requireTypeNode(data, node.token(), "type node " + index + " component", target);
        break;
      case JsonMetadataFormat.TYPE_VARIABLE:
        if (node.variableKey() < 0 || node.variableKey() >= data.typeParameterCount()) {
          throw invalid(target, data.section(), "invalid variable key at type node " + index);
        }
        break;
      case JsonMetadataFormat.TYPE_WILDCARD:
        validateChildren(data, node, index, target);
        for (int j = 0; j < node.lowerBoundCount(); j++) {
          requireTypeNode(data, node.lowerBound(j), "type node " + index + " lower bound", target);
        }
        if (node.lowerBoundCount() > 1) {
          throw invalid(
              target, data.section(), "wildcard type node " + index + " has multiple lower bounds");
        }
        break;
      default:
        throw invalid(target, data.section(), "invalid kind at type node " + index);
    }
  }

  private static void validateChildren(
      JsonDecodedMetadata data, JsonTypeNode node, int index, Class<?> target) {
    for (int j = 0; j < node.childCount(); j++) {
      requireTypeNode(data, node.child(j), "type node " + index + " child", target);
    }
  }

  private static void validateSubtypes(
      JsonDecodedMetadata data, JsonDecodedMetadata.SubtypeTable table, Class<?> target) {
    requireRange(data, table.inclusion(), 0, 2, "subtype inclusion");
    if ((table.inclusion() == 0) != !table.property().isEmpty()) {
      throw invalid(target, data.section(), "subtype property does not match inclusion");
    }
    if (table.entryCount() == 0) {
      throw invalid(target, data.section(), "empty subtype table");
    }
    for (int i = 0; i < table.entryCount(); i++) {
      JsonDecodedMetadata.SubtypeEntry entry = table.entry(i);
      boolean hasToken = entry.typeToken() >= 0;
      boolean hasName = !entry.className().isEmpty();
      if (hasToken == hasName) {
        throw invalid(
            target, data.section(), "subtype entry " + i + " must have one type reference");
      }
      if (hasToken) {
        requireToken(data, entry.typeToken(), "subtype entry " + i, target);
      } else {
        JsonClassNames.requireBinaryName(entry.className(), metadataSource(target, data.section()));
      }
      if (entry.name().isEmpty()) {
        throw invalid(target, data.section(), "subtype entry " + i + " has an empty logical name");
      }
    }
  }

  private static void validateCreator(
      JsonDecodedMetadata data, JsonDecodedMetadata.Creator creator, int index, Class<?> target) {
    requireRange(
        data,
        creator.executableKind(),
        JsonMetadataFormat.CREATOR_CONSTRUCTOR,
        JsonMetadataFormat.CREATOR_FACTORY,
        "creator " + index + " kind");
    requireToken(data, creator.declaringToken(), "creator " + index + " declaring type", target);
    requireMember(creator.name(), data, "creator " + index + " name", target);
    requireDescriptor(creator.descriptor(), true, data, "creator " + index + " descriptor", target);
    requireOperation(data, creator.operation(), "creator " + index, target);
    if (creator.propertyNameCount() != 0
        && creator.propertyNameCount() != creator.parameterCount()) {
      throw invalid(target, data.section(), "creator " + index + " property-name count mismatch");
    }
    for (int i = 0; i < creator.parameterCount(); i++) {
      requireTypeNode(data, creator.parameterNode(i), "creator " + index + " parameter", target);
      validateProperty(
          data, creator.parameterProperty(i), "creator " + index + " parameter " + i, target);
    }
  }

  private static void validateFlags(
      JsonDecodedMetadata data,
      int flags,
      JsonDecodedMetadata.Property property,
      String source,
      Class<?> target) {
    if ((flags & ~ALL_FLAGS) != 0) {
      throw invalid(target, data.section(), source + " has unknown flags " + flags);
    }
    if ((flags & JsonMetadataFormat.HAS_JSON_IGNORE) == 0
        && (flags & (JsonMetadataFormat.IGNORE_READ | JsonMetadataFormat.IGNORE_WRITE)) != 0) {
      throw invalid(target, data.section(), source + " has ignore values without JsonIgnore");
    }
    if (((flags & JsonMetadataFormat.HAS_JSON_PROPERTY) != 0) != property.present()) {
      throw invalid(target, data.section(), source + " has inconsistent JsonProperty values");
    }
    validateProperty(data, property, source, target);
  }

  private static void validateProperty(
      JsonDecodedMetadata data,
      JsonDecodedMetadata.Property property,
      String source,
      Class<?> target) {
    if (!property.present()) {
      return;
    }
    if (property.index() < -1) {
      throw invalid(
          target, data.section(), source + " has invalid property index " + property.index());
    }
    requireRange(data, property.include(), 0, 2, source + " property include");
  }

  private static void requireCodecPair(
      JsonDecodedMetadata data, int token, int operation, String source, Class<?> target) {
    if ((token >= 0) != (operation >= 0)) {
      throw invalid(target, data.section(), source + " has an incomplete codec reference");
    }
    if (token >= 0) {
      requireToken(data, token, source + " codec", target);
      requireOperation(data, operation, source + " codec factory", target);
      if (data.operation(operation).shape() != JsonMetadataFormat.CODEC_FACTORY) {
        throw invalid(target, data.section(), source + " codec operation has the wrong shape");
      }
    }
  }

  private static void requireOptionalToken(
      JsonDecodedMetadata data, int index, String source, Class<?> target) {
    if (index >= 0) {
      requireToken(data, index, source, target);
    }
  }

  private static void requireToken(
      JsonDecodedMetadata data, int index, String source, Class<?> target) {
    if (index < 0 || index >= data.tokenCount()) {
      throw invalid(target, data.section(), source + " token index " + index + " is out of bounds");
    }
  }

  private static void requireOptionalTypeNode(
      JsonDecodedMetadata data, int index, String source, Class<?> target) {
    if (index >= 0) {
      requireTypeNode(data, index, source, target);
    }
  }

  private static void requireTypeNode(
      JsonDecodedMetadata data, int index, String source, Class<?> target) {
    if (index < 0 || index >= data.typeNodeCount()) {
      throw invalid(
          target, data.section(), source + " type-node index " + index + " is out of bounds");
    }
  }

  private static void requireOptionalOperation(
      JsonDecodedMetadata data, int index, String source, Class<?> target) {
    if (index >= 0) {
      requireOperation(data, index, source, target);
    }
  }

  private static void requireOperation(
      JsonDecodedMetadata data, int index, String source, Class<?> target) {
    if (index < 0 || index >= data.operationCount()) {
      throw invalid(
          target, data.section(), source + " operation index " + index + " is out of bounds");
    }
  }

  private static void requireRange(
      JsonDecodedMetadata data, int value, int min, int max, String source) {
    if (value < min || value > max) {
      throw new ForyJsonException(
          "Invalid generated JSON metadata section "
              + data.section()
              + ": "
              + source
              + " value "
              + value
              + " is out of range");
    }
  }

  private static void requireMember(
      String value, JsonDecodedMetadata data, String source, Class<?> target) {
    if (value == null || value.isEmpty()) {
      throw invalid(target, data.section(), source + " is empty");
    }
  }

  private static void requireDescriptor(
      String descriptor, boolean method, JsonDecodedMetadata data, String source, Class<?> target) {
    if (!Descriptors.valid(descriptor, method)) {
      throw invalid(target, data.section(), source + " is invalid: " + descriptor);
    }
  }

  private static void validateTag(Reader reader, int section, int tag) {
    boolean valid;
    if (section == JsonTypeMetadata.DECLARATIONS) {
      valid =
          tag == JsonMetadataFormat.TOKEN
              || tag == JsonMetadataFormat.OPERATION
              || tag == JsonMetadataFormat.DECLARATION;
    } else if (section == JsonTypeMetadata.SUBTYPES) {
      valid = tag == JsonMetadataFormat.TOKEN || tag == JsonMetadataFormat.SUBTYPE_TABLE;
    } else {
      valid = tag != JsonMetadataFormat.SUBTYPE_TABLE;
    }
    if (!valid || tag < JsonMetadataFormat.TOKEN || tag > JsonMetadataFormat.HIERARCHY) {
      throw reader.failure("tag " + tag + " is invalid for section " + section);
    }
  }

  private static void requireDenseId(Reader reader, String kind, int expected) {
    int actual = reader.readIndex(kind + " id");
    if (actual != expected) {
      throw reader.failure(kind + " id " + actual + " is not dense; expected " + expected);
    }
  }

  private static void requireByte(Reader reader, int expected, String source) {
    int actual = reader.readByte(source);
    if (actual != expected) {
      throw reader.failure(source + " is " + actual + ", expected " + expected);
    }
  }

  private static String metadataSource(Class<?> target, int section) {
    return "generated JSON metadata section " + section + " for " + target.getName();
  }

  private static ForyJsonException invalid(Class<?> target, int section, String reason) {
    return new ForyJsonException(
        "Invalid generated JSON metadata section "
            + section
            + " for "
            + target.getName()
            + ": "
            + reason
            + ". Align fory-json and fory-annotation-processor and recompile the model.");
  }

  private static final class Lists {
    private final List<JsonDecodedMetadata.Token> tokens = new ArrayList<>();
    private final List<JsonDecodedMetadata.TypeParameter> typeParameters = new ArrayList<>();
    private final List<JsonTypeNode> typeNodes = new ArrayList<>();
    private final List<JsonDecodedMetadata.Operation> operations = new ArrayList<>();
    private final List<JsonDecodedMetadata.Declaration> declarations = new ArrayList<>();
    private final List<JsonDecodedMetadata.SubtypeTable> subtypes = new ArrayList<>();
    private final List<JsonDecodedMetadata.Field> fields = new ArrayList<>();
    private final List<JsonDecodedMetadata.Method> methods = new ArrayList<>();
    private final List<JsonDecodedMetadata.Creator> creators = new ArrayList<>();
    private final List<JsonDecodedMetadata.RecordComponent> recordComponents = new ArrayList<>();
    private final List<JsonDecodedMetadata.PropertyOrder> propertyOrders = new ArrayList<>();
    private final List<JsonDecodedMetadata.Instantiator> instantiators = new ArrayList<>();
    private final List<JsonDecodedMetadata.Hierarchy> hierarchies = new ArrayList<>();

    private JsonDecodedMetadata build(int section) {
      return new JsonDecodedMetadata(
          section,
          tokens.toArray(new JsonDecodedMetadata.Token[0]),
          typeParameters.toArray(new JsonDecodedMetadata.TypeParameter[0]),
          typeNodes.toArray(new JsonTypeNode[0]),
          operations.toArray(new JsonDecodedMetadata.Operation[0]),
          declarations.toArray(new JsonDecodedMetadata.Declaration[0]),
          subtypes.toArray(new JsonDecodedMetadata.SubtypeTable[0]),
          fields.toArray(new JsonDecodedMetadata.Field[0]),
          methods.toArray(new JsonDecodedMetadata.Method[0]),
          creators.toArray(new JsonDecodedMetadata.Creator[0]),
          recordComponents.toArray(new JsonDecodedMetadata.RecordComponent[0]),
          propertyOrders.toArray(new JsonDecodedMetadata.PropertyOrder[0]),
          instantiators.toArray(new JsonDecodedMetadata.Instantiator[0]),
          hierarchies.toArray(new JsonDecodedMetadata.Hierarchy[0]));
    }

    private void resolveNames(String[][] names, Class<?> target, int section) {
      if (names == null) {
        throw invalid(target, section, "inaccessible type-name chunks are null");
      }
      boolean[][] used = new boolean[names.length][];
      for (int chunk = 0; chunk < names.length; chunk++) {
        if (names[chunk] == null) {
          throw invalid(target, section, "inaccessible type-name chunk " + chunk + " is null");
        }
        used[chunk] = new boolean[names[chunk].length];
        for (int slot = 0; slot < names[chunk].length; slot++) {
          if (names[chunk][slot] == null) {
            throw invalid(
                target, section, "inaccessible type name " + chunk + ":" + slot + " is null");
          }
        }
      }
      for (int index = 0; index < tokens.size(); index++) {
        JsonDecodedMetadata.Token token = tokens.get(index);
        if (token.mode() != JsonMetadataFormat.TOKEN_INACCESSIBLE) {
          continue;
        }
        int chunk = token.nameChunk();
        int slot = token.nameSlot();
        if (chunk < 0
            || chunk >= used.length
            || slot < 0
            || slot >= used[chunk].length
            || used[chunk][slot]) {
          throw invalid(
              target, section, "invalid or duplicate inaccessible name at token " + index);
        }
        String name = names[chunk][slot];
        JsonClassNames.requireBinaryName(name, metadataSource(target, section));
        used[chunk][slot] = true;
        tokens.set(
            index,
            new JsonDecodedMetadata.Token(
                JsonMetadataFormat.TOKEN_INACCESSIBLE, -1, -1, name, -1, -1));
      }
      for (int chunk = 0; chunk < used.length; chunk++) {
        for (int slot = 0; slot < used[chunk].length; slot++) {
          if (!used[chunk][slot]) {
            throw invalid(target, section, "unused inaccessible type name " + chunk + ":" + slot);
          }
        }
      }
    }
  }

  private static final class Reader {
    private final Class<?> target;
    private final int section;
    private final byte[][] chunks;
    private final int total;
    private int chunk;
    private int offset;
    private int position;
    private int limit;

    private Reader(Class<?> target, int section, byte[][] chunks) {
      this.target = target;
      this.section = section;
      if (chunks == null) {
        throw invalid(target, section, "fact chunks are null");
      }
      long length = 0;
      for (int i = 0; i < chunks.length; i++) {
        if (chunks[i] == null) {
          throw invalid(target, section, "fact chunk " + i + " is null");
        }
        length += chunks[i].length;
        if (length > Integer.MAX_VALUE) {
          throw invalid(target, section, "fact stream exceeds the supported size");
        }
      }
      this.chunks = chunks;
      total = (int) length;
      limit = total;
    }

    private int remaining() {
      return limit - position;
    }

    private int readByte(String source) {
      if (position >= limit) {
        throw failure("truncated " + source);
      }
      while (chunk < chunks.length && offset == chunks[chunk].length) {
        chunk++;
        offset = 0;
      }
      if (chunk == chunks.length) {
        throw failure("truncated " + source);
      }
      position++;
      return chunks[chunk][offset++] & 0xff;
    }

    private boolean readBoolean(String source) {
      int value = readByte(source);
      if (value > 1) {
        throw failure(source + " must be 0 or 1");
      }
      return value == 1;
    }

    private int readIndex(String source) {
      long value = readUnsigned(source);
      if (value > Integer.MAX_VALUE) {
        throw failure(source + " exceeds the supported index range");
      }
      return (int) value;
    }

    private int readCount(String source) {
      int count = readIndex(source + " count");
      if (count > remaining()) {
        throw failure(source + " count " + count + " exceeds remaining bytes");
      }
      return count;
    }

    private int readOptionalIndex(String source) {
      int value = readSigned(source);
      if (value < -1) {
        throw failure(source + " must be -1 or a non-negative index");
      }
      return value;
    }

    private int readSigned(String source) {
      long value = readUnsigned(source);
      return (int) ((value >>> 1) ^ -(value & 1));
    }

    private long readUnsigned(String source) {
      long value = 0;
      int shift = 0;
      for (int i = 0; i < 5; i++) {
        int current = readByte(source);
        if (i == 4 && (current & 0xf0) != 0) {
          throw failure(source + " unsigned LEB128 overflows 32 bits");
        }
        value |= (long) (current & 0x7f) << shift;
        if ((current & 0x80) == 0) {
          if (i != 0 && (current & 0x7f) == 0) {
            throw failure(source + " uses non-canonical unsigned LEB128");
          }
          return value;
        }
        shift += 7;
      }
      throw failure(source + " unsigned LEB128 is too long");
    }

    private int[] readIndexes(String source) {
      int count = readCount(source);
      int[] values = new int[count];
      for (int i = 0; i < count; i++) {
        values[i] = readIndex(source + "[" + i + "]");
      }
      return values;
    }

    private String[] readStrings(String source) {
      int count = readCount(source);
      String[] values = new String[count];
      for (int i = 0; i < count; i++) {
        values[i] = readString(source + "[" + i + "]");
      }
      return values;
    }

    private String readString(String source) {
      int length = readIndex(source + " UTF-8 length");
      if (length > remaining()) {
        throw failure(source + " length " + length + " exceeds remaining bytes");
      }
      byte[] bytes = new byte[length];
      for (int i = 0; i < length; i++) {
        bytes[i] = (byte) readByte(source);
      }
      try {
        CharBuffer chars =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return chars.toString();
      } catch (CharacterCodingException e) {
        throw new ForyJsonException(failureMessage("invalid UTF-8 in " + source), e);
      }
    }

    private int enter(int length, String source) {
      if (length > remaining()) {
        throw failure(source + " length " + length + " exceeds remaining bytes");
      }
      int previousLimit = limit;
      limit = position + length;
      return previousLimit;
    }

    private void leave(int previousLimit, String source) {
      if (position != limit) {
        throw failure(source + " has " + (limit - position) + " unread body bytes");
      }
      limit = previousLimit;
    }

    private ForyJsonException failure(String reason) {
      return new ForyJsonException(failureMessage(reason));
    }

    private String failureMessage(String reason) {
      return "Invalid generated JSON metadata section "
          + section
          + " for "
          + target.getName()
          + " at byte "
          + position
          + " of "
          + total
          + ": "
          + reason
          + ". Align fory-json and fory-annotation-processor and recompile the model.";
    }
  }

  private static final class Descriptors {
    private static boolean valid(String descriptor, boolean method) {
      if (descriptor == null || descriptor.isEmpty()) {
        return false;
      }
      int end = method ? method(descriptor) : type(descriptor, 0, false);
      return end == descriptor.length();
    }

    private static int method(String value) {
      if (value.charAt(0) != '(') {
        return -1;
      }
      int offset = 1;
      while (offset < value.length() && value.charAt(offset) != ')') {
        offset = type(value, offset, false);
        if (offset < 0) {
          return -1;
        }
      }
      if (offset >= value.length()) {
        return -1;
      }
      return type(value, offset + 1, true);
    }

    private static int type(String value, int offset, boolean allowVoid) {
      if (offset >= value.length()) {
        return -1;
      }
      char kind = value.charAt(offset++);
      if (kind == 'V') {
        return allowVoid ? offset : -1;
      }
      if (kind == 'Z'
          || kind == 'B'
          || kind == 'S'
          || kind == 'I'
          || kind == 'J'
          || kind == 'F'
          || kind == 'D'
          || kind == 'C') {
        return offset;
      }
      if (kind == '[') {
        return type(value, offset, false);
      }
      if (kind != 'L') {
        return -1;
      }
      int start = offset;
      while (offset < value.length() && value.charAt(offset) != ';') {
        char c = value.charAt(offset++);
        if (c == '.' || c == '[' || c == '(' || c == ')') {
          return -1;
        }
      }
      return offset == start || offset == value.length() ? -1 : offset + 1;
    }
  }
}
