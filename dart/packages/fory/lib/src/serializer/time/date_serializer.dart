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

import 'package:fory/src/const/obj_type.dart';
import 'package:fory/src/datatype/local_date.dart';
import 'package:fory/src/deserializer_pack.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/serializer/serializer.dart';
import 'package:fory/src/serializer/serializer_cache.dart';
import 'package:fory/src/serializer/time/time_serializer_cache.dart';
import 'package:fory/src/serializer_pack.dart';
import 'package:fory/src/util/math_checker.dart';

final class _DateSerializerCache extends TimeSerializerCache{
  static DateSerializer? serRef;
  static DateSerializer? serNoRef;

  const _DateSerializerCache();

  @override
  Serializer getSerWithRef(bool writeRef) {
    if (writeRef){
      serRef ??= DateSerializer._(true);
      return serRef!;
    } else {
      serNoRef ??= DateSerializer._(false);
      return serNoRef!;
    }
  }
}


final class DateSerializer extends Serializer<LocalDate> {

  static const SerializerCache cache = _DateSerializerCache();

  DateSerializer._(bool writeRef) : super(ObjType.DATE, writeRef);

  @override
  LocalDate read(ByteReader br, int refId, DeserializerPack pack) {
    return LocalDate.fromEpochDay(br.readInt32(), utc: true);
  }

  @override
  void write(ByteWriter bw, LocalDate v, SerializerPack pack) {
    int days = v.toEpochDay(utc: true);
    if (!MathChecker.validInt32(days)){
      throw ArgumentError('Date toEpochDay is not valid int32: $days');
    }
    bw.writeInt32(days);
  }
}