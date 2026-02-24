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
import org.apache.fory.Fory;
import org.apache.fory.collection.ForyObjectMap;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.Encoders;
import org.apache.fory.meta.MetaString;
import org.apache.fory.resolver.MetaStringBytes;
import org.apache.fory.resolver.MetaStringResolver;
import org.apache.fory.util.Preconditions;

@SuppressWarnings("rawtypes")
public class EnumSerializer extends ImmutableSerializer<Enum> {
  private final MetaStringResolver metaStringResolver;
  private final Enum[] enumConstants;
  private final ForyObjectMap<MetaStringBytes, Enum> metaStringtoEnumRepresentation;
  private final MetaStringBytes[] metaStringBytesArrByEnumOrdinal;

  public EnumSerializer(Fory fory, Class<Enum> cls) {
    super(fory, cls, false);
    metaStringResolver = fory.getMetaStringResolver();
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

    metaStringBytesArrByEnumOrdinal = new MetaStringBytes[enumConstants.length];

    if (fory.getConfig().serializeEnumByName()) {
      // as we know the size of enum is fixed, initialize the size of map with that value
      int initialCapacity = (int) Math.ceil(enumConstants.length / 0.5f);

      metaStringtoEnumRepresentation = new ForyObjectMap<>(initialCapacity, 0.5f);

      for (Enum enumConstant : enumConstants) {
        if (enumConstant != null) {
          MetaString ms = Encoders.GENERIC_ENCODER.encode(enumConstant.name());
          MetaStringBytes msb = metaStringResolver.getOrCreateMetaStringBytes(ms);
          metaStringtoEnumRepresentation.put(msb, enumConstant);
          metaStringBytesArrByEnumOrdinal[enumConstant.ordinal()] = msb;
        }
      }

    } else {
      metaStringtoEnumRepresentation = null;
    }
  }

  @Override
  public void write(MemoryBuffer buffer, Enum value) {
    if (isJava && fory.getConfig().serializeEnumByName()) {
      MetaStringBytes metaStringBytes = metaStringBytesArrByEnumOrdinal[value.ordinal()];
      metaStringResolver.writeMetaStringBytes(buffer, metaStringBytes);
    } else {
      buffer.writeVarUint32Small7(value.ordinal());
    }
  }

  @Override
  public Enum read(MemoryBuffer buffer) {
    if (isJava && fory.getConfig().serializeEnumByName()) {
      MetaStringBytes metaStringBytes = metaStringResolver.readMetaStringBytes(buffer);
      Enum e = metaStringtoEnumRepresentation.get(metaStringBytes);
      if (e != null) {
        return e;
      }
      return handleUnknownEnumValue(metaStringBytes.decode(Encoders.GENERIC_DECODER));
    } else {
      int value = buffer.readVarUint32Small7();
      if (value >= enumConstants.length) {
        return handleUnknownEnumValue(value);
      }
      return enumConstants[value];
    }
  }

  private Enum handleUnknownEnumValue(int value) {
    switch (fory.getConfig().getUnknownEnumValueStrategy()) {
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
    switch (fory.getConfig().getUnknownEnumValueStrategy()) {
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
}
