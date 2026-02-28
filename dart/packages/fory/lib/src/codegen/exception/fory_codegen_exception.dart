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

import 'package:fory/src/fory_exception.dart';

abstract class ForyCodegenException extends ForyException {
  final String? _where;
  ForyCodegenException([this._where]);

  /// will generate warning and error location
  @override
  void giveExceptionMessage(StringBuffer buf) {
    buf.write('''[FORY]: Analysis error detected!
You need to make sure your codes don't contain any grammar error itself.
And review the error messages below, correct the issues, and then REGENERATE the code.
''');
    if (_where != null && _where.isNotEmpty) {
      buf.write('where: ');
      buf.write(_where);
      buf.write('\n');
    }
  }
}
