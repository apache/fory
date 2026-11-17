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

import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.annotation.JsonLenient;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Read-only coercion in front of one property occurrence's Fory-owned codec, selected when the
 * property's metadata is built from its effective {@link JsonLenient} features.
 *
 * <p>Strict properties never reference this codec. A lenient property dispatches its off-spec
 * shapes here and delegates every native token to the wrapped occurrence's current reader slot, so
 * generated object and collection capabilities installed in that slot remain in use. Writing always
 * delegates unchanged.
 */
@Internal
public abstract class LenientReadCodec implements JsonValueCodec<Object> {
  public static final int SINGLE_VALUE_AS_ARRAY = bit(JsonLenient.Feature.SINGLE_VALUE_AS_ARRAY);
  public static final int SCALAR_AS_STRING = bit(JsonLenient.Feature.SCALAR_AS_STRING);
  public static final int FLOAT_AS_INT = bit(JsonLenient.Feature.FLOAT_AS_INT);
  public static final int TRIM_QUOTED_SCALAR = bit(JsonLenient.Feature.TRIM_QUOTED_SCALAR);
  public static final int EMPTY_STRING_AS_NULL = bit(JsonLenient.Feature.EMPTY_STRING_AS_NULL);

  /**
   * Marks a property mask that comes from the property's own declaration rather than its class or
   * builder default. Such a declaration must have at least one feature that fits the property.
   */
  public static final int DECLARED = 1 << 31;

  /** Selects the feature bits of a property mask. */
  public static final int FEATURES = ~DECLARED;

  private static final int INT_TARGET = 1;
  private static final int LONG_TARGET = 2;
  private static final int SHORT_TARGET = 3;
  private static final int BYTE_TARGET = 4;
  private static final int BIG_INTEGER_TARGET = 5;

  final JsonTypeInfo base;

  LenientReadCodec(JsonTypeInfo base) {
    this.base = base;
  }

  private static int bit(JsonLenient.Feature feature) {
    return 1 << feature.ordinal();
  }

  /** Returns the feature mask of the given features. */
  public static int mask(Iterable<JsonLenient.Feature> features) {
    int mask = 0;
    for (JsonLenient.Feature feature : features) {
      mask |= bit(feature);
    }
    return mask;
  }

  /** Returns the feature mask of one declaration. */
  public static int mask(JsonLenient declaration) {
    int mask = 0;
    for (JsonLenient.Feature feature : declaration.value()) {
      mask |= bit(feature);
    }
    return mask;
  }

  /**
   * Returns the lenient codec for a scalar occurrence, or {@code null} when none of the features
   * applies to its exact Fory-owned codec.
   */
  public static LenientReadCodec scalar(JsonTypeInfo base, int features) {
    Object codec = base.valueCodec();
    if (codec == ScalarCodecs.StringCodec.INSTANCE) {
      return (features & SCALAR_AS_STRING) == 0 ? null : new TextCodec(base);
    }
    int scalarFeatures = features & (TRIM_QUOTED_SCALAR | EMPTY_STRING_AS_NULL);
    Object empty = null;
    int integral = 0;
    if (codec == ScalarCodecs.BooleanCodec.PRIMITIVE) {
      empty = Boolean.FALSE;
    } else if (codec == ScalarCodecs.IntCodec.PRIMITIVE) {
      empty = 0;
      integral = INT_TARGET;
    } else if (codec == ScalarCodecs.LongCodec.PRIMITIVE
        || codec == ScalarCodecs.LongAsStringCodec.PRIMITIVE) {
      empty = 0L;
      integral = LONG_TARGET;
    } else if (codec == ScalarCodecs.ShortCodec.PRIMITIVE) {
      empty = (short) 0;
      integral = SHORT_TARGET;
    } else if (codec == ScalarCodecs.ByteCodec.PRIMITIVE) {
      empty = (byte) 0;
      integral = BYTE_TARGET;
    } else if (codec == ScalarCodecs.FloatCodec.PRIMITIVE) {
      empty = 0f;
    } else if (codec == ScalarCodecs.DoubleCodec.PRIMITIVE) {
      empty = 0d;
    } else if (codec == ScalarCodecs.IntCodec.BOXED) {
      integral = INT_TARGET;
    } else if (codec == ScalarCodecs.LongCodec.BOXED
        || codec == ScalarCodecs.LongAsStringCodec.BOXED) {
      integral = LONG_TARGET;
    } else if (codec == ScalarCodecs.ShortCodec.BOXED) {
      integral = SHORT_TARGET;
    } else if (codec == ScalarCodecs.ByteCodec.BOXED) {
      integral = BYTE_TARGET;
    } else if (codec == ScalarCodecs.BigIntegerCodec.INSTANCE) {
      integral = BIG_INTEGER_TARGET;
    } else if (codec != ScalarCodecs.BooleanCodec.BOXED
        && codec != ScalarCodecs.FloatCodec.BOXED
        && codec != ScalarCodecs.DoubleCodec.BOXED
        && codec != ScalarCodecs.BigDecimalCodec.INSTANCE) {
      return null;
    }
    if (integral != 0) {
      scalarFeatures |= features & FLOAT_AS_INT;
    }
    return scalarFeatures == 0 ? null : new ScalarCodec(base, scalarFeatures, integral, empty);
  }

