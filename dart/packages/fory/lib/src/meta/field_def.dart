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

const int kTagIdUseFieldName = -1;

class FieldDef {
  final String name;
  final int tagId;
  final bool nullable;
  final bool trackingRef;
  final RemoteFieldType fieldType;

  const FieldDef({
    required this.name,
    required this.tagId,
    required this.nullable,
    required this.trackingRef,
    required this.fieldType,
  });
}

/// Type of a field as read from TypeDef (supports nested LIST/SET/MAP).
abstract base class RemoteFieldType {
  const RemoteFieldType();

  int get typeId;
}

/// Simple (non-collection) field type.
final class SimpleRemoteFieldType extends RemoteFieldType {
  @override
  final int typeId;

  const SimpleRemoteFieldType(this.typeId);
}

/// LIST or SET: typeId is LIST.id or SET.id, elementType is the element type.
final class ListSetRemoteFieldType extends RemoteFieldType {
  @override
  final int typeId;
  final RemoteFieldType elementType;

  const ListSetRemoteFieldType(this.typeId, this.elementType);
}

/// MAP: typeId is MAP.id, keyType and valueType are the key/value types.
final class MapRemoteFieldType extends RemoteFieldType {
  @override
  final int typeId;
  final RemoteFieldType keyType;
  final RemoteFieldType valueType;

  const MapRemoteFieldType(this.typeId, this.keyType, this.valueType);
}
