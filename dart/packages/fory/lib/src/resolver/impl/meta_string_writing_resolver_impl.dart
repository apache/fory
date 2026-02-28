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

import 'package:fory/src/memory/byte_writer.dart';
import 'package:fory/src/meta/meta_string_byte.dart';
import 'package:fory/src/resolver/meta_string_writing_resolver.dart';

final class MetaStringWritingResolverImpl extends MetaStringWritingResolver {
  int _dynamicWriteStrId = 0;

  final Map<int, int> _memHash2Id = {};

  @override
  void writeMetaStringBytes(ByteWriter bw, MetaStringBytes msb) {
    int identityHash = identityHashCode(msb);
    int? id = _memHash2Id[identityHash];
    if (id != null) {
      bw.writeVarUint32Small7(((id + 1) << 1) | 1);
      return;
    }
    _memHash2Id[identityHash] = _dynamicWriteStrId;
    ++_dynamicWriteStrId;
    int bytesLen = msb.length;
    bw.writeVarUint32Small7(bytesLen << 1);
    if (bytesLen > smallStringThreshold) {
      bw.writeInt64(msb.hashCode);
    } else if (bytesLen != 0) {
      bw.writeInt8(msb.encoding.id);
    }
    bw.writeBytes(msb.bytes);
  }
}
