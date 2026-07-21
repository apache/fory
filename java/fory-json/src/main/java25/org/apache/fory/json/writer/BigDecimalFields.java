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

import java.lang.invoke.VarHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.apache.fory.platform.internal._JDKAccess;

/**
 * JDK 25+ access to {@link BigDecimal}'s stored representation through static-final {@link
 * VarHandle} values that C2 can treat as constants.
 *
 * <p>This multi-release owner keeps private-field lookup out of writer hot paths and contains no
 * dependency on the JDK 8-24 field-access implementation. If lookup is unavailable, exact base
 * values may use {@link BigDecimal#scale()}, while inflated subtypes are rejected.
 */
final class BigDecimalFields {
  static final long INFLATED = Long.MIN_VALUE;
  private static final VarHandle INT_COMPACT = fieldHandle("intCompact", long.class);
  private static final VarHandle INT_VAL = fieldHandle("intVal", BigInteger.class);
  private static final VarHandle SCALE = fieldHandle("scale", int.class);

  private BigDecimalFields() {}

  static long compactValue(BigDecimal value) {
    VarHandle handle = INT_COMPACT;
    return handle == null || SCALE == null ? INFLATED : (long) handle.get(value);
  }

  static boolean isCompact(long value) {
    return value != INFLATED;
  }

  static boolean canReadInflatedValue() {
    return INT_COMPACT != null && INT_VAL != null && SCALE != null;
  }

  static BigInteger inflatedValue(BigDecimal value) {
    return (BigInteger) INT_VAL.get(value);
  }

  static int scale(BigDecimal value) {
    VarHandle handle = SCALE;
    return handle == null ? value.scale() : (int) handle.get(value);
  }

  private static VarHandle fieldHandle(String name, Class<?> type) {
    try {
      return _JDKAccess._trustedLookup(BigDecimal.class).findVarHandle(BigDecimal.class, name, type);
    } catch (ThreadDeath e) {
      throw e;
    } catch (VirtualMachineError e) {
      throw e;
    } catch (Throwable e) {
      return null;
    }
  }
}
