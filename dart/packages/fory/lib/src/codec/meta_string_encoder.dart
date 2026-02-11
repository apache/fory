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

import 'package:meta/meta.dart';

import 'package:fory/src/codec/entity/string_statistics.dart';
import 'package:fory/src/codec/meta_string_codecs.dart';
import 'package:fory/src/codec/meta_string_encoding.dart';
import 'package:fory/src/meta/meta_string.dart';
import 'package:fory/src/util/char_util.dart';

abstract base class MetaStringEncoder extends MetaStringCodecs {
  const MetaStringEncoder(super.specialChar1, super.specialChar2);

  // MetaString encode(String input, MetaStringEncoding encoding);
  MetaString encodeByAllowedEncodings(
      String input, List<MetaStringEncoding> encodings);

  StringStatistics _computeStringStatistics(String input) {
    bool canLUDS = true;
    bool canLS = true;
    int digitCount = 0;
    int upperCount = 0;
    for (var c in input.codeUnits) {
      if (canLUDS &&
          !CharUtil.isLUD(c) &&
          c != specialChar1 &&
          c != specialChar2) canLUDS = false;
      if (canLS && !CharUtil.isLS(c)) canLS = false;
      if (CharUtil.digit(c)) ++digitCount;
      if (CharUtil.upper(c)) ++upperCount;
    }
    return StringStatistics(
      digitCount,
      upperCount,
      canLUDS,
      canLS,
    );
  }

  @protected
  MetaStringEncoding decideEncoding(
      String input, List<MetaStringEncoding> encodings) {
    List<bool> flags = List.filled(MetaStringEncoding.values.length, false);
    for (var e in encodings) {
      flags[e.index] = true;
    }
    // The encoding array is very small, so the List's contains method is used. If more encodings need to be supported in the future, consider using a Set.
    if (input.isEmpty && flags[MetaStringEncoding.ls.index]) {
      return MetaStringEncoding.ls;
    }
    StringStatistics stat = _computeStringStatistics(input);
    if (stat.canLS && flags[MetaStringEncoding.ls.index]) {
      return MetaStringEncoding.ls;
    }
    if (stat.canLUDS) {
      if (stat.digitCount != 0 && flags[MetaStringEncoding.luds.index]) {
        return MetaStringEncoding.luds;
      }
      if (stat.upperCount == 1 &&
          CharUtil.upper(input.codeUnitAt(0)) &&
          flags[MetaStringEncoding.ftls.index]) {
        return MetaStringEncoding.ftls;
      }
      if (((input.length + stat.upperCount) * 5 < input.length * 6) &&
          flags[MetaStringEncoding.atls.index]) {
        return MetaStringEncoding.atls;
      }
      if (flags[MetaStringEncoding.luds.index]) {
        return MetaStringEncoding.luds;
      }
    }
    return MetaStringEncoding.utf8;
  }
}
