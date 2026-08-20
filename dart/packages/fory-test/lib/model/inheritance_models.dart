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

library;

import 'package:fory/fory.dart';
import 'package:inheritance_test_models/base_models.dart' as base;
import 'package:inheritance_test_models/middle_models.dart' as middle;

part 'inheritance_models.fory.dart';

@ForyStruct()
final class CrossLibraryChild extends middle.PublicMiddleBoundary {
  // Keep explicit forwarding to exercise constructor identity analysis.
  // ignore: use_super_parameters
  CrossLibraryChild(String rootValue, int middleValue, this.childFinal)
    : super(rootValue, middleValue);

  @ForyField(id: 21)
  final bool childFinal;

  @ForyField(id: 22)
  String childMutable = '';
}

@ForyStruct(ignoreInheritedPrivateFields: true)
final class IgnoringPrivateChild extends middle.UnexposedPrivateMiddle {
  IgnoringPrivateChild();

  @ForyField(id: 10)
  String childPublic = '';
}

class OptionalMutableBase {
  OptionalMutableBase([int decoded = 0])
    : inheritedMutable = decoded,
      observedAtConstruction = decoded;

  @ForyField(id: 1)
  int inheritedMutable;

  @ForyField(ignore: true)
  final int observedAtConstruction;
}

@ForyStruct()
final class OptionalMutableChild extends OptionalMutableBase {
  OptionalMutableChild(this.fixed, [int decoded = 0]) : super(decoded);

  @ForyField(id: 2)
  final int fixed;
}

class AllWritableOptionalBase {
  AllWritableOptionalBase([int decoded = 0])
    : inheritedMutable = decoded,
      observedAtConstruction = decoded;

  @ForyField(id: 1)
  int inheritedMutable;

  @ForyField(ignore: true)
  final int observedAtConstruction;
}

@ForyStruct()
final class AllWritableOptionalChild extends AllWritableOptionalBase {
  AllWritableOptionalChild([super.decoded]);
}

@ForyStruct()
final class XlangInheritedChild extends base.XlangInheritanceBoundary {
  XlangInheritedChild();

  @ForyField(id: 1, type: Int32Type())
  int childInt = 0;

  @ForyField(id: 5)
  bool childFlag = false;

  @ForyField(id: 9)
  String childText = '';
}

class PublicXlangInheritanceParent {
  @ForyField(id: 2, type: Int32Type())
  int parentInt = 0;

  @ForyField(id: 6)
  bool parentFlag = false;

  @ForyField(id: 10)
  String parentText = '';
}

@ForyStruct()
final class PublicXlangInheritedChild extends PublicXlangInheritanceParent {
  PublicXlangInheritedChild();

  @ForyField(id: 1, type: Int32Type())
  int childInt = 0;

  @ForyField(id: 5)
  bool childFlag = false;

  @ForyField(id: 9)
  String childText = '';
}

@ForyStruct()
final class FlatXlangInheritedStruct {
  FlatXlangInheritedStruct();

  @ForyField(id: 1, type: Int32Type())
  int childInt = 0;

  @ForyField(id: 2, type: Int32Type())
  int parentInt = 0;

  @ForyField(id: 5)
  bool childFlag = false;

  @ForyField(id: 6)
  bool parentFlag = false;

  @ForyField(id: 9)
  String childText = '';

  @ForyField(id: 10)
  String parentText = '';
}

@ForyStruct()
class CompatibleOldParent {
  CompatibleOldParent();

  @ForyField(id: 1)
  String inheritedStable = '';
}

@ForyStruct()
final class CompatibleOldChild extends CompatibleOldParent {
  CompatibleOldChild();

  @ForyField(id: 3)
  String childStable = '';
}

@ForyStruct()
class CompatibleNewParent {
  CompatibleNewParent();

  @ForyField(id: 1)
  String inheritedStable = '';

  @ForyField(id: 2)
  String inheritedAdded = 'new-default';
}

@ForyStruct()
final class CompatibleNewChild extends CompatibleNewParent {
  CompatibleNewChild();

  @ForyField(id: 3)
  String childStable = '';
}

@ForyStruct()
final class ReferenceLeaf {
  ReferenceLeaf();

  @ForyField(id: 1)
  String label = '';
}

class ReferenceFieldsBase<T> {
  ReferenceFieldsBase();

  @ForyField(id: 1, ref: true)
  T? first;

  @ForyField(id: 2, ref: true)
  T? second;

  @ListField(id: 3, element: DeclaredType(ref: true))
  List<T> nested = <T>[];
}

@ForyStruct()
final class InheritedReferenceModel extends ReferenceFieldsBase<ReferenceLeaf> {
  InheritedReferenceModel();
}

@ForyStruct()
final class FlatReferenceModel {
  FlatReferenceModel();

  @ForyField(id: 1, ref: true)
  ReferenceLeaf? first;

  @ForyField(id: 2, ref: true)
  ReferenceLeaf? second;

  @ListField(id: 3, element: DeclaredType(ref: true))
  List<ReferenceLeaf> nested = <ReferenceLeaf>[];
}

class SelfReferenceBase<T> {
  SelfReferenceBase();

  @ForyField(id: 1, ref: true)
  T? self;
}

@ForyStruct()
final class InheritedSelfReference
    extends SelfReferenceBase<InheritedSelfReference> {
  InheritedSelfReference();
}

@ForyStruct()
final class FlatSelfReference {
  FlatSelfReference();

  @ForyField(id: 1, ref: true)
  FlatSelfReference? self;
}