  /**
   * Returns the lenient codec for an array, collection, map, or object occurrence, or {@code null}
   * when none of the features applies to its exact Fory-owned codec. {@code element} is the
   * occurrence's element type information used for single-value reads.
   */
  public static LenientReadCodec container(JsonTypeInfo base, JsonTypeInfo element, int features) {
    Object codec = base.valueCodec();
    boolean array = codec instanceof ArrayCodec;
    boolean collection = codec instanceof CollectionCodec;
    int containerFeatures = features & EMPTY_STRING_AS_NULL;
    if (array || collection) {
      containerFeatures |= features & SINGLE_VALUE_AS_ARRAY;
    } else if (!(codec instanceof MapCodec) && !(codec instanceof ObjectCodec)) {
      return null;
    }
    if (containerFeatures == 0) {
      return null;
    }
    if ((containerFeatures & SINGLE_VALUE_AS_ARRAY) == 0) {
      element = null;
    }
    return array
        ? new ArrayElementCodec(base, element, containerFeatures, base.rawType().getComponentType())
        : new ContainerCodec(base, element, containerFeatures);
  }

  // An empty string decodes to null, which a non-null occurrence rejects like a JSON null token.
  final Object emptyReference() {
    if (base.rejectsNull()) {
      base.rejectNullValue();
    }
    return null;
  }

  @Override
  public final void writeString(StringJsonWriter writer, Object value) {
    base.stringWriter().writeString(writer, value);
  }

  @Override
  public final void writeUtf8(Utf8JsonWriter writer, Object value) {
    base.utf8Writer().writeUtf8(writer, value);
  }

  @Override
  public final boolean isEmpty(JsonWriter writer, Object value) {
    return base.valueCodec().isEmpty(writer, value);
  }

  /** Keeps a JSON number or boolean read into a String as its token text. */
  private static final class TextCodec extends LenientReadCodec {
    private TextCodec(JsonTypeInfo base) {
      super(base);
    }

    // Returns null when the next token is not a native number or boolean.
    private static String readScalarText(JsonReader reader) {
      char token = reader.peekToken();
      if (token == '-' || token >= '0' && token <= '9') {
        return reader.readNumberAsString();
      }
      if (token == 't' || token == 'f') {
        return reader.readBoolean() ? "true" : "false";
      }
      return null;
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      String text = readScalarText(reader);
      return text != null ? text : base.latin1Reader().readLatin1(reader);
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      String text = readScalarText(reader);
      return text != null ? text : base.utf16Reader().readUtf16(reader);
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      String text = readScalarText(reader);
      return text != null ? text : base.utf8Reader().readUtf8(reader);
    }
  }

  /** Coerces padded, empty, and fractional input for boolean and numeric targets. */
  private static final class ScalarCodec extends LenientReadCodec {
    // Distinguishes "decoded a null" from "the native reader owns this token".
    private static final Object NATIVE = new Object();
    private static final Object EMPTY_REFERENCE = new Object();
    private static final Object PADDED = new Object();

    private final boolean emptyAsNull;
    private final boolean trim;
    private final boolean truncate;
    private final int integral;
    private final Object empty;

    private ScalarCodec(JsonTypeInfo base, int features, int integral, Object empty) {
      super(base);
      emptyAsNull = (features & EMPTY_STRING_AS_NULL) != 0;
      trim = (features & TRIM_QUOTED_SCALAR) != 0;
      truncate = (features & FLOAT_AS_INT) != 0;
      this.integral = integral;
      this.empty = empty == null ? EMPTY_REFERENCE : empty;
    }

    // Consumes an empty string or the opening of a padded string. Returns EMPTY_REFERENCE or a
    // primitive zero for an empty string, PADDED after a padded opening, and NATIVE otherwise.
    private Object begin(JsonReader reader) {
      if (reader.peekToken() != '"') {
        return NATIVE;
      }
      if (emptyAsNull && reader.tryReadEmptyString(trim)) {
        return empty;
      }
      if (trim && reader.beginPaddedQuotedScalar()) {
        if (reader.peekToken() == 'n') {
          throw new ForyJsonException("Expected a quoted boolean or number");
        }
        return PADDED;
      }
      return NATIVE;
    }

