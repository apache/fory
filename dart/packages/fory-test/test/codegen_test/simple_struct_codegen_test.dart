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

// @Skip()
library;

import 'package:collection/collection.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:build/build.dart';
import 'package:build_test/build_test.dart';
import 'package:checks/checks.dart';
import 'package:test/test.dart';

void main() {
  group('Simple struct codegen', () {
    test('generates EnumSpecs from enum_foo.dart', () async {
      // await runBuild();
      AssetId inputId = AssetId('fory-test', 'lib/entity/enum_foo.dart');
      var lib = await resolveAsset(inputId, (resolver) async {
        return resolver.libraryFor(inputId);
      });
      List<String> variables = [];
      for (var libPart in lib.children) {
        for (var ele in libPart.children) {
          if (ele is VariableElement) {
            InterfaceType type = ele.type as InterfaceType;
            if (type.element.name == 'EnumSpec') {
              // print('found EnumSpec: ${ele.name}');
              variables.add(ele.name);
            }
          }
        }
      }
      check(variables.equals(['\$EnumFoo', '\$EnumSubClass'])).isTrue();
    });

    test('generates TypeSpec without mixin from time_obj.dart', () async {
      // await runBuild();
      AssetId inputId = AssetId('fory-test', 'lib/entity/time_obj.dart');
      var lib = await resolveAsset(inputId, (resolver) async {
        return resolver.libraryFor(inputId);
      });
      List<String> variables = [];
      for (var libPart in lib.children) {
        for (var ele in libPart.children) {
          if (ele is VariableElement) {
            InterfaceType type = ele.type as InterfaceType;
            if (type.element.name == 'TypeSpec') {
              // print('found EnumSpec: ${ele.name}');
              variables.add(ele.name);
            }
          }
        }
      }
      check(variables.equals([
        '\$TimeObj',
      ])).isTrue();
    });
  });
}
