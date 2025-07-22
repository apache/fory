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
import 'package:fory/src/codegen/entity/constructor_params.dart';
import 'package:fory/src/codegen/meta/gen_export.dart';
import 'package:fory/src/codegen/meta/impl/constructor_info.dart';
import 'package:fory/src/codegen/meta/impl/field_spec_immutable.dart';
import 'package:fory/src/codegen/meta/impl/fields_spec_gen.dart';
import 'package:fory/src/codegen/meta/lib_import_pack.dart';
import 'package:fory/src/codegen/tool/codegen_tool.dart';
import 'package:meta/meta.dart';

@immutable
class ConstructorSpecGen extends GenExport{

  final String className;
  final LibImportPack imports;
  final ConstructorInfo consInfo;
  final FieldsSpecGen fieldsSpecGen;

  const ConstructorSpecGen(this.className, this.imports,this.consInfo, this.fieldsSpecGen);

  @override
  void genCodeReqImportsInfo(StringBuffer buf, LibImportPack imports, String? dartCorePrefixWithPoint,[int indentLevel = 0]) {
    int totalIndent = indentLevel * CodegenStyle.indent;
    if (consInfo.flexibleOrUnnamedCons){
      // Use parameterless constructor
      _genCodeForFlexibleCons(buf, consInfo.flexibleConsName!, totalIndent);
    }else{
      // Use unnamed constructor
      _genCodeForUnnamedCons(buf, consInfo.unnamedConsParams!, totalIndent, dartCorePrefixWithPoint);
    }
  }

  void _genCodeForFlexibleCons(StringBuffer buf, String consName, int baseIndent){
    CodegenTool.writeIndent(buf, baseIndent);
    buf.write("null,\n");
    CodegenTool.writeIndent(buf, baseIndent);
    buf.write("() => ");
    buf.write(className);
    if (consName.isNotEmpty){
      buf.write(".");
      buf.write(consName);
    }
    buf.write("(),\n");
  }

  void _genCodeForUnnamedCons(StringBuffer buf, ConstructorParams consParams, int baseIndent, String? dartCorePrefixWithPoint){
    int nextTotalIndent = baseIndent + CodegenStyle.indent;
    List<FieldSpecImmutable> fields = fieldsSpecGen.fields;
    List<bool> setThroughConsFlags = fieldsSpecGen.setThroughConsFlags;
    CodegenTool.writeIndent(buf, baseIndent);

    buf.write("(");
    if (dartCorePrefixWithPoint != null) {
      buf.write(dartCorePrefixWithPoint);
    }
    buf.write("List<dynamic> objList) => ");
    buf.write(className);
    buf.write("(\n");
    for (var param in consParams.positional){
      if (param.fieldIndex == -1) continue;
      CodegenTool.writeIndent(buf, nextTotalIndent);
      String paramName = "objList[${param.fieldIndex}]";
      fields[param.fieldIndex].typeAdapter.genCodeReqImportsInfo(buf, imports, dartCorePrefixWithPoint, 0 ,paramName);
      buf.write(",\n");
    }

    for (var param in consParams.named){
      if (param.fieldIndex == -1) continue;
      CodegenTool.writeIndent(buf, nextTotalIndent);
      buf.write(param.name);
      buf.write(": ");
      String paramName = "objList[${param.fieldIndex}]";
      fields[param.fieldIndex].typeAdapter.genCodeReqImportsInfo(buf, imports, dartCorePrefixWithPoint, 0, paramName);
      buf.write(",\n");
    }
    CodegenTool.writeIndent(buf, baseIndent);
    buf.write(')');

    late FieldSpecImmutable field;
    for (int i =0; i< fields.length; ++i){
      field = fields[i];
      if (field.includeFromFory && !setThroughConsFlags[i]){
        assert(field.canSet); // This should have been ensured in previous steps, if there's an error, it should have already stopped
        buf.write("..");
        buf.write(field.name);
        buf.write(" = ");
        String paramName = "objList[$i]";
        field.typeAdapter.genCodeReqImportsInfo(buf, imports, dartCorePrefixWithPoint, 0, paramName);
        buf.write("\n");
      }
    }
    buf.write(",\n");
    CodegenTool.writeIndent(buf, baseIndent);
    buf.write("null,\n");
  }
}
