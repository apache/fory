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

import 'package:fory/src/codegen/entity/struct_hash_pair.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/meta/type_info.dart';
import 'package:fory/src/meta/spec_wraps/type_spec_wrap.dart';
import 'package:fory/src/resolver/impl/type_resolver_impl.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/config/fory_config.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serialization_context.dart';

abstract base class TypeResolver {
  const TypeResolver(ForyConfig conf);

  static TypeResolver newOne(ForyConfig conf) {
    return TypeResolverImpl(conf);
  }

  void registerType(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  });

  void registerStruct(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  });

  void registerEnum(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  });

  void registerUnion(
    Type type, {
    int? typeId,
    String? namespace,
    String? typename,
  });

  void registerSerializer(Type type, Serializer serializer);

  void bindSerializers(List<TypeSpecWrap> typeWraps);

  void resetWriteContext();

  void resetReadContext();

  TypeInfo readTypeInfo(ByteReader br);

  String getRegisteredTag(Type type);

  TypeInfo writeTypeInfo(ByteWriter bw, Object obj, SerializationContext pack);

  Serializer getRegisteredSerializer(Type type);

  /*-----For test only------------------------------------------------*/
  StructHashPair getHashPairForTest(
    Type type,
  );
}
