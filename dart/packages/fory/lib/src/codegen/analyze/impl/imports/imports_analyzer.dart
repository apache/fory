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

import 'dart:collection';

import 'package:analyzer/dart/element/element.dart';
import 'package:fory/src/codegen/analyze/analysis_cache.dart';
import 'package:fory/src/codegen/analyze/analysis_type_identifier.dart';
import 'package:fory/src/codegen/meta/library_import_pack.dart';

class ImportsAnalyzer {
  const ImportsAnalyzer();

  LibraryImportPack analyze(LibraryElement libElement) {
    LibraryImportPack? importPack;
    // check cache
    importPack = AnalysisCache.getLibraryImportPack(libElement.id);
    if (importPack != null) return importPack;

    Map<int, String> libToPrefix = HashMap();
    List<LibraryImportElement> imports =
        libElement.definingCompilationUnit.libraryImports;
    String? prefix;
    for (var import in imports) {
      prefix = import.prefix?.element.name;
      if (prefix == null) continue;
      libToPrefix[import.importedLibrary!.id] = prefix;
    }
    String? dartCorePrefix = libToPrefix[AnalysisTypeIdentifier.dartCoreLibId];
    // cache it
    importPack = LibraryImportPack(libToPrefix, dartCorePrefix);
    AnalysisCache.putLibraryImportPack(libElement.id, importPack);
    return importPack;
  }
}
