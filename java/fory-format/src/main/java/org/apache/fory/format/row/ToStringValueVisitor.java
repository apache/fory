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

import java.util.function.Function;
import org.apache.arrow.vector.types.pojo.ArrowType;

class ToStringValueVisitor extends ValueVisitor {
  ToStringValueVisitor(final Getters getters) {
    super(getters);
  }

  @Override
  public Function<Integer, Object> visit(final ArrowType.Binary type) {
    return super.visit(type).andThen(this::binaryToString);
  }

  @Override
  public Function<Integer, Object> visit(final ArrowType.FixedSizeBinary type) {
    return super.visit(type).andThen(this::binaryToString);
  }

  private String binaryToString(final Object obj) {
    final byte[] bytes = (byte[]) obj;
    final int clampedLen = Math.min(bytes.length, 256);
    final StringBuilder result = new StringBuilder(clampedLen * 2 + 5);
    result.append("0x");
    for (int i = 0; i < clampedLen; i++) {
      final String hexStr = Integer.toHexString(bytes[i] & 0xff);
      if (hexStr.length() == 1) {
        result.append('0');
      }
      result.append(hexStr);
    }
    if (bytes.length > clampedLen) {
      result.append("...");
    }
    return result.toString();
  }
}
