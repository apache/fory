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

package org.apache.fory.builder;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import org.apache.fory.annotation.Internal;
import org.apache.fory.platform.internal._JDKAccess;

/** JDK25 helper for source-generated serializers that use per-field static VarHandles. */
@Internal
public final class VarHandleCodegenSupport {
  private VarHandleCodegenSupport() {}

  public static VarHandle getVarHandle(Field field) {
    try {
      Class<?> declaringClass = field.getDeclaringClass();
      // JDK25+ final-field writes require a target-class trusted lookup from _JDKAccess.
      // A normal private lookup returns a read-only VarHandle for final instance fields.
      return _JDKAccess._trustedLookup(declaringClass)
          .findVarHandle(declaringClass, field.getName(), field.getType());
    } catch (IllegalAccessException | NoSuchFieldException | RuntimeException e) {
      throw new IllegalStateException(
          "Cannot create VarHandle for field "
              + field
              + ". "
              + _JDKAccess.jdk25AccessMessage(),
          e);
    }
  }

  public static boolean getBoolean(VarHandle handle, Object bean) {
    return (boolean) handle.get(bean);
  }

  public static byte getByte(VarHandle handle, Object bean) {
    return (byte) handle.get(bean);
  }

  public static char getChar(VarHandle handle, Object bean) {
    return (char) handle.get(bean);
  }

  public static short getShort(VarHandle handle, Object bean) {
    return (short) handle.get(bean);
  }

  public static int getInt(VarHandle handle, Object bean) {
    return (int) handle.get(bean);
  }

  public static long getLong(VarHandle handle, Object bean) {
    return (long) handle.get(bean);
  }

  public static float getFloat(VarHandle handle, Object bean) {
    return (float) handle.get(bean);
  }

  public static double getDouble(VarHandle handle, Object bean) {
    return (double) handle.get(bean);
  }

  public static Object getObject(VarHandle handle, Object bean) {
    return handle.get(bean);
  }

  public static void setBoolean(VarHandle handle, Object bean, boolean value) {
    handle.set(bean, value);
  }

  public static void setByte(VarHandle handle, Object bean, byte value) {
    handle.set(bean, value);
  }

  public static void setChar(VarHandle handle, Object bean, char value) {
    handle.set(bean, value);
  }

  public static void setShort(VarHandle handle, Object bean, short value) {
    handle.set(bean, value);
  }

  public static void setInt(VarHandle handle, Object bean, int value) {
    handle.set(bean, value);
  }

  public static void setLong(VarHandle handle, Object bean, long value) {
    handle.set(bean, value);
  }

  public static void setFloat(VarHandle handle, Object bean, float value) {
    handle.set(bean, value);
  }

  public static void setDouble(VarHandle handle, Object bean, double value) {
    handle.set(bean, value);
  }

  public static void setObject(VarHandle handle, Object bean, Object value) {
    handle.set(bean, value);
  }
}
