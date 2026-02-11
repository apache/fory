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

import 'package:collection/collection.dart';
import 'package:meta/meta.dart';
import 'package:fory/src/meta/specs/custom_type_spec.dart';
import 'package:fory/src/const/types.dart';

// Enums do not need tags because they are not allowed to be transmitted directly; they should always be within a class.
// Enums do not support inheritance, which makes serialization much easier as there will be no cases where the specific class is unknown.
@immutable
class EnumSpec extends CustomTypeSpec {
  // final String tag;
  // TODO: Currently, enums only support using ordinal for transmission. There is also support for ForyEnum annotation, such as using value, so we can directly use the values array here.
  final List<Enum> values;
  const EnumSpec(Type dartType, this.values)
      : super(dartType, ObjType.NAMED_ENUM);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        other is EnumSpec &&
            runtimeType == other.runtimeType &&
            dartType == other.dartType &&
            values.equals(other.values);
  }

  @override
  int get hashCode => Object.hash(
        runtimeType,
        dartType,
        values,
      );
}
