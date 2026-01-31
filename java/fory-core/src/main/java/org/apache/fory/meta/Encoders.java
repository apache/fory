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

package org.apache.fory.meta;

import static org.apache.fory.meta.MetaString.Encoding.ALL_TO_LOWER_SPECIAL;
import static org.apache.fory.meta.MetaString.Encoding.EXTENDED;
import static org.apache.fory.meta.MetaString.Encoding.FIRST_TO_LOWER_SPECIAL;
import static org.apache.fory.meta.MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.fory.collection.Tuple2;
import org.apache.fory.meta.MetaString.Encoding;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.type.TypeUtils;
import org.apache.fory.util.StringUtils;

/** A class used to encode package/class/field name. */
public class Encoders {
  public static final MetaStringEncoder GENERIC_ENCODER = new MetaStringEncoder('.', '_');
  public static final MetaStringDecoder GENERIC_DECODER = new MetaStringDecoder('.', '_');
  public static final MetaStringEncoder PACKAGE_ENCODER = GENERIC_ENCODER;
  public static final MetaStringDecoder PACKAGE_DECODER = GENERIC_DECODER;
  public static final MetaStringEncoder TYPE_NAME_ENCODER = new MetaStringEncoder('$', '_');
  public static final MetaStringDecoder TYPE_NAME_DECODER = new MetaStringDecoder('$', '_');
  public static final String ARRAY_PREFIX = "1";
  public static final String ENUM_PREFIX = "2";
  static final MetaStringEncoder FIELD_NAME_ENCODER = new MetaStringEncoder('$', '_');
  static final MetaStringDecoder FIELD_NAME_DECODER = new MetaStringDecoder('$', '_');
  private static final ConcurrentMap<String, MetaString> pgkMetaStringCache =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<String, MetaString> typeMetaStringCache =
      new ConcurrentHashMap<>();
  private static final ConcurrentMap<String, MetaString> fieldMetaStringCache =
      new ConcurrentHashMap<>();
  static final Encoding[] pkgEncodings =
      new Encoding[] {EXTENDED, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL};
  static final List<Encoding> pkgEncodingsList = Arrays.asList(pkgEncodings);

  static final Encoding[] typeNameEncodings =
      new Encoding[] {
        EXTENDED, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL, FIRST_TO_LOWER_SPECIAL
      };
  static final List<Encoding> typeNameEncodingsList = Arrays.asList(typeNameEncodings);

  static final Encoding[] fieldNameEncodings =
      new Encoding[] {EXTENDED, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL};
  static final List<Encoding> fieldNameEncodingsList = Arrays.asList(fieldNameEncodings);

  public static MetaString encodePackage(String pkg) {
    return pgkMetaStringCache.computeIfAbsent(pkg, k -> PACKAGE_ENCODER.encode(pkg, pkgEncodings));
  }

  public static MetaString encodeTypeName(String typeName) {
    return typeMetaStringCache.computeIfAbsent(
        typeName, k -> TYPE_NAME_ENCODER.encode(typeName, typeNameEncodings));
  }

  public static Tuple2<String, String> encodePkgAndClass(Class<?> cls) {
    String packageName = ReflectionUtils.getPackage(cls);
    String className = ReflectionUtils.getClassNameWithoutPackage(cls);
    if (cls.isArray()) {
      Tuple2<Class<?>, Integer> componentInfo = TypeUtils.getArrayComponentInfo(cls);
      Class<?> ctype = componentInfo.f0;
      if (!ctype.isPrimitive()) { // primitive array has special format like [[[III.
        String componentName = ctype.getName();
        packageName = ReflectionUtils.getPackage(componentName);
        String componentSimpleName = ReflectionUtils.getClassNameWithoutPackage(componentName);
        String prefix = StringUtils.repeat(Encoders.ARRAY_PREFIX, componentInfo.f1);
        if (ctype.isEnum()) {
          className = prefix + Encoders.ENUM_PREFIX + componentSimpleName;
        } else {
          className = prefix + componentSimpleName;
        }
      }
    } else if (cls.isEnum()) {
      className = Encoders.ENUM_PREFIX + className;
    }
    return Tuple2.of(packageName, className);
  }

  public static ClassSpec buildClassSpec(Class<?> cls) {
    if (cls.isArray()) {
      Tuple2<Class<?>, Integer> info = TypeUtils.getArrayComponentInfo(cls);
      return new ClassSpec(cls.getName(), info.f0.isEnum(), true, info.f1);
    } else {
      return new ClassSpec(cls.getName(), cls.isEnum(), false, 0);
    }
  }

  public static ClassSpec decodePkgAndClass(String packageName, String className) {
    String rawPkg = packageName;
    boolean isArray = className.startsWith(Encoders.ARRAY_PREFIX);
    int dimension = 0;
    if (isArray) {
      while (className.charAt(dimension) == Encoders.ARRAY_PREFIX.charAt(0)) {
        dimension++;
      }
      packageName = StringUtils.repeat("[", dimension) + "L" + packageName;
      className = className.substring(dimension) + ";";
    }
    boolean isEnum = className.startsWith(Encoders.ENUM_PREFIX);
    if (isEnum) {
      className = className.substring(1);
    }
    String entireClassName;
    if (StringUtils.isBlank(rawPkg)) {
      if (isArray) {
        entireClassName = packageName + className;
      } else {
        entireClassName = className;
      }
    } else {
      entireClassName = packageName + "." + className;
    }
    return new ClassSpec(entireClassName, isEnum, isArray, dimension);
  }

  public static MetaString encodeFieldName(String fieldName) {
    return fieldMetaStringCache.computeIfAbsent(
        fieldName, k -> FIELD_NAME_ENCODER.encode(fieldName, fieldNameEncodings));
  }
}
