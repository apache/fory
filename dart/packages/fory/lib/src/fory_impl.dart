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

import 'dart:typed_data';
import 'package:fory/src/base_fory.dart';
import 'package:fory/src/codegen/entity/struct_hash_pair.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/dev_annotation/optimize.dart';
import 'package:fory/src/deserialize_coordinator.dart';
import 'package:fory/src/serialize_coordinator.dart';
import 'package:fory/src/manager/fory_config_manager.dart';
import 'package:fory/src/resolver/xtype_resolver.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/meta/specs/custom_type_spec.dart';
import 'package:fory/src/serializer/serializer.dart';

final class Fory implements BaseFory {
  static final DeserializeCoordinator _deserDirector = DeserializeCoordinator.I;
  static final SerializeCoordinator _serDirector = SerializeCoordinator.I;

  final ForyConfig _conf;
  late final XtypeResolver _xtypeResolver;

  Fory({
    bool compatible = false,
    bool refTracking = false,
    bool basicTypesRefIgnored = true,
    bool timeRefIgnored = true,
    // bool stringRefIgnored = true,
  }) : _conf = ForyConfigManager.inst.createConfig(
          compatible: compatible,
          refTracking: refTracking,
          basicTypesRefIgnored: basicTypesRefIgnored,
          timeRefIgnored: timeRefIgnored,
          // stringRefIgnored: stringRefIgnored,
        ) {
    _xtypeResolver = XtypeResolver.newOne(_conf);
  }

  @override
  @inline
  void register(CustomTypeSpec spec, [Object? tagOrTypeId]) {
    _xtypeResolver.reg(spec, tagOrTypeId);
  }

  @inline
  @override
  void registerSerializer(Type type, Serializer ser) {
    _xtypeResolver.registerSerializer(type, ser);
  }

  @override
  @inline
  Object? fromFory(Uint8List bytes, [ByteReader? reader]) {
    return _deserDirector.read(bytes, _conf, _xtypeResolver, reader);
  }

  @override
  @inline
  Uint8List toFory(
    Object? obj,
  ) {
    return _serDirector.write(obj, _conf, _xtypeResolver);
  }

  @override
  @inline
  void toForyWithWriter(Object? obj, ByteWriter writer) {
    _serDirector.writeWithWriter(obj, _conf, _xtypeResolver, writer);
  }

  // for test only
  StructHashPair getStructHashPair(Type type) {
    return _xtypeResolver.getHashPairForTest(type);
  }
}
