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

import 'package:fory/src/codec/impl/meta_string_decoder_impl.dart';
import 'package:fory/src/codec/impl/meta_string_encoder_impl.dart';
import 'package:fory/src/codec/meta_string_encoder.dart';
import 'package:fory/src/codec/meta_string_decoder.dart';

final class Encoders {
  // Here, to use const optimization, we rely on specific implementation classes for performance considerations
  static const MetaStringEncoder genericEncoder =
      ForyMetaStringEncoder(46, 95); // '.', '_'
  static const MetaStringDecoder genericDecoder =
      ForyMetaStringDecoder(46, 95); // '.', '_'
  static const MetaStringEncoder packageEncoder = genericEncoder;
  static const MetaStringDecoder packageDecoder = genericDecoder;
  static const MetaStringEncoder typeNameEncoder =
      ForyMetaStringEncoder(36, 95); // '$', '_'
  static const MetaStringDecoder typeNameDecoder =
      ForyMetaStringDecoder(36, 95); // '$', '_'
}
