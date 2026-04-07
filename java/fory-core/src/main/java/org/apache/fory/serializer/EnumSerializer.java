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

package org.apache.fory.serializer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.util.Preconditions;

@SuppressWarnings("rawtypes")
public class EnumSerializer extends ImmutableSerializer<Enum> {
  private final Config config;
  private final Enum[] enumConstants;
  private final Map<String, Enum> stringToEnum;

  public EnumSerializer(Config config, Class<Enum> cls) {
    super(config, cls, false);
    this.config = config;
    if (cls.isEnum()) {
      enumConstants = cls.getEnumConstants();
    } else {
      Preconditions.checkArgument(Enum.class.isAssignableFrom(cls) && cls != Enum.class);
      @SuppressWarnings("unchecked")
      Class<Enum> enclosingClass = (Class<Enum>) cls.getEnclosingClass();
      Preconditions.checkNotNull(enclosingClass);
      Preconditions.checkArgument(enclosingClass.isEnum());
      enumConstants = enclosingClass.getEnumConstants();
    }
    if (config.serializeEnumByName()) {
      stringToEnum = new HashMap<>();
      for (Enum enumConstant : enumConstants) {
        stringToEnum.put(enumConstant.name(), enumConstant);
      }
    } else {
      stringToEnum = null;
    }
  }

  @Override
  public void write(WriteContext writeContext, Enum value) {
    if (!config.isXlang() && config.serializeEnumByName()) {
      writeContext.writeString(value.name());
    } else {
      writeContext.getBuffer().writeVarUint32Small7(value.ordinal());
    }
  }

  @Override
  public Enum read(ReadContext readContext) {
    if (!config.isXlang() && config.serializeEnumByName()) {
      String name = readContext.readString();
      Enum e = stringToEnum.get(name);
      if (e != null) {
        return e;
      }
      return handleUnknownEnumValue(name);
    } else {
      int value = readContext.getBuffer().readVarUint32Small7();
      if (value >= enumConstants.length) {
        return handleUnknownEnumValue(value);
      }
      return enumConstants[value];
    }
  }

  private Enum handleUnknownEnumValue(int value) {
    switch (config.getUnknownEnumValueStrategy()) {
      case RETURN_NULL:
        return null;
      case RETURN_FIRST_VARIANT:
        return enumConstants[0];
      case RETURN_LAST_VARIANT:
        return enumConstants[enumConstants.length - 1];
      default:
        throw new IllegalArgumentException(
            String.format("Enum ordinal %s not in %s", value, Arrays.toString(enumConstants)));
    }
  }

  private Enum handleUnknownEnumValue(String value) {
    switch (config.getUnknownEnumValueStrategy()) {
      case RETURN_NULL:
        return null;
      case RETURN_FIRST_VARIANT:
        return enumConstants[0];
      case RETURN_LAST_VARIANT:
        return enumConstants[enumConstants.length - 1];
      default:
        throw new IllegalArgumentException(
            String.format("Enum string %s not in %s", value, Arrays.toString(enumConstants)));
    }
  }

  @Override
  public boolean shareable() {
    return config.forVirtualThread();
  }
}
