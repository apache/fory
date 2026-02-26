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

// Currently not in use
class TypeStringKey {
  final String name;
  final String scheme; // It seems there are only two types: dart and package
  final String path;

  int? _hashCode;

  TypeStringKey(this.name, this.scheme, this.path);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is TypeStringKey &&
          runtimeType == other.runtimeType &&
          name == other.name &&
          scheme == other.scheme &&
          path == other.path;

  @override
  int get hashCode {
    if (_hashCode != null) return _hashCode!;

    // In practice, names are mostly different, so the name needs to be given enough importance
    int nameHash = name.hashCode;
    int len1 = path.length;

    int letter1 = scheme.isNotEmpty ? scheme.codeUnitAt(0) : 32;
    int letter2 = path.isNotEmpty ? path.codeUnitAt(0) : 32;
    int letter3 = path.isNotEmpty ? path.codeUnitAt(path.length ~/ 2) : 32;
    int letter4 =
        path.isNotEmpty ? path.codeUnitAt((path.length ~/ 7) * 5) : 32;

    int hash = 17;
    hash = hash * 31 + nameHash;
    hash = hash * 31 + len1;
    hash = hash * 31 + letter1;
    hash = hash * 31 + letter2;
    hash = hash * 31 + letter3;
    hash = hash * 31 + letter4;

    _hashCode = hash;
    return hash;
  }
}
