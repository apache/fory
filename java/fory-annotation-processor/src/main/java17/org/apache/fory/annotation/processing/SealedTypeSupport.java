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

package org.apache.fory.annotation.processing;

import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

/** Java 17 annotation-processing access to sealed source metadata. */
final class SealedTypeSupport {
  private SealedTypeSupport() {}

  static boolean isSealed(TypeElement type) {
    return type.getModifiers().contains(Modifier.SEALED);
  }

  static List<? extends TypeElement> directSubtypes(TypeElement type) {
    List<TypeElement> result = new ArrayList<>();
    for (TypeMirror subtype : type.getPermittedSubclasses()) {
      if (subtype instanceof javax.lang.model.type.DeclaredType) {
        result.add((TypeElement) ((javax.lang.model.type.DeclaredType) subtype).asElement());
      }
    }
    return result;
  }
}
