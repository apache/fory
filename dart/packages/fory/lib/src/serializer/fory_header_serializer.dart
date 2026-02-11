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
import 'package:fory/src/const/fory_header_const.dart';
import 'package:fory/src/memory/byte_reader.dart';
import 'package:fory/src/memory/byte_writer.dart';

typedef HeaderBrief = ({
  bool isXLang,
  bool oobEnabled,
});

final class ForyHeaderSerializer {
  // singleton
  static final ForyHeaderSerializer _singleton = ForyHeaderSerializer._();
  static ForyHeaderSerializer get I => _singleton;
  ForyHeaderSerializer._();

  HeaderBrief? read(ByteReader br, ForyConfig conf) {
    int bitmap = br.readInt8();
    // header: nullFlag
    if ((bitmap & ForyHeaderConst.nullFlag) != 0) {
      return null;
    }
    // header: xlang
    bool isXLang = (bitmap & ForyHeaderConst.crossLanguageFlag) != 0;
    assert(isXLang, 'Now Fory Dart only supports xlang mode');
    bool oobEnabled = (bitmap & ForyHeaderConst.outOfBandFlag) != 0;
    //TODO: oobEnabled unsupported yet.
    return (
      isXLang: isXLang,
      oobEnabled: oobEnabled,
    );
  }

  void write(ByteWriter bd, bool objNull, ForyConfig conf) {
    int bitmap = ForyHeaderConst.crossLanguageFlag;
    if (objNull) {
      bitmap |= ForyHeaderConst.nullFlag;
    }
    // callback must be null
    bd.writeInt8(bitmap);
    // Next is xWriteRef, handed over to the outside
  }
}
