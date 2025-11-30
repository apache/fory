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

package org.apache.fory.format.row;

import org.apache.fory.format.type.Schema;

/**
 * Interface for row in row format. Row is inspired by Apache Spark tungsten, the differences are
 * <li>Use Fory schema to describe meta.
 * <li>String support latin/utf16/utf8 encoding.
 * <li>Decimal use arrow decimal format.
 * <li>Variable-size field can be inline in fixed-size region if small enough.
 * <li>Allow skip padding bye generate Row using aot to put offsets in generated code.
 * <li>The implementation support java/C++/python/golang/javascript/rust/etc..
 * <li>Support adding fields without breaking compatibility in the future.
 */
public interface Row extends Getters, Setters {

  Schema getSchema();

  int numFields();

  Row copy();

  default boolean anyNull() {
    for (int i = 0; i < numFields(); i++) {
      if (isNullAt(i)) {
        return true;
      }
    }
    return false;
  }
}
