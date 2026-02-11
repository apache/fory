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

import 'dart:collection';
import 'package:fory/src/codec/encoders.dart';
import 'package:fory/src/codec/meta_string_encoder.dart';
import 'package:fory/src/codec/meta_string_encoding.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/resolver/tag_string_resolver.dart';

final class TagStringResolverImpl extends TagStringResolver {
  final MetaStringEncoder _tnEncoder = Encoders.typeNameEncoder;
  final MetaStringEncoder _nsEncoder = Encoders.packageEncoder;

  static const List<MetaStringEncoding> _tagAllowedEncodings = [
    MetaStringEncoding.utf8,
    MetaStringEncoding.luds,
    MetaStringEncoding.ftls,
    MetaStringEncoding.atls,
  ];

  static const List<MetaStringEncoding> _nsAllowedEncodings = [
    MetaStringEncoding.utf8,
    MetaStringEncoding.luds,
    MetaStringEncoding.ftls,
    MetaStringEncoding.atls,
  ];

  final Map<String, MetaString> _tnMetaStringCache = HashMap();
  final Map<String, MetaString> _nsMetaStringCache = HashMap();

  @override
  MetaString encodeTypeName(String tag) {
    MetaString? metaString = _tnMetaStringCache[tag];
    if (metaString != null) {
      return metaString;
    }
    metaString = _tnEncoder.encodeByAllowedEncodings(tag, _tagAllowedEncodings);
    _tnMetaStringCache[tag] = metaString;
    return metaString;
  }

  @override
  MetaString encodeNs(String ns) {
    MetaString? metaString = _nsMetaStringCache[ns];
    if (metaString != null) {
      return metaString;
    }
    metaString = _nsEncoder.encodeByAllowedEncodings(ns, _nsAllowedEncodings);
    _nsMetaStringCache[ns] = metaString;
    return metaString;
  }
}
