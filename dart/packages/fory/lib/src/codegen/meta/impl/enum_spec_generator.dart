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
import 'package:fory/src/codegen/meta/custom_type_spec_generator.dart';
import 'package:fory/src/codegen/tool/codegen_tool.dart';
import 'package:meta/meta.dart';

@immutable
class EnumSpecGenerator extends CustomTypeSpecGenerator {
  final List<String> _enumVarNames;
  late final String _varName;

  EnumSpecGenerator(super.name, super.importPath, this._enumVarNames) {
    _varName = "\$$name";
    assert(_enumVarNames.isNotEmpty);
  }

  void _writeFieldsStr(StringBuffer buf, int indentLevel) {
    int totalIndent = indentLevel * CodegenStyle.indent;
    CodegenTool.writeIndent(buf, totalIndent);
    buf.write("[");
    for (String varName in _enumVarNames) {
      buf.write(name);
      buf.write(".");
      buf.write(varName);
      buf.write(', ');
    }
    buf.write("],\n");
  }

  @override
  void writeCode(StringBuffer buf, [int indentLevel = 0]) {
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
    buf.write("const ");
    buf.write(_varName);
    buf.write(" = EnumSpec(\n");

    // type name
    CodegenTool.writeIndent(buf, CodegenStyle.indent * (indentLevel + 1));
    buf.write(name);
    buf.write(",\n");

    // // tag
    // GenCodeTool.writeIndent(buf, GenCodeStyle.indent * (indentLevel + 1));
    // buf.write("'''");
    // buf.write(tag);
    // buf.write("'''");
    // buf.write(",\n");

    _writeFieldsStr(buf, indentLevel + 1);

    // tail part
    buf.write(");\n");
  }
}
