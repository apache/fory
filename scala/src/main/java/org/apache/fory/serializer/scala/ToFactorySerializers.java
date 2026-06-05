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

package org.apache.fory.serializer.scala;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.apache.fory.config.Config;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.reflect.FieldAccessor;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.Shareable;
import org.apache.fory.serializer.Serializer;

public class ToFactorySerializers {
  static final Class<?> IterableToFactoryClass =
      ReflectionUtils.loadClass("scala.collection.IterableFactory$ToFactory");
  static final Class<?> MapToFactoryClass =
      ReflectionUtils.loadClass("scala.collection.MapFactory$ToFactory");

  public static class IterableToFactorySerializer extends Serializer implements Shareable {
    private static final FieldAccessor FACTORY_ACCESSOR;
    private static final Constructor<?> CONSTRUCTOR;

    static {
      try {
        Field field = IterableToFactoryClass.getDeclaredField("factory");
        FACTORY_ACCESSOR = FieldAccessor.createAccessor(field);
        CONSTRUCTOR = IterableToFactoryClass.getDeclaredConstructor(field.getType());
        CONSTRUCTOR.setAccessible(true);
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }

    public IterableToFactorySerializer(Config config) {
      super(config, IterableToFactoryClass);
    }

    @Override
    public void write(WriteContext writeContext, Object value) {
      writeContext.writeRef(FACTORY_ACCESSOR.getObject(value));
    }

    @Override
    public Object read(ReadContext readContext) {
      try {
        return CONSTRUCTOR.newInstance(readContext.readRef());
      } catch (Exception e) {
        throw new ForyException("Failed to create Scala IterableFactory.ToFactory", e);
      }
    }
  }

  public static class MapToFactorySerializer extends Serializer implements Shareable {
    private static final FieldAccessor FACTORY_ACCESSOR;
    private static final Constructor<?> CONSTRUCTOR;

    static {
      try {
        Field field = MapToFactoryClass.getDeclaredField("factory");
        FACTORY_ACCESSOR = FieldAccessor.createAccessor(field);
        CONSTRUCTOR = MapToFactoryClass.getDeclaredConstructor(field.getType());
        CONSTRUCTOR.setAccessible(true);
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }

    public MapToFactorySerializer(Config config) {
      super(config, MapToFactoryClass);
    }

    @Override
    public void write(WriteContext writeContext, Object value) {
      writeContext.writeRef(FACTORY_ACCESSOR.getObject(value));
    }

    @Override
    public Object read(ReadContext readContext) {
      try {
        return CONSTRUCTOR.newInstance(readContext.readRef());
      } catch (Exception e) {
        throw new ForyException("Failed to create Scala MapFactory.ToFactory", e);
      }
    }
  }
}
