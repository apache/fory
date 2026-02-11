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
import 'package:fory/src/codegen/meta/impl/type_immutable.dart';
import 'package:fory/src/codegen/meta/library_import_pack.dart';
import 'package:fory/src/codegen/tool/codegen_tool.dart';
import 'package:fory/src/const/types.dart';

class TypeSpecGenerator extends GeneratedCodePart {
  final TypeImmutable immutablePart;
  late bool nullable;
  final List<TypeSpecGenerator>
      genericsArgs; // An empty list indicates no generic arguments

  String? _fullName;
  String? _fullNameNoLastNull;
  String? _shortName;

  TypeSpecGenerator(
    this.immutablePart,
    this.nullable,
    this.genericsArgs,
  );

  bool get independent => immutablePart.independent;

  String getFullName(LibraryImportPack imports) {
    if (_fullName != null) return _fullName!;
    _fullName = _equipFullTypeName(imports);
    return _fullName!;
  }

  String getFullNameNoLastNull(LibraryImportPack imports) {
    if (_fullNameNoLastNull != null) return _fullNameNoLastNull!;
    if (!nullable) {
      return getFullName(imports);
    }
    _fullNameNoLastNull = _equipFullTypeName(imports, false);
    return _fullNameNoLastNull!;
  }

  String getShortName(LibraryImportPack imports) {
    if (_shortName != null) return _shortName!;
    String? prefix = imports.getPrefixByLibId(immutablePart.typeLibId);
    StringBuffer buf = StringBuffer();
    if (prefix != null) {
      buf.write(prefix);
      buf.write(".");
    }
    buf.write(immutablePart.name);
    _shortName = buf.toString();
    return _shortName!;
  }

  String _equipFullTypeName(LibraryImportPack imports,
      [bool containLastNull = true]) {
    String? importPrefix = imports.getPrefixByLibId(immutablePart.typeLibId);
    // print("equip full type name: $name");
    StringBuffer buf = StringBuffer();
    if (importPrefix != null) {
      buf.write(importPrefix);
      buf.write(".");
    }
    buf.write(immutablePart.name);
    if (genericsArgs.isNotEmpty) {
      buf.write("<");
      for (int i = 0; i < genericsArgs.length - 1; ++i) {
        buf.write(genericsArgs[i].getFullName(imports));
        buf.write(", ");
      }
      buf.write(genericsArgs.last.getFullName(imports));
      buf.write(">");
    }
    if (nullable && containLastNull) {
      buf.write("?");
    }
    return buf.toString();
  }

  @override
  void writeCodeWithImports(StringBuffer buf, LibraryImportPack imports,
      String? dartCorePrefixWithPoint,
      [int indentLevel = 0]) {
    int totalIndent = indentLevel * CodegenStyle.indent;
    int nextTotalIndent = totalIndent + CodegenStyle.indent;

    // declare part
    CodegenTool.writeIndent(buf, totalIndent);
    buf.write("FieldTypeSpec(\n");

    // ForyTypeSpec::type
    String? prefix = imports.getPrefixByLibId(immutablePart.typeLibId);
    CodegenTool.writeIndent(buf, nextTotalIndent);
    if (prefix != null) {
      buf.write(prefix);
      buf.write(".");
    }
    buf.write(immutablePart.name);
    buf.write(",\n");

    // ForyTypeSpec::objType part
    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write("ObjType.");
    buf.write(immutablePart.objType.name);
    buf.write(",\n");

    // // ForyTypeSpec::type part
    // String? importPrefix = prefixReference.getPrefixByLibId(immutablePart.typeLibId);
    // GenCodeTool.writeIndent(buf, nextTotalIndent);
    // if (importPrefix != null){
    //   buf.write(importPrefix);
    //   buf.write(".");
    // }
    // buf.write(immutablePart.name);
    // buf.write(",\n");

    // ForyTypeSpec::nullable part
    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write(nullable ? "true,\n" : "false,\n");

    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write(immutablePart.serializationCertain ? "true,\n" : "false,\n");

    if (immutablePart.objType != ObjType.NAMED_ENUM) {
      // ForyTypeSpec::enumSpec part
      CodegenTool.writeIndent(buf, nextTotalIndent);
      buf.write("null,\n");
    } else {
      if (prefix != null) {
        buf.write(prefix);
        buf.write(".");
      }
      buf.write('\$');
      buf.write(immutablePart.name);
      buf.write(",\n");
    }

    // ForyTypeSpec::genericsArgs part
    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write("const [\n");
    for (var arg in genericsArgs) {
      arg.writeCodeWithImports(
          buf, imports, dartCorePrefixWithPoint, indentLevel + 1);
    }
    CodegenTool.writeIndent(buf, nextTotalIndent);
    buf.write("],\n");

    // end part
    CodegenTool.writeIndent(buf, totalIndent);
    buf.write("),\n");
  }
}
