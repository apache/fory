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

import 'dart:mirrors';
import 'package:fory/src/meta/specs/custom_type_spec.dart';

CustomTypeSpec? resolveByMirrors(Type type) {
  final ClassMirror? classMirror = _classMirrorOf(type);
  if (classMirror == null) {
    return null;
  }
  final String simpleName = MirrorSystem.getName(classMirror.simpleName);
  if (simpleName.isEmpty) {
    return null;
  }
  final Symbol symbol = Symbol('\$$simpleName');
  final LibraryMirror? ownerLibrary = _ownerLibraryOf(classMirror);
  if (ownerLibrary != null) {
    final CustomTypeSpec? inOwner = _readSpecFromLibrary(ownerLibrary, symbol);
    if (inOwner != null && inOwner.dartType == type) {
      return inOwner;
    }
  }
  for (final LibraryMirror library in currentMirrorSystem().libraries.values) {
    if (identical(library, ownerLibrary)) {
      continue;
    }
    final CustomTypeSpec? candidate = _readSpecFromLibrary(library, symbol);
    if (candidate != null && candidate.dartType == type) {
      return candidate;
    }
  }
  return null;
}

ClassMirror? _classMirrorOf(Type type) {
  try {
    final TypeMirror typeMirror = reflectType(type);
    if (typeMirror is ClassMirror) {
      return typeMirror;
    }
  } catch (_) {
    return null;
  }
  return null;
}

LibraryMirror? _ownerLibraryOf(ClassMirror classMirror) {
  DeclarationMirror? owner = classMirror.owner;
  while (owner != null && owner is! LibraryMirror) {
    if (owner is ClassMirror) {
      owner = owner.owner;
      continue;
    }
    return null;
  }
  return owner is LibraryMirror ? owner : null;
}

CustomTypeSpec? _readSpecFromLibrary(LibraryMirror library, Symbol symbol) {
  if (!library.declarations.containsKey(symbol)) {
    return null;
  }
  try {
    final Object? reflectee = library.getField(symbol).reflectee;
    if (reflectee is CustomTypeSpec) {
      return reflectee;
    }
  } catch (_) {
    return null;
  }
  return null;
}
