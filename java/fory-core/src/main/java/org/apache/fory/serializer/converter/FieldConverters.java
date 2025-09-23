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

package org.apache.fory.serializer.converter;

import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Field;
import java.util.Set;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.type.TypeUtils;

/**
 * Factory class for creating field converters that handle type conversions between different data
 * types. This class provides converters for primitive types and their boxed counterparts, enabling
 * automatic type conversion during serialization/deserialization processes.
 */
public class FieldConverters {

  /**
   * Creates an appropriate field converter based on the target field type and source object type.
   *
   * @param from the source object type to convert from
   * @param field the target field to convert to
   * @return a FieldConverter instance that can handle the conversion, or null if no compatible
   *     converter exists
   */
  public static FieldConverter<?> getConverter(Class<?> from, Field field) {
    Class<?> to = field.getType();
    from = TypeUtils.wrap(from);
    // Handle primitive int conversions
    if (to == int.class) {
      if (IntConverter.compatibleTypes.contains(from)) {
        return new IntConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == Integer.class) {
      // Handle boxed Integer conversions
      if (IntConverter.compatibleTypes.contains(from)) {
        return new BoxedIntConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == boolean.class) {
      // Handle primitive boolean conversions
      if (BooleanConverter.compatibleTypes.contains(from)) {
        return new BooleanConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == Boolean.class) {
      // Handle boxed Boolean conversions
      if (BooleanConverter.compatibleTypes.contains(from)) {
        return new BoxedBooleanConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == byte.class) {
      // Handle primitive byte conversions
      if (ByteConverter.compatibleTypes.contains(from)) {
        return new ByteConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == Byte.class) {
      // Handle boxed Byte conversions
      if (ByteConverter.compatibleTypes.contains(from)) {
        return new BoxedByteConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == short.class) {
      // Handle primitive short conversions
      if (ShortConverter.compatibleTypes.contains(from)) {
        return new ShortConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == Short.class) {
      // Handle boxed Short conversions
      if (ShortConverter.compatibleTypes.contains(from)) {
        return new BoxedShortConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == long.class) {
      // Handle primitive long conversions
      if (LongConverter.compatibleTypes.contains(from)) {
        return new LongConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == Long.class) {
      // Handle boxed Long conversions
      if (LongConverter.compatibleTypes.contains(from)) {
        return new BoxedLongConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == float.class) {
      // Handle primitive float conversions
      if (FloatConverter.compatibleTypes.contains(from)) {
        return new FloatConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == Float.class) {
      // Handle boxed Float conversions
      if (FloatConverter.compatibleTypes.contains(from)) {
        return new BoxedFloatConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == double.class) {
      // Handle primitive double conversions
      if (DoubleConverter.compatibleTypes.contains(from)) {
        return new DoubleConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == Double.class) {
      // Handle boxed Double conversions
      if (DoubleConverter.compatibleTypes.contains(from)) {
        return new BoxedDoubleConverter(FieldAccessor.createAccessor(field));
      }
    } else if (to == String.class) {
      // Handle String conversions
      if (StringConverter.compatibleTypes.contains(from)) {
        return new StringConverter(FieldAccessor.createAccessor(field));
      }
    }

    return null; // No compatible converter found
  }

  /**
   * Converter for primitive boolean fields. Converts compatible types to boolean values. Returns
   * false for null values and incompatible types.
   */
  public static class BooleanConverter extends FieldConverter<Boolean> {
    static Set<Class<?>> compatibleTypes = ImmutableSet.of(String.class, Boolean.class);

    protected BooleanConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a boolean value.
     *
     * @param from the object to convert
     * @return the converted boolean value
     */
    public static Boolean convertFrom(Object from) {
      if (from == null) {
        return false;
      }
      if (from instanceof Boolean) {
        return (Boolean) from;
      } else if (from instanceof String) {
        return Boolean.parseBoolean((String) from);
      } else {
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public Boolean convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for boxed Boolean fields. Converts compatible types to Boolean values. Returns null
   * for null values, unlike the primitive version.
   */
  public static class BoxedBooleanConverter extends FieldConverter<Boolean> {
    protected BoxedBooleanConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a Boolean value.
     *
     * @param from the object to convert
     * @return the converted Boolean value, or null if from is null
     */
    public static Boolean convertFrom(Object from) {
      if (from == null) {
        return null;
      }
      return BooleanConverter.convertFrom(from);
    }

    @Override
    public Boolean convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for primitive byte fields. Converts compatible types to byte values. Returns 0 for
   * null values.
   */
  public static class ByteConverter extends FieldConverter<Byte> {
    static Set<Class<?>> compatibleTypes =
        ImmutableSet.of(String.class, Integer.class, Long.class, Short.class, Byte.class);

    protected ByteConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a byte value.
     *
     * @param from the object to convert
     * @return the converted byte value
     * @throws NumberFormatException if the string cannot be parsed as a byte
     * @throws ArithmeticException if the numeric value is out of byte range
     */
    public static Byte convertFrom(Object from) {
      if (from == null) {
        return 0;
      }
      if (from instanceof Byte) {
        return (Byte) from;
      } else if (from instanceof Integer) {
        return ((Integer) from).byteValue();
      } else if (from instanceof Long) {
        return ((Long) from).byteValue();
      } else if (from instanceof Short) {
        return ((Short) from).byteValue();
      } else if (from instanceof String) {
        return Byte.parseByte((String) from);
      } else {
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public Byte convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for boxed Byte fields. Converts compatible types to Byte values. Returns null for
   * null values, unlike the primitive version.
   */
  public static class BoxedByteConverter extends FieldConverter<Byte> {
    protected BoxedByteConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a Byte value.
     *
     * @param from the object to convert
     * @return the converted Byte value, or null if from is null
     */
    public static Byte convertFrom(Object from) {
      if (from == null) {
        return null;
      }
      return ByteConverter.convertFrom(from);
    }

    @Override
    public Byte convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for primitive short fields. Converts compatible types to short values. Returns 0 for
   * null values.
   */
  public static class ShortConverter extends FieldConverter<Short> {
    static Set<Class<?>> compatibleTypes =
        ImmutableSet.of(String.class, Integer.class, Long.class, Short.class);

    protected ShortConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a short value.
     *
     * @param from the object to convert
     * @return the converted short value
     * @throws NumberFormatException if the string cannot be parsed as a short
     * @throws ArithmeticException if the numeric value is out of short range
     */
    public static Short convertFrom(Object from) {
      if (from == null) {
        return 0;
      }
      if (from instanceof Short) {
        return (Short) from;
      } else if (from instanceof Integer) {
        return ((Integer) from).shortValue();
      } else if (from instanceof Long) {
        return ((Long) from).shortValue();
      } else if (from instanceof String) {
        return Short.parseShort((String) from);
      } else {
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public Short convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for boxed Short fields. Converts compatible types to Short values. Returns null for
   * null values, unlike the primitive version.
   */
  public static class BoxedShortConverter extends FieldConverter<Short> {
    protected BoxedShortConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    public static Short convertFrom(Object from) {
      if (from == null) {
        return null;
      }
      return ShortConverter.convertFrom(from);
    }

    @Override
    public Short convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for primitive int fields. Converts compatible types to int values. Returns 0 for null
   * values.
   */
  public static class IntConverter extends FieldConverter<Integer> {
    static Set<Class<?>> compatibleTypes = ImmutableSet.of(String.class, Long.class, Integer.class);

    protected IntConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to an int value.
     *
     * @param from the object to convert
     * @return the converted int value
     * @throws NumberFormatException if the string cannot be parsed as an int
     * @throws ArithmeticException if the numeric value is out of int range
     */
    public static Integer convertFrom(Object from) {
      if (from == null) {
        return 0;
      }
      if (from instanceof Long) {
        return Math.toIntExact((Long) from);
      } else if (from instanceof Integer) {
        return (Integer) from;
      } else if (from instanceof String) {
        return Integer.parseInt((String) from);
      } else {
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public Integer convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for boxed Integer fields. Converts compatible types to Integer values. Returns null
   * for null values, unlike the primitive version.
   */
  public static class BoxedIntConverter extends FieldConverter<Integer> {
    protected BoxedIntConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to an Integer value.
     *
     * @param from the object to convert
     * @return the converted Integer value, or null if from is null
     */
    public static Integer convertFrom(Object from) {
      if (from == null) {
        return null;
      }
      return IntConverter.convertFrom(from);
    }

    @Override
    public Integer convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for primitive long fields. Converts compatible types to long values. Returns 0 for
   * null values.
   */
  public static class LongConverter extends FieldConverter<Long> {
    static Set<Class<?>> compatibleTypes = ImmutableSet.of(String.class, Long.class);

    protected LongConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a long value.
     *
     * @param from the object to convert
     * @return the converted long value
     * @throws NumberFormatException if the string cannot be parsed as a long
     */
    public static Long convertFrom(Object from) {
      if (from == null) {
        return 0L;
      }
      if (from instanceof Long) {
        return (Long) from;
      } else if (from instanceof String) {
        return Long.parseLong((String) from);
      } else {
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public Long convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for boxed Long fields. Converts compatible types to Long values. Returns null for
   * null values, unlike the primitive version.
   */
  public static class BoxedLongConverter extends FieldConverter<Long> {
    protected BoxedLongConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a Long value.
     *
     * @param from the object to convert
     * @return the converted Long value, or null if from is null
     */
    public static Long convertFrom(Object from) {
      if (from == null) {
        return null;
      }
      return LongConverter.convertFrom(from);
    }

    @Override
    public Long convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for primitive float fields. Converts compatible types to float values. Returns 0.0f
   * for null values. Only allows conversion from String.
   */
  public static class FloatConverter extends FieldConverter<Float> {
    static Set<Class<?>> compatibleTypes = ImmutableSet.of(String.class, Float.class);

    protected FloatConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a float value.
     *
     * @param from the object to convert
     * @return the converted float value
     * @throws NumberFormatException if the string cannot be parsed as a float
     */
    public static Float convertFrom(Object from) {
      if (from == null) {
        return 0.0f;
      }
      if (from instanceof String) {
        return Float.parseFloat((String) from);
      } else if (from instanceof Float) {
        return (Float) from;
      } else {
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public Float convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for boxed Float fields. Converts compatible types to Float values. Returns null for
   * null values, unlike the primitive version. Only allows conversion from String.
   */
  public static class BoxedFloatConverter extends FieldConverter<Float> {
    protected BoxedFloatConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a Float value.
     *
     * @param from the object to convert
     * @return the converted Float value, or null if from is null
     */
    public static Float convertFrom(Object from) {
      if (from == null) {
        return null;
      }
      return FloatConverter.convertFrom(from);
    }

    @Override
    public Float convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for primitive double fields. Converts compatible types to double values. Returns 0.0
   * for null values. Allows conversion from String and Float.
   */
  public static class DoubleConverter extends FieldConverter<Double> {
    static Set<Class<?>> compatibleTypes = ImmutableSet.of(String.class, Float.class, Double.class);

    protected DoubleConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a double value.
     *
     * @param from the object to convert
     * @return the converted double value
     * @throws NumberFormatException if the string cannot be parsed as a double
     */
    public static Double convertFrom(Object from) {
      if (from == null) {
        return 0.0;
      }
      if (from instanceof String) {
        return Double.parseDouble((String) from);
      } else if (from instanceof Double) {
        return (Double) from;
      } else if (from instanceof Float) {
        return ((Float) from).doubleValue();
      } else {
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public Double convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for boxed Double fields. Converts compatible types to Double values. Returns null for
   * null values, unlike the primitive version. Allows conversion from String and Float.
   */
  public static class BoxedDoubleConverter extends FieldConverter<Double> {
    protected BoxedDoubleConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a Double value.
     *
     * @param from the object to convert
     * @return the converted Double value, or null if from is null
     */
    public static Double convertFrom(Object from) {
      if (from == null) {
        return null;
      }
      return DoubleConverter.convertFrom(from);
    }

    @Override
    public Double convert(Object from) {
      return convertFrom(from);
    }
  }

  /**
   * Converter for String fields. Converts compatible types to String values. Only allows conversion
   * from Number types to prevent malicious toString() calls.
   */
  public static class StringConverter extends FieldConverter<String> {
    static Set<Class<?>> compatibleTypes =
        ImmutableSet.of(
            Integer.class,
            Long.class,
            Short.class,
            Byte.class,
            Boolean.class,
            Float.class,
            Double.class);

    protected StringConverter(FieldAccessor fieldAccessor) {
      super(fieldAccessor);
    }

    /**
     * Converts an object to a String value.
     *
     * @param from the object to convert
     * @return the converted String value, or null if from is null
     */
    public static String convertFrom(Object from) {
      if (from == null) {
        return null;
      } else if (from instanceof Number || from instanceof Boolean) {
        return from.toString();
      } else {
        // disallow on other types, to avoid malicious toString get called.
        throw new UnsupportedOperationException("Incompatible type: " + from.getClass());
      }
    }

    @Override
    public String convert(Object from) {
      return convertFrom(from);
    }
  }
}
