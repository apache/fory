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

package org.apache.fory.json.codec;

import java.lang.reflect.Constructor;
import java.util.Date;
import java.util.IdentityHashMap;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Optional SQL JSON codecs loaded only through class-name based registration. */
public final class SqlJsonCodecs {
  private static final String SQL_DATE = "java.sql.Date";
  private static final String SQL_TIME = "java.sql.Time";
  private static final String SQL_TIMESTAMP = "java.sql.Timestamp";

  private SqlJsonCodecs() {}

  public static void register(IdentityHashMap<Class<?>, JsonCodec> codecs) {
    register(codecs, SQL_DATE);
    register(codecs, SQL_TIME);
    register(codecs, SQL_TIMESTAMP);
  }

  private static void register(IdentityHashMap<Class<?>, JsonCodec> codecs, String className) {
    Class<?> type = loadClass(className);
    if (type != null) {
      codecs.put(type, new SqlMillisCodec(type));
    }
  }

  private static Class<?> loadClass(String className) {
    try {
      return Class.forName(className, false, SqlJsonCodecs.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
  }

  private static final class SqlMillisCodec extends AbstractJsonCodec {
    private final Constructor<?> constructor;

    private SqlMillisCodec(Class<?> type) {
      try {
        constructor = type.getConstructor(long.class);
      } catch (NoSuchMethodException e) {
        throw new ForyJsonException("Cannot access SQL JSON type " + type.getName(), e);
      }
    }

    @Override
    void writeNonNull(JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((Date) value).getTime());
    }

    @Override
    void writeUtf8NonNull(Utf8JsonWriter writer, Object value, JsonTypeResolver resolver) {
      writer.writeLong(((Date) value).getTime());
    }

    @Override
    Object readNonNull(JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver) {
      try {
        return constructor.newInstance(reader.readLong());
      } catch (ReflectiveOperationException e) {
        throw new ForyJsonException("Cannot create SQL JSON type " + typeInfo.rawType(), e);
      }
    }
  }
}
