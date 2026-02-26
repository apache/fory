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

package org.apache.fory.benchmark.state;

import org.apache.fory.benchmark.data.MediaContent;
import org.apache.fory.benchmark.data.Sample;
import org.apache.fory.benchmark.data.Struct;

public enum ObjectType {
  MEDIA_CONTENT,
  SAMPLE,
  STRUCT,
  STRUCT2;

  public static Object createObject(ObjectType objectType, boolean references) {
    switch (objectType) {
      case SAMPLE:
        return new Sample().populate(references);
      case MEDIA_CONTENT:
        return new MediaContent().populate(references);
      case STRUCT:
        return Struct.create(false);
      case STRUCT2:
        return Struct.create(true);
      default:
        throw new UnsupportedOperationException(String.valueOf(objectType));
    }
  }
}
