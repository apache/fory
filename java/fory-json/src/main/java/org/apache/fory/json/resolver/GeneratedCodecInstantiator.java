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

package org.apache.fory.json.resolver;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.meta.JsonFieldTable;
import org.apache.fory.platform.AndroidSupport;
import org.apache.fory.platform.internal._JDKAccess;

/** Invokes generated codec constructor contracts selected and owned by {@link JsonTypeResolver}. */
final class GeneratedCodecInstantiator {
  private GeneratedCodecInstantiator() {}

  @SuppressWarnings("unchecked")
  static StringWriterCodec<Object> instantiateStringWriter(
      Class<?> type, JsonFieldInfo[] fields, StringWriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(JsonFieldInfo[].class, StringWriterCodec[].class);
        constructor.setAccessible(true);
        return (StringWriterCodec<Object>) constructor.newInstance(fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class, JsonFieldInfo[].class, StringWriterCodec[].class));
      return (StringWriterCodec<Object>) constructor.invoke(fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON String writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static StringWriterCodec<Object> instantiateAnyStringWriter(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      StringWriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, StringWriterCodec[].class);
        constructor.setAccessible(true);
        return (StringWriterCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      StringWriterCodec[].class));
      return (StringWriterCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any String writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static StringWriterCodec<Object> instantiateAnyStringWriter(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      StringWriterCodec<Object>[] codecs,
      StringWriterCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldInfo[].class,
                StringWriterCodec[].class,
                StringWriterCodec.class);
        constructor.setAccessible(true);
        return (StringWriterCodec<Object>) constructor.newInstance(owner, fields, codecs, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      StringWriterCodec[].class,
                      StringWriterCodec.class));
      return (StringWriterCodec<Object>) constructor.invoke(owner, fields, codecs, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any String writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8WriterCodec<Object> instantiateUtf8Writer(
      Class<?> type, JsonFieldInfo[] fields, Utf8WriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(JsonFieldInfo[].class, Utf8WriterCodec[].class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class, JsonFieldInfo[].class, Utf8WriterCodec[].class));
      return (Utf8WriterCodec<Object>) constructor.invoke(fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF8 writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8WriterCodec<Object> instantiateAnyUtf8Writer(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf8WriterCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Utf8WriterCodec[].class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf8WriterCodec[].class));
      return (Utf8WriterCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8WriterCodec<Object> instantiateAnyUtf8Writer(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf8WriterCodec<Object>[] codecs,
      Utf8WriterCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldInfo[].class,
                Utf8WriterCodec[].class,
                Utf8WriterCodec.class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(owner, fields, codecs, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf8WriterCodec[].class,
                      Utf8WriterCodec.class));
      return (Utf8WriterCodec<Object>) constructor.invoke(owner, fields, codecs, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Latin1ReaderCodec<Object> instantiateLatin1Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Latin1ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Latin1ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Latin1ReaderCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Latin1ReaderCodec[].class));
      return (Latin1ReaderCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Latin1 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Latin1ReaderCodec<Object> instantiateAnyLatin1Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Latin1ReaderCodec<Object>[] codecs,
      Latin1ReaderCodec<Object> selfReader) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Latin1ReaderCodec[].class,
                Latin1ReaderCodec.class);
        constructor.setAccessible(true);
        return (Latin1ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, selfReader);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Latin1ReaderCodec[].class,
                      Latin1ReaderCodec.class));
      return (Latin1ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, selfReader);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any Latin1 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Latin1ReaderCodec<Object> instantiateAnyLatin1Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Latin1ReaderCodec<Object>[] codecs,
      Latin1ReaderCodec<Object> selfReader,
      Latin1ReaderCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Latin1ReaderCodec[].class,
                Latin1ReaderCodec.class,
                Latin1ReaderCodec.class);
        constructor.setAccessible(true);
        return (Latin1ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, selfReader, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Latin1ReaderCodec[].class,
                      Latin1ReaderCodec.class,
                      Latin1ReaderCodec.class));
      return (Latin1ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, selfReader, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any Latin1 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf16ReaderCodec<Object> instantiateUtf16Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf16ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Utf16ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Utf16ReaderCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf16ReaderCodec[].class));
      return (Utf16ReaderCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF16 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf16ReaderCodec<Object> instantiateAnyUtf16Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf16ReaderCodec<Object>[] codecs,
      Utf16ReaderCodec<Object> selfReader) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf16ReaderCodec[].class,
                Utf16ReaderCodec.class);
        constructor.setAccessible(true);
        return (Utf16ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, selfReader);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf16ReaderCodec[].class,
                      Utf16ReaderCodec.class));
      return (Utf16ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, selfReader);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF16 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf16ReaderCodec<Object> instantiateAnyUtf16Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf16ReaderCodec<Object>[] codecs,
      Utf16ReaderCodec<Object> selfReader,
      Utf16ReaderCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf16ReaderCodec[].class,
                Utf16ReaderCodec.class,
                Utf16ReaderCodec.class);
        constructor.setAccessible(true);
        return (Utf16ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, selfReader, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf16ReaderCodec[].class,
                      Utf16ReaderCodec.class,
                      Utf16ReaderCodec.class));
      return (Utf16ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, selfReader, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF16 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8ReaderCodec<Object> instantiateUtf8Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldInfo[] fields,
      Utf8ReaderCodec<Object>[] codecs) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class, JsonFieldInfo[].class, Utf8ReaderCodec[].class);
        constructor.setAccessible(true);
        return (Utf8ReaderCodec<Object>) constructor.newInstance(owner, fields, codecs);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldInfo[].class,
                      Utf8ReaderCodec[].class));
      return (Utf8ReaderCodec<Object>) constructor.invoke(owner, fields, codecs);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF8 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8WriterCodec<Object> instantiateUtf8CollectionWriter(
      Class<?> type, Utf8WriterCodec<Object> fallback) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor = type.getDeclaredConstructor(Utf8WriterCodec.class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(fallback);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(type, MethodType.methodType(void.class, Utf8WriterCodec.class));
      return (Utf8WriterCodec<Object>) constructor.invoke(fallback);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF8 collection writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8WriterCodec<Object> instantiateUtf8CollectionWriter(
      Class<?> type, Utf8WriterCodec<Object> fallback, Utf8WriterCodec<Object> elementWriter) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(Utf8WriterCodec.class, Utf8WriterCodec.class);
        constructor.setAccessible(true);
        return (Utf8WriterCodec<Object>) constructor.newInstance(fallback, elementWriter);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(void.class, Utf8WriterCodec.class, Utf8WriterCodec.class));
      return (Utf8WriterCodec<Object>) constructor.invoke(fallback, elementWriter);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF8 collection writer", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8ReaderCodec<Object> instantiateUtf8CollectionReader(
      Class<?> type, Utf8ReaderCodec<Object> elementReader) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor = type.getDeclaredConstructor(Utf8ReaderCodec.class);
        constructor.setAccessible(true);
        return (Utf8ReaderCodec<Object>) constructor.newInstance(elementReader);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(type, MethodType.methodType(void.class, Utf8ReaderCodec.class));
      return (Utf8ReaderCodec<Object>) constructor.invoke(elementReader);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON UTF8 collection reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8ReaderCodec<Object> instantiateAnyUtf8Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf8ReaderCodec<Object>[] codecs,
      Utf8ReaderCodec<Object> selfReader) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf8ReaderCodec[].class,
                Utf8ReaderCodec.class);
        constructor.setAccessible(true);
        return (Utf8ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, selfReader);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf8ReaderCodec[].class,
                      Utf8ReaderCodec.class));
      return (Utf8ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, selfReader);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 reader", e);
    }
  }

  @SuppressWarnings("unchecked")
  static Utf8ReaderCodec<Object> instantiateAnyUtf8Reader(
      Class<?> type,
      ObjectCodec<?> owner,
      JsonFieldTable readTable,
      JsonFieldInfo[] fields,
      Utf8ReaderCodec<Object>[] codecs,
      Utf8ReaderCodec<Object> selfReader,
      Utf8ReaderCodec<Object> anyCodec) {
    try {
      if (AndroidSupport.IS_ANDROID) {
        Constructor<?> constructor =
            type.getDeclaredConstructor(
                ObjectCodec.class,
                JsonFieldTable.class,
                JsonFieldInfo[].class,
                Utf8ReaderCodec[].class,
                Utf8ReaderCodec.class,
                Utf8ReaderCodec.class);
        constructor.setAccessible(true);
        return (Utf8ReaderCodec<Object>)
            constructor.newInstance(owner, readTable, fields, codecs, selfReader, anyCodec);
      }
      MethodHandle constructor =
          _JDKAccess._trustedLookup(type)
              .findConstructor(
                  type,
                  MethodType.methodType(
                      void.class,
                      ObjectCodec.class,
                      JsonFieldTable.class,
                      JsonFieldInfo[].class,
                      Utf8ReaderCodec[].class,
                      Utf8ReaderCodec.class,
                      Utf8ReaderCodec.class));
      return (Utf8ReaderCodec<Object>)
          constructor.invoke(owner, readTable, fields, codecs, selfReader, anyCodec);
    } catch (Throwable e) {
      throw new ForyJsonException("Cannot instantiate generated JSON Any UTF8 reader", e);
    }
  }
}
