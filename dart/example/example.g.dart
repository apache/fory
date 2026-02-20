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

part of 'example.dart';

// **************************************************************************
// ForyObjSpecGenerator
// **************************************************************************

final $Person = TypeSpec(
  Person,
  false,
  true,
  [
    FieldSpec(
      'age',
      FieldTypeSpec(
        int,
        ObjType.INT64,
        false,
        true,
        null,
        const [],
      ),
      true,
      true,
      (Object inst) => (inst as Person).age,
      null,
    ),
    FieldSpec(
      'dateOfBirth',
      FieldTypeSpec(
        LocalDate,
        ObjType.DATE,
        false,
        true,
        null,
        const [],
      ),
      true,
      true,
      (Object inst) => (inst as Person).dateOfBirth,
      null,
    ),
    FieldSpec(
      'firstName',
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
      (Object inst) => (inst as Person).firstName,
      null,
    ),
    FieldSpec(
      'lastName',
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
      (Object inst) => (inst as Person).lastName,
      null,
    ),
  ],
  (List<dynamic> objList) => Person(
    (objList[2] as String),
    (objList[3] as String),
    (objList[0] as int),
    (objList[1] as LocalDate),
  ),
  null,
);
