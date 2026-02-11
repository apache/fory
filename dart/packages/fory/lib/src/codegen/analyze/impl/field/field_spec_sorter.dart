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

import 'package:fory/src/codegen/meta/impl/field_spec_immutable.dart';
import 'package:fory/src/util/string_util.dart';

class FieldSpecSorter {
  const FieldSpecSorter();

  void sortFieldsByName(List<FieldSpecImmutable> fields) {
    for (int i = 0; i < fields.length; ++i) {
      fields[i].transName ??=
          StringUtil.lowerCamelToLowerUnderscore(fields[i].name);
    }
    fields.sort(
      (a, b) {
        return a.transName!.compareTo(b.transName!);
      },
    );
  }
}
