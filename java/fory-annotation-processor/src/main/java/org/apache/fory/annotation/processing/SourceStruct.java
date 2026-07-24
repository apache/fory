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
import java.util.Collections;
import java.util.List;

final class SourceStruct {
  final String packageName;
  final String typeName;
  final String targetBinaryName;
  final String serializerName;
  final boolean record;
  final boolean debug;
  final boolean hasNestedCompatibleStructFields;
  final int graphMemoryBytes;
  final List<SourceField> fields;
  final List<SourceField> recordConstructorFields;

  SourceStruct(
      String packageName,
      String typeName,
      String targetBinaryName,
      String serializerName,
      boolean record,
      boolean debug,
      int graphMemoryBytes,
      List<SourceField> fields,
      List<SourceField> recordConstructorFields) {
    this.packageName = packageName;
    this.typeName = typeName;
    this.targetBinaryName = targetBinaryName;
    this.serializerName = serializerName;
    this.record = record;
    this.debug = debug;
    this.graphMemoryBytes = graphMemoryBytes;
    this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
    this.recordConstructorFields =
        Collections.unmodifiableList(new ArrayList<>(recordConstructorFields));
    boolean hasNestedStruct = false;
    for (SourceField field : fields) {
      hasNestedStruct |= field.typeNode.hasNestedCompatibleStruct();
    }
    this.hasNestedCompatibleStructFields = hasNestedStruct;
  }

  String qualifiedSerializerName() {
    if (packageName.isEmpty()) {
      return serializerName;
    }
    return packageName + "." + serializerName;
  }
}
