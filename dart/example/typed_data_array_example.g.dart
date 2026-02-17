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

// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'typed_data_array_example.dart';

// **************************************************************************
// ForyObjSpecGenerator
// **************************************************************************

final $TypedDataArrayExample = TypeSpec(
  TypedDataArrayExample,
  false,
  true,
  [
    FieldSpec(
      'bools',
      FieldTypeSpec(
        BoolList,
        ObjType.BOOL_ARRAY,
        false,
        true,
        null,
        const [],
      ),
      true,
      true,
      (Object inst) => (inst as TypedDataArrayExample).bools,
      (Object inst, var v) =>
          (inst as TypedDataArrayExample).bools = (v as BoolList),
    ),
    FieldSpec(
      'bytes',
      FieldTypeSpec(
        Uint8List,
        ObjType.BINARY,
        false,
        true,
        null,
        const [],
      ),
      true,
      true,
      (Object inst) => (inst as TypedDataArrayExample).bytes,
      (Object inst, var v) =>
          (inst as TypedDataArrayExample).bytes = (v as Uint8List),
    ),
    FieldSpec(
      'nums',
      FieldTypeSpec(
        Int32List,
        ObjType.INT32_ARRAY,
        false,
        true,
        null,
        const [],
      ),
      true,
      true,
      (Object inst) => (inst as TypedDataArrayExample).nums,
      (Object inst, var v) =>
          (inst as TypedDataArrayExample).nums = (v as Int32List),
    ),
  ],
  null,
  () => TypedDataArrayExample(),
);
