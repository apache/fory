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

import 'package:fory/src/collection/stack.dart';
import 'package:fory/src/serialization_coordinator.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/resolver/meta_string_writing_resolver.dart';
import 'package:fory/src/resolver/serialization_ref_resolver.dart';
import 'package:fory/src/resolver/type_resolver.dart';
import 'package:fory/src/pack.dart';

final class SerializerPack extends Pack {
  final SerializationCoordinator serializationCoordinator;
  final TypeResolver typeResolver;
  final SerializationRefResolver refResolver;
  final SerializationRefResolver noRefResolver;
  final MetaStringWritingResolver msWritingResolver;
  final Stack<TypeSpecWrap> typeWrapStack;

  const SerializerPack(
    super.structHashResolver,
    super.getTagByDartType,
    this.serializationCoordinator,
    this.typeResolver,
    this.refResolver,
    this.noRefResolver,
    this.msWritingResolver,
    this.typeWrapStack,
  );
}
