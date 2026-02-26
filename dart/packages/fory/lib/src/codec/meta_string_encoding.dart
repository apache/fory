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

enum MetaStringEncoding {
  utf8(0x00, -1),
  ls(0x01, 5),
  luds(0x02, 6),
  ftls(0x03, 5),
  atls(0x04, 5);

  final int id;
  final int bits;
  const MetaStringEncoding(this.id, this.bits);

  static MetaStringEncoding fromId(int id) {
    for (var value in MetaStringEncoding.values) {
      if (value.id == id) {
        return value;
      }
    }
    throw ArgumentError('Invalid MetaStringEncoding id: $id');
  }
}
