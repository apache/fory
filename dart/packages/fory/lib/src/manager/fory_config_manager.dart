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

import 'package:fory/src/config/fory_config.dart';

class ForyConfigManager {
  // singleton
  static final ForyConfigManager _instance = ForyConfigManager._();
  static ForyConfigManager get inst => _instance;
  ForyConfigManager._();

  int configId = 0;
  int get nextConfigId => configId++;

  ForyConfig createConfig({
    bool compatible = false,
    bool refTracking = true,
    bool basicTypesRefIgnored = true,
    bool timeRefIgnored = true,
    // bool stringRefIgnored = true,
  }) {
    return ForyConfig.onlyForManager(
      nextConfigId,
      compatible: compatible,
      refTracking: refTracking,
      basicTypesRefIgnored: basicTypesRefIgnored,
      timeRefIgnored: timeRefIgnored,
      // stringRefIgnored: stringRefIgnored,
    );
  }
}
