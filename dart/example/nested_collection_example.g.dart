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

part of 'nested_collection_example.dart';

// **************************************************************************
// ForyObjSpecGenerator
// **************************************************************************

final $NestedObject = TypeSpec(
  NestedObject,
  false,
  false,
  [
    FieldSpec(
      'map',
      FieldTypeSpec(
        Map,
        ObjType.MAP,
        false,
        false,
        null,
        const [
          FieldTypeSpec(
            double,
            ObjType.FLOAT64,
            false,
            true,
            null,
            const [],
          ),
          FieldTypeSpec(
            Map,
            ObjType.MAP,
            false,
            false,
            null,
            const [
              FieldTypeSpec(
                String,
                ObjType.STRING,
                false,
                true,
                null,
                const [],
              ),
              FieldTypeSpec(
                int,
                ObjType.INT64,
                false,
                true,
                null,
                const [],
              ),
            ],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as NestedObject).map,
      (Object inst, var v) => (inst as NestedObject).map =
          Map<double, Map<String, int>>.of((v as Map).map((k, v) => MapEntry(
              (k as double),
              Map<String, int>.of((v as Map)
                  .map((k, v) => MapEntry((k as String), (v as int))))))),
    ),
    FieldSpec(
      'name',
      FieldTypeSpec(
        String,
        ObjType.STRING,
        false,
        true,
        null,
        const [],
      ),
      true,
      true,
      (Object inst) => (inst as NestedObject).name,
      (Object inst, var v) => (inst as NestedObject).name = (v as String),
    ),
    FieldSpec(
      'names',
      FieldTypeSpec(
        List,
        ObjType.LIST,
        false,
        false,
        null,
        const [
          FieldTypeSpec(
            String,
            ObjType.STRING,
            false,
            true,
            null,
            const [],
          ),
        ],
      ),
      true,
      true,
      (Object inst) => (inst as NestedObject).names,
      (Object inst, var v) => (inst as NestedObject).names =
          List<String>.of((v as List).map((v) => (v as String))),
    ),
  ],
  null,
  () => NestedObject(),
);
