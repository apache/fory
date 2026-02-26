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

import 'package:fory/src/codegen/config/codegen_style.dart';
import 'package:fory/src/codegen/meta/generated_code_part.dart';
import 'package:fory/src/codegen/meta/impl/type_spec_generator.dart';
import 'package:fory/src/codegen/meta/library_import_pack.dart';
import 'package:fory/src/codegen/tool/codegen_tool.dart';

import 'package:fory/src/const/types.dart';

final class TypeAdapter extends GeneratedCodePart {
  final TypeSpecGenerator typeSpec;
  TypeAdapter(this.typeSpec);

  void _writeCodeInner(
    StringBuffer buf,
    LibraryImportPack imports,
    TypeSpecGenerator spec,
    String paramName, [
    String? dartCorePrefixWithPoint,
  ]) {
    ObjType objType = spec.immutablePart.objType;
    // It is not possible to be compatible with LinkedList here, because it does not implement the Dart List interface.
    // If you want to support LinkedList, changes must also be made here.
    if (objType == ObjType.LIST || objType == ObjType.SET) {
      if (spec.nullable) {
        buf.write('($paramName == null) ? null : ');
      }
      buf.write(spec.getFullNameNoLastNull(imports));
      buf.write('.of((');
      buf.write(paramName);
      buf.write(' as ');
      buf.write(spec.getShortName(imports));
      buf.write(').map((v)=>');
      _writeCodeInner(
          buf, imports, spec.genericsArgs[0], 'v', dartCorePrefixWithPoint);
      buf.write(')');
      buf.write(')');
      return;
    }
    if (objType == ObjType.MAP) {
      if (spec.nullable) {
        buf.write('($paramName == null) ? null : ');
      }
      buf.write(spec.getFullNameNoLastNull(imports));
      buf.write('.of(');
      buf.write('(');
      buf.write(paramName);
      buf.write(' as ');
      buf.write(spec.getShortName(imports));
      buf.write(').map((k,v)=>');
      if (dartCorePrefixWithPoint != null) {
        buf.write(dartCorePrefixWithPoint);
      }
      buf.write('MapEntry(');
      _writeCodeInner(
          buf, imports, spec.genericsArgs[0], 'k', dartCorePrefixWithPoint);
      buf.write(',');
      _writeCodeInner(
          buf, imports, spec.genericsArgs[1], 'v', dartCorePrefixWithPoint);
      buf.write(')');
      buf.write(')');
      buf.write(')');
      return;
    } else {
      buf.write('(');
      buf.write(paramName);
      buf.write(' as ');
      buf.write(spec.getShortName(imports));
      if (spec.nullable) {
        buf.write('?');
      }
      buf.write(')');
    }
  }

  @override
  void writeCodeWithImports(
    StringBuffer buf,
    LibraryImportPack imports,
    String? dartCorePrefixWithPoint, [
    int indentLevel = 0,
    String paramName = 'v',
  ]) {
    int totalIndent = indentLevel * CodegenStyle.indent;
    CodegenTool.writeIndent(buf, totalIndent);
    _writeCodeInner(
      buf,
      imports,
      typeSpec,
      paramName,
      dartCorePrefixWithPoint,
    );
  }
}