    private Object readTruncated(JsonReader reader) {
      if (reader.peekToken() == 'n') {
        return NATIVE;
      }
      if (integral == BIG_INTEGER_TARGET) {
        return reader.readTruncatedBigInteger();
      }
      switch (integral) {
        case INT_TARGET:
          return (int)
              reader.readTruncatedLong(Integer.MIN_VALUE, Integer.MAX_VALUE, "Integer overflow");
        case SHORT_TARGET:
          return (short)
              reader.readTruncatedLong(Short.MIN_VALUE, Short.MAX_VALUE, "Short overflow");
        case BYTE_TARGET:
          return (byte) reader.readTruncatedLong(Byte.MIN_VALUE, Byte.MAX_VALUE, "Byte overflow");
        default:
          return reader.readTruncatedLong(Long.MIN_VALUE, Long.MAX_VALUE, "Long overflow");
      }
    }

    private Object finish(JsonReader reader, Object opening, Object value) {
      if (opening == PADDED) {
        reader.finishPaddedQuotedScalar();
      }
      return value;
    }

    @Override
    public Object readLatin1(Latin1JsonReader reader) {
      Object opening = begin(reader);
      if (opening != NATIVE && opening != PADDED) {
        return opening == EMPTY_REFERENCE ? emptyReference() : opening;
      }
      Object value = truncate ? readTruncated(reader) : NATIVE;
      if (value == NATIVE) {
        value = base.latin1Reader().readLatin1(reader);
      }
      return finish(reader, opening, value);
    }

    @Override
    public Object readUtf16(Utf16JsonReader reader) {
      Object opening = begin(reader);
      if (opening != NATIVE && opening != PADDED) {
        return opening == EMPTY_REFERENCE ? emptyReference() : opening;
      }
      Object value = truncate ? readTruncated(reader) : NATIVE;
      if (value == NATIVE) {
        value = base.utf16Reader().readUtf16(reader);
      }
      return finish(reader, opening, value);
    }

    @Override
    public Object readUtf8(Utf8JsonReader reader) {
      Object opening = begin(reader);
      if (opening != NATIVE && opening != PADDED) {
        return opening == EMPTY_REFERENCE ? emptyReference() : opening;
      }
      Object value = truncate ? readTruncated(reader) : NATIVE;
      if (value == NATIVE) {
        value = base.utf8Reader().readUtf8(reader);
      }
      return finish(reader, opening, value);
    }
  }

  /** Reads an empty string as null and, for collections, a single value as one element. */
  private static class ContainerCodec extends LenientReadCodec {
    private static final int NATIVE = 0;
    private static final int EMPTY = 1;
    private static final int SINGLE = 2;

    final JsonTypeInfo element;
    private final boolean emptyAsNull;

    private ContainerCodec(JsonTypeInfo base, JsonTypeInfo element, int features) {
      super(base);
      this.element = element;
      emptyAsNull = (features & EMPTY_STRING_AS_NULL) != 0;
    }

    final int shape(JsonReader reader) {
      char token = reader.peekToken();
      if (token == '[' || token == 'n') {
        return NATIVE;
      }
      if (emptyAsNull && token == '"' && reader.tryReadEmptyString(false)) {
        return EMPTY;
      }
      return element == null ? NATIVE : SINGLE;
    }

    Object single(JsonReader reader, Object value) {
      return ((CollectionCodec<?>) base.valueCodec()).singleElementCollection(reader, value);
    }

    @Override
    public final Object readLatin1(Latin1JsonReader reader) {
      switch (shape(reader)) {
        case EMPTY:
          return emptyReference();
        case SINGLE:
          return single(reader, element.latin1Reader().readLatin1(reader));
        default:
          return base.latin1Reader().readLatin1(reader);
      }
    }

    @Override
    public final Object readUtf16(Utf16JsonReader reader) {
      switch (shape(reader)) {
        case EMPTY:
          return emptyReference();
        case SINGLE:
          return single(reader, element.utf16Reader().readUtf16(reader));
        default:
          return base.utf16Reader().readUtf16(reader);
      }
    }

    @Override
    public final Object readUtf8(Utf8JsonReader reader) {
      switch (shape(reader)) {
        case EMPTY:
          return emptyReference();
        case SINGLE:
          return single(reader, element.utf8Reader().readUtf8(reader));
        default:
          return base.utf8Reader().readUtf8(reader);
      }
    }
  }

  /** Builds a one-element array for a single value. */
  private static final class ArrayElementCodec extends ContainerCodec {
    private final Class<?> componentType;

    private ArrayElementCodec(
        JsonTypeInfo base, JsonTypeInfo element, int features, Class<?> componentType) {
      super(base, element, features);
      this.componentType = componentType;
    }

    @Override
    Object single(JsonReader reader, Object value) {
      if (value == null && element.rejectsNull()) {
        element.rejectNullValue();
      }
      return ArrayCodec.singleElementArray(reader, componentType, value);
    }
  }
}
