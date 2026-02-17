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

import 'package:meta/meta.dart';
import 'package:fory/src/codegen/meta/custom_type_spec_generator.dart';
import 'package:fory/src/codegen/meta/impl/constructor_spec_generator.dart';
import 'package:fory/src/codegen/meta/impl/fields_spec_generator.dart';
import 'package:fory/src/codegen/meta/library_import_pack.dart';
import 'package:fory/src/codegen/meta/impl/constructor_info.dart';
import 'package:fory/src/codegen/config/codegen_style.dart';
import 'package:fory/src/codegen/tool/codegen_tool.dart';

@immutable
class ClassSpecGenerator extends CustomTypeSpecGenerator {
  final bool promiseAcyclic;
  final bool noCyclicRisk;
  final FieldsSpecGenerator _fieldsSpecGen;
  final LibraryImportPack imports;
  late final ConstructorSpecGenerator _consSpecGen;
  late final String _varName;

  late final String? _dartCorePrefixWithPoint;

  ClassSpecGenerator(
    super.name,
    super.importPath,
    this.promiseAcyclic,
    this.noCyclicRisk,
    this._fieldsSpecGen,
    this.imports,
    ConstructorInfo consInfo,
  ) {
    _varName = "\$$name";
    _dartCorePrefixWithPoint =
        imports.dartCorePrefix != null ? "${imports.dartCorePrefix}." : null;
    _consSpecGen = ConstructorSpecGenerator(
      name,
      imports,
      consInfo,
      _fieldsSpecGen,
    );
  }

  @override
  void writeCode(StringBuffer buf, [int indentLevel = 0]) {
    int totalIndent = indentLevel * CodegenStyle.indent;
    int nextTotalIndent = totalIndent + CodegenStyle.indent;
    // // the import part
    // buf.write("import 'packages:");
    // buf.write(ForyCoreConst.importPath);
    // buf.write("';\n");
    // buf.write(GenCodeStyle.magicSign);
    // buf.write(_varName);
    // buf.write(GenCodeStyle.markSep);
    // buf.write(name);
    // buf.write(GenCodeStyle.markSep);
    // buf.write(tag);
    // buf.write(GenCodeStyle.markSep);
    // buf.write(importPath);
    // buf.write("\n");

    // the declare of variable
    buf.write("final ");
    buf.write(_varName);
    buf.write(" = TypeSpec(\n");

    // arg: type name
    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write(name);
    buf.write(",\n");

    // arg: type name
    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write(promiseAcyclic ? "true" : "false");
    buf.write(",\n");

    // arg: noCyclicRisk
    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write(noCyclicRisk ? "true" : "false");
    buf.write(",\n");

    // // second arg: tag
    // GenCodeTool.writeIndent(buf, nextTotalIndent);
    // buf.write("\'\'\'");
    // buf.write(tag);
    // buf.write("\'\'\',\n");

    // // fromForyHash
    // GenCodeTool.writeIndent(buf, nextTotalIndent);
    // buf.write(_fromForyHash);
    // buf.write(",\n");
    //
    // // toForyHash
    // GenCodeTool.writeIndent(buf, nextTotalIndent);
    // buf.write(_toForyHash);
    // buf.write(",\n");

    // arg: fields
    _fieldsSpecGen.writeCodeWithImports(
        buf, imports, _dartCorePrefixWithPoint, indentLevel + 1);

    // arg: construct function
    _consSpecGen.writeCodeWithImports(
        buf, imports, _dartCorePrefixWithPoint, indentLevel + 1);

    // tail part
    buf.write(");\n\n");
  }
}
