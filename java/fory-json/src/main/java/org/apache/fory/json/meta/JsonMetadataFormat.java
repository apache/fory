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

import org.apache.fory.annotation.Internal;

/**
 * Versioned compact fact format emitted by {@code ForyJsonProcessor}.
 *
 * <p>The byte chunks form one logical stream and must be consumed without concatenating them. The
 * stream starts with the four bytes {@code FJMD}, an unsigned LEB128 format version, one unsigned
 * section byte, and an unsigned LEB128 record count. Each record is a TLV: one unsigned tag byte,
 * an unsigned LEB128 body length, and exactly that many body bytes. Integers called {@code index}
 * or {@code count} below are unsigned LEB128. Optional indexes and {@code JsonProperty.index} are
 * zig-zag signed LEB128. Booleans and enum/tag values are one unsigned byte. Strings are a UTF-8
 * byte length followed by those bytes. Lists are a count followed by their elements.
 *
 * <p>Every section has independent, dense token, type-node, declaration-key, and operation index
 * spaces. {@link JsonTypeMetadata#metadataType(int, int)} accepts a direct token's recorded direct
 * index. Inaccessible token names are addressed by the recorded chunk and slot in {@link
 * JsonTypeMetadataData#inaccessibleTypeNames()}. {@link JsonTypeMetadata#metadataOperation(int,
 * int)} is requested only for a direct operation after its semantic owner validates the selected
 * candidate. Handle operations contain an exact cold member recipe and never request generated
 * operation code.
 *
 * <p>Record bodies are fixed for this format version:
 *
 * <ul>
 *   <li>{@link #TOKEN}: id, mode, then primitive-kind; direct-index; or name-chunk/name-slot.
 *   <li>{@link #TYPE_PARAMETER}: id, owner-kind, owner-token, member-name, JVM descriptor,
 *       parameter-index, bound-node list.
 *   <li>{@link #TYPE_NODE}: id, node-kind, optional codec-token, optional codec-operation, codec
 *       source, followed by the node-kind body. Primitive: primitive-kind. Declared: raw-token,
 *       optional owner-node, argument-node list. Array: component-node. Variable: declaration-key.
 *       Wildcard: upper-node list, lower-node list.
 *   <li>{@link #OPERATION}: id, shape, mode, direction-mask. Direct: generated-operation index.
 *       Handle: owner-token, member-name, JVM descriptor.
 *   <li>{@link #DECLARATION}: type-token, declaration-kind, modifiers, optional superclass-token,
 *       interface-token list, optional codec-token, optional codec-operation.
 *   <li>{@link #SUBTYPE_TABLE}: inclusion, property, entry count, then per entry
 *       token-or-minus-one, class-name, and logical name.
 *   <li>{@link #FIELD}: declaring-token, name, JVM descriptor, modifiers, type-node, flags,
 *       property tuple, optional access-operation.
 *   <li>{@link #METHOD}: declaring-token, name, descriptor, modifiers, return-node, parameter-node
 *       list, flags, optional property-name, property-index, property-include, optional operation.
 *   <li>{@link #CREATOR}: executable-kind, declaring-token, name, descriptor, modifiers,
 *       creator-name list, parameter-node list, per-parameter property tuples, operation.
 *   <li>{@link #RECORD_COMPONENT}: declaring-token, name, type-node, accessor descriptor, flags,
 *       optional property-name, property-index, property-include.
 *   <li>{@link #PROPERTY_ORDER}: declaring-token, property-name list, alphabetic.
 *   <li>{@link #INSTANTIATOR}: instantiator-kind, operation.
 *   <li>{@link #HIERARCHY}: declaring-token, optional generic-superclass type-node, generic
 *       interface type-node list.
 * </ul>
 *
 * <p>The generated ABI remains stable across format changes; changing any body above requires an
 * ABI and format version bump and no legacy decoder.
 */
@Internal
public final class JsonMetadataFormat {
  private JsonMetadataFormat() {}

  public static final int MAGIC_0 = 'F';
  public static final int MAGIC_1 = 'J';
  public static final int MAGIC_2 = 'M';
  public static final int MAGIC_3 = 'D';
  public static final int VERSION = 1;

  public static final int TOKEN = 1;
  public static final int TYPE_PARAMETER = 2;
  public static final int TYPE_NODE = 3;
  public static final int OPERATION = 4;
  public static final int DECLARATION = 5;
  public static final int SUBTYPE_TABLE = 6;
  public static final int FIELD = 7;
  public static final int METHOD = 8;
  public static final int CREATOR = 9;
  public static final int RECORD_COMPONENT = 10;
  public static final int PROPERTY_ORDER = 11;
  public static final int INSTANTIATOR = 12;
  public static final int HIERARCHY = 13;

  public static final int TOKEN_PRIMITIVE = 0;
  public static final int TOKEN_DIRECT = 1;
  public static final int TOKEN_INACCESSIBLE = 2;

  public static final int TYPE_PRIMITIVE = 0;
  public static final int TYPE_DECLARED = 1;
  public static final int TYPE_ARRAY = 2;
  public static final int TYPE_VARIABLE = 3;
  public static final int TYPE_WILDCARD = 4;

  public static final int BOOLEAN = 0;
  public static final int BYTE = 1;
  public static final int SHORT = 2;
  public static final int INT = 3;
  public static final int LONG = 4;
  public static final int FLOAT = 5;
  public static final int DOUBLE = 6;
  public static final int CHAR = 7;
  public static final int VOID = 8;

  public static final int OP_DIRECT = 0;
  public static final int OP_HANDLE = 1;

  public static final int FIELD_ACCESS = 0;
  public static final int GETTER = 1;
  public static final int SETTER = 2;
  public static final int CREATOR_CALL = 3;
  public static final int ANY_SETTER = 4;
  public static final int CODEC_FACTORY = 5;
  public static final int NO_ARG_CONSTRUCTOR = 6;
  public static final int RECORD_CONSTRUCTOR = 7;

  public static final int READ = 1;
  public static final int WRITE = 2;

  public static final int DECL_CLASS = 0;
  public static final int DECL_INTERFACE = 1;
  public static final int DECL_ENUM = 2;
  public static final int DECL_RECORD = 3;
  public static final int DECL_ABSTRACT = 4;

  public static final int OWNER_TYPE = 0;
  public static final int OWNER_METHOD = 1;
  public static final int OWNER_CONSTRUCTOR = 2;

  public static final int CREATOR_CONSTRUCTOR = 0;
  public static final int CREATOR_FACTORY = 1;

  public static final int INSTANTIATOR_NO_ARG = 0;
  public static final int INSTANTIATOR_RECORD = 1;

  public static final int HAS_JSON_PROPERTY = 1;
  public static final int HAS_JSON_IGNORE = 1 << 1;
  public static final int IGNORE_READ = 1 << 2;
  public static final int IGNORE_WRITE = 1 << 3;
  public static final int JSON_ANY_PROPERTY = 1 << 4;
  public static final int JSON_ANY_GETTER = 1 << 5;
  public static final int JSON_ANY_SETTER = 1 << 6;
  public static final int READ_ELIGIBLE = 1 << 7;
  public static final int WRITE_ELIGIBLE = 1 << 8;
  public static final int SYNTHETIC = 1 << 9;
  public static final int BRIDGE = 1 << 10;
  public static final int VARARGS = 1 << 11;
}
