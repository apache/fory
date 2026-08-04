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

package org.apache.fory.kotlin.xlang;

import java.util.List;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.Ref;
import org.apache.fory.annotation.UInt32Type;

/** Java-carrier writer used to verify generated Kotlin compatible container reads. */
public final class KotlinCompatibleUIntListWriter {
  @ForyField(id = 1)
  @Ref
  public List<@UInt32Type Long> first;

  @ForyField(id = 2)
  @Ref
  public List<@UInt32Type Long> second;

  public KotlinCompatibleUIntListWriter() {}

  public KotlinCompatibleUIntListWriter(List<Long> first, List<Long> second) {
    this.first = first;
    this.second = second;
  }
}
