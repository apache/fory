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

import 'package:fory/src/config/config.dart';

class ForyConfig extends Config {
  final int _configId;
  final bool _compatible;
  final bool _refTracking;
  final bool _basicTypesRefIgnored;
  final bool _timeRefIgnored;
  final bool _stringRefIgnored;

  ForyConfig.onlyForManager(
    this._configId, {
    bool compatible = false,
    bool refTracking = true,
    bool basicTypesRefIgnored = true,
    bool timeRefIgnored = true,
    // bool stringRefIgnored = true,
  })  : _compatible = compatible,
        _refTracking = refTracking,
        _basicTypesRefIgnored = basicTypesRefIgnored,
        _timeRefIgnored = timeRefIgnored,
        _stringRefIgnored = false {
    // some checking works
    // assert(_xlangMode == true, 'currently only support xlang mode');
  }

  //getters
  bool get compatible => _compatible;
  bool get refTracking => _refTracking;
  int get configId => _configId;
  bool get basicTypesRefIgnored => _basicTypesRefIgnored;
  bool get timeRefIgnored => _timeRefIgnored;
  bool get stringRefIgnored => _stringRefIgnored;
}
