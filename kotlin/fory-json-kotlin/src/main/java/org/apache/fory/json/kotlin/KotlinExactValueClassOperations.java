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

package org.apache.fory.json.kotlin;

import java.lang.invoke.MethodHandle;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.reader.JsonReader;

/** Exact signature-polymorphic invocation for prebound value-class operations. */
final class KotlinExactValueClassOperations {
  private KotlinExactValueClassOperations() {}

  static KotlinValueClassOperations createCodec(
      Class<?> owner, Class<?> carrier, MethodHandle construct, MethodHandle unbox) {
    if (carrier == boolean.class) {
      return new BooleanOperations(owner, construct, unbox);
    } else if (carrier == byte.class) {
      return new ByteOperations(owner, construct, unbox);
    } else if (carrier == short.class) {
      return new ShortOperations(owner, construct, unbox);
    } else if (carrier == int.class) {
      return new IntOperations(owner, construct, unbox);
    } else if (carrier == long.class) {
      return new LongOperations(owner, construct, unbox);
    } else if (carrier == float.class) {
      return new FloatOperations(owner, construct, unbox);
    } else if (carrier == double.class) {
      return new DoubleOperations(owner, construct, unbox);
    } else if (carrier == char.class) {
      return new CharOperations(owner, construct, unbox);
    }
    return new ReferenceOperations(owner, construct, unbox);
  }

  static KotlinValueClassOperations createMapKey(
      Class<?> owner,
      Class<?> carrier,
      MethodHandle construct,
      MethodHandle constructUncharged,
      MethodHandle unbox) {
    if (carrier == byte.class) {
      return new ByteMapKeyOperations(owner, construct, constructUncharged, unbox);
    } else if (carrier == short.class) {
      return new ShortMapKeyOperations(owner, construct, constructUncharged, unbox);
    } else if (carrier == int.class) {
      return new IntMapKeyOperations(owner, construct, constructUncharged, unbox);
    } else if (carrier == long.class) {
      return new LongMapKeyOperations(owner, construct, constructUncharged, unbox);
    }
    return new ReferenceMapKeyOperations(owner, construct, constructUncharged, unbox);
  }

  private abstract static class Operations implements KotlinValueClassOperations {
    final Class<?> owner;
    final MethodHandle construct;
    final MethodHandle unbox;

    Operations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      this.owner = owner;
      this.construct = construct;
      this.unbox = unbox;
    }

    final ForyJsonException failure(String operation, Throwable cause) {
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      if (cause instanceof ForyJsonException) {
        return (ForyJsonException) cause;
      }
      return new ForyJsonException(
          "Kotlin value-class " + operation + " failed for " + owner.getName(), cause);
    }
  }

  private static final class BooleanOperations extends Operations
      implements KotlinBooleanValueClassOperations<Object> {
    BooleanOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructBoolean(JsonReader reader, boolean value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public boolean unboxBoolean(Object value) {
      try {
        return (boolean) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static class ByteOperations extends Operations
      implements KotlinByteValueClassOperations<Object> {
    ByteOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructByte(JsonReader reader, byte value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public byte unboxByte(Object value) {
      try {
        return (byte) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static class ShortOperations extends Operations
      implements KotlinShortValueClassOperations<Object> {
    ShortOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructShort(JsonReader reader, short value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public short unboxShort(Object value) {
      try {
        return (short) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static class IntOperations extends Operations
      implements KotlinIntValueClassOperations<Object> {
    IntOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructInt(JsonReader reader, int value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public int unboxInt(Object value) {
      try {
        return (int) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static class LongOperations extends Operations
      implements KotlinLongValueClassOperations<Object> {
    LongOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructLong(JsonReader reader, long value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public long unboxLong(Object value) {
      try {
        return (long) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static final class FloatOperations extends Operations
      implements KotlinFloatValueClassOperations<Object> {
    FloatOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructFloat(JsonReader reader, float value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public float unboxFloat(Object value) {
      try {
        return (float) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static final class DoubleOperations extends Operations
      implements KotlinDoubleValueClassOperations<Object> {
    DoubleOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructDouble(JsonReader reader, double value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public double unboxDouble(Object value) {
      try {
        return (double) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static final class CharOperations extends Operations
      implements KotlinCharValueClassOperations<Object> {
    CharOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object constructChar(JsonReader reader, char value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public char unboxChar(Object value) {
      try {
        return (char) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static class ReferenceOperations extends Operations implements BoxedValueClassOperations {
    ReferenceOperations(Class<?> owner, MethodHandle construct, MethodHandle unbox) {
      super(owner, construct, unbox);
    }

    @Override
    public Object construct(JsonReader reader, Object value) {
      try {
        return (Object) construct.invokeExact(reader, value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }

    @Override
    public Object unbox(Object value) {
      try {
        return (Object) unbox.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("unbox", cause);
      }
    }
  }

  private static final class ByteMapKeyOperations extends ByteOperations
      implements KotlinByteMapKeyOperations<Object> {
    private final MethodHandle uncharged;

    ByteMapKeyOperations(
        Class<?> owner, MethodHandle construct, MethodHandle uncharged, MethodHandle unbox) {
      super(owner, construct, unbox);
      this.uncharged = uncharged;
    }

    @Override
    public Object constructByteUncharged(byte value) {
      try {
        return (Object) uncharged.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }
  }

  private static final class ShortMapKeyOperations extends ShortOperations
      implements KotlinShortMapKeyOperations<Object> {
    private final MethodHandle uncharged;

    ShortMapKeyOperations(
        Class<?> owner, MethodHandle construct, MethodHandle uncharged, MethodHandle unbox) {
      super(owner, construct, unbox);
      this.uncharged = uncharged;
    }

    @Override
    public Object constructShortUncharged(short value) {
      try {
        return (Object) uncharged.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }
  }

  private static final class IntMapKeyOperations extends IntOperations
      implements KotlinIntMapKeyOperations<Object> {
    private final MethodHandle uncharged;

    IntMapKeyOperations(
        Class<?> owner, MethodHandle construct, MethodHandle uncharged, MethodHandle unbox) {
      super(owner, construct, unbox);
      this.uncharged = uncharged;
    }

    @Override
    public Object constructIntUncharged(int value) {
      try {
        return (Object) uncharged.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }
  }

  private static final class LongMapKeyOperations extends LongOperations
      implements KotlinLongMapKeyOperations<Object> {
    private final MethodHandle uncharged;

    LongMapKeyOperations(
        Class<?> owner, MethodHandle construct, MethodHandle uncharged, MethodHandle unbox) {
      super(owner, construct, unbox);
      this.uncharged = uncharged;
    }

    @Override
    public Object constructLongUncharged(long value) {
      try {
        return (Object) uncharged.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }
  }

  private static final class ReferenceMapKeyOperations extends ReferenceOperations
      implements UnchargedBoxedOperations {
    private final MethodHandle uncharged;

    ReferenceMapKeyOperations(
        Class<?> owner, MethodHandle construct, MethodHandle uncharged, MethodHandle unbox) {
      super(owner, construct, unbox);
      this.uncharged = uncharged;
    }

    @Override
    public Object constructUncharged(Object value) {
      try {
        return (Object) uncharged.invokeExact(value);
      } catch (Throwable cause) {
        throw failure("construct", cause);
      }
    }
  }
}
