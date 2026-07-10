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

package org.apache.fory.json.writer;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.apache.fory.reflect.FieldAccessor;

/** Reads BigDecimal's stored representation without invoking overridable accessors. */
final class BigDecimalFields {
  static final long INFLATED = Long.MIN_VALUE;
  private static final FieldAccessor INT_COMPACT = fieldAccessor("intCompact", long.class);
  private static final FieldAccessor INT_VAL = fieldAccessor("intVal", BigInteger.class);
  private static final FieldAccessor SCALE = fieldAccessor("scale", int.class);

  private BigDecimalFields() {}

  static long compactValue(BigDecimal value) {
    FieldAccessor accessor = INT_COMPACT;
    return accessor == null || SCALE == null ? INFLATED : accessor.getLong(value);
  }

  static boolean isCompact(long value) {
    return value != INFLATED;
  }

  static boolean canReadInflatedValue() {
    return INT_COMPACT != null && INT_VAL != null && SCALE != null;
  }

  static BigInteger inflatedValue(BigDecimal value) {
    return (BigInteger) INT_VAL.getObject(value);
  }

  static int scale(BigDecimal value) {
    FieldAccessor accessor = SCALE;
    return accessor == null ? value.scale() : accessor.getInt(value);
  }

  private static FieldAccessor fieldAccessor(String name, Class<?> type) {
    try {
      Field field = BigDecimal.class.getDeclaredField(name);
      if (field.getType() != type) {
        return null;
      }
      return FieldAccessor.createAccessor(field);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      return null;
    }
  }
}
