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

import 'package:fory/src/codegen/const/location_level.dart';
import 'package:meta/meta.dart';

@immutable
class LocationMark {
  final String libPath;
  final String clsName;
  final String? fieldName;
  final LocationLevel _level;

  LocationMark._(
    this.libPath,
    this.clsName,
    this.fieldName,
    this._level,
  );

  LocationMark.clsLevel(this.libPath, this.clsName)
      : fieldName = null,
        _level = LocationLevel.clsLevel;

  LocationMark.fieldLevel(this.libPath, this.clsName, this.fieldName)
      : _level = LocationLevel.fieldLevel;

  bool get ensureFieldLevel => _level.index >= LocationLevel.fieldLevel.index;
  bool get ensureClassLevel => _level.index >= LocationLevel.clsLevel.index;

  String get clsLocation => '$libPath@$clsName';

  LocationMark copyWithFieldName(String fieldName) {
    return LocationMark._(
        libPath, clsName, fieldName, LocationLevel.fieldLevel);
  }
}
