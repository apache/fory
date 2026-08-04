// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

import 'dart:io';
import 'dart:isolate';

import 'package:build/build.dart';
import 'package:build_test/build_test.dart';
import 'package:fory/src/codegen/fory_builder.dart';
import 'package:test/test.dart';

const String foryAnnotationImports = '''
import 'package:fory/src/annotation/fory_field.dart';
import 'package:fory/src/annotation/fory_struct.dart';
import 'package:fory/src/annotation/type_spec.dart';
''';

final Future<Map<String, String>> _annotationAssets = _loadAnnotationAssets();

String foryGeneratedPath(String inputPath) =>
    inputPath.replaceFirst(RegExp(r'\.dart$'), '.fory.dart');

String foryTestLibrarySource(
  String inputPath,
  String body, {
  String directives = '',
  String imports = foryAnnotationImports,
}) {
  final fileName = inputPath.split('/').last;
  return '''
$imports
$directives
part '${foryGeneratedPath(fileName)}';

$body
''';
}

Future<String> generateForySource({
  required String inputPath,
  required String source,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  final result = await testBuilder(
    foryBuilder(BuilderOptions.empty),
    <String, String>{
      ...(await _annotationAssets),
      ...additionalAssets,
      'fory|$inputPath': source,
    },
    rootPackage: 'fory',
    generateFor: <String>{'fory|$inputPath'},
    outputs: null,
    flattenOutput: true,
  );
  expect(result.succeeded, isTrue, reason: result.errors.join('\n'));
  return result.readerWriter.testing.readString(
    AssetId('fory', foryGeneratedPath(inputPath)),
  );
}

Future<void> expectForyGenerationOutput({
  required String inputPath,
  required String source,
  required Matcher output,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  expect(
    await generateForySource(
      inputPath: inputPath,
      source: source,
      additionalAssets: additionalAssets,
    ),
    output,
  );
}

Future<void> expectForyGenerationError({
  required String inputPath,
  required String source,
  required String message,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  final logs = <String>[];
  final result = await testBuilder(
    foryBuilder(BuilderOptions.empty),
    <String, String>{
      ...(await _annotationAssets),
      ...additionalAssets,
      'fory|$inputPath': source,
    },
    rootPackage: 'fory',
    generateFor: <String>{'fory|$inputPath'},
    onLog: (record) => logs.add(record.message),
  );
  expect(result.succeeded, isFalse);
  expect(logs.join('\n'), contains(message));
}

Future<Map<String, String>> _loadAnnotationAssets() async {
  const paths = <String>[
    'src/annotation/fory_field.dart',
    'src/annotation/fory_struct.dart',
    'src/annotation/fory_union.dart',
    'src/annotation/type_spec.dart',
  ];
  final assets = <String, String>{};
  for (final path in paths) {
    final uri = await Isolate.resolvePackageUri(
      Uri.parse('package:fory/$path'),
    );
    if (uri == null) {
      throw StateError('Could not resolve package:fory/$path.');
    }
    assets['fory|lib/$path'] = await File.fromUri(uri).readAsString();
  }
  return assets;
}
