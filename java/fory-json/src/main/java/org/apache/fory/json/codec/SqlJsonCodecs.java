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
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Optional codecs for {@code java.sql} date/time values represented as epoch milliseconds.
 *
 * <p>SQL classes are discovered by name so the main codec registry does not acquire mandatory
 * descriptors for an optional module. Each available class receives an exact codec whose cached
 * {@code long} constructor owns reconstruction; subclasses are not accepted through this mapping.
 */
public final class SqlJsonCodecs {
  private static final String SQL_DATE = "java.sql.Date";
  private static final String SQL_TIME = "java.sql.Time";
  private static final String SQL_TIMESTAMP = "java.sql.Timestamp";

  private SqlJsonCodecs() {}

  public static void register(IdentityHashMap<Class<?>, JsonCodec<?>> codecs) {
    register(codecs, SQL_DATE);
    register(codecs, SQL_TIME);
    register(codecs, SQL_TIMESTAMP);
  }

  private static void register(IdentityHashMap<Class<?>, JsonCodec<?>> codecs, String className) {
    Class<? extends Date> type = loadClass(className);
    if (type != null) {
      codecs.put(type, new SqlMillisCodec<>(type));
    }
  }

  private static Class<? extends Date> loadClass(String className) {
    try {
      return Class.forName(className, false, SqlJsonCodecs.class.getClassLoader())
          .asSubclass(Date.class);
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
  }

  private static final class SqlMillisCodec<T extends Date> implements JsonCodec<T> {
    private final Constructor<T> constructor;

    private SqlMillisCodec(Class<T> type) {
      try {
        constructor = type.getConstructor(long.class);
      } catch (NoSuchMethodException e) {
        throw new ForyJsonException("Cannot access SQL JSON type " + type.getName(), e);
      }
    }

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.getTime());
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeLong(value.getTime());
      }
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : newSqlValue(reader.readLong());
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : newSqlValue(reader.readLong());
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : newSqlValue(reader.readLong());
    }

    private T newSqlValue(long millis) {
      try {
        return constructor.newInstance(millis);
      } catch (ReflectiveOperationException e) {
        throw new ForyJsonException(
            "Cannot create SQL JSON type " + constructor.getDeclaringClass(), e);
      }
    }
  }
}
