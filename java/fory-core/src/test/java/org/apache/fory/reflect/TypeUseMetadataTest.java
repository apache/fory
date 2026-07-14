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

package org.apache.fory.reflect;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import org.testng.annotations.Test;

public class TypeUseMetadataTest {
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE_USE)
  private @interface Marker {
    String value();
  }

  private static class TypeUses {
    @Marker("element")
    String[] elementAnnotated;

    String @Marker("array") [] arrayAnnotated;
    List<@Marker("wildcard") ? extends @Marker("upper") Number> upperBound;
    List<? super @Marker("lower") Integer> lowerBound;

    TypeUses(List<@Marker("parameter") String> values) {}
  }

  @Test
  public void testArrayComponentTypeUse() throws Exception {
    Object elementArray = fieldTypeUse("elementAnnotated");
    assertNull(TypeUseMetadata.typeUseAnnotation(elementArray, Marker.class));
    assertMarker(TypeUseMetadata.arrayComponentTypeUse(elementArray), "element");

    Object annotatedArray = fieldTypeUse("arrayAnnotated");
    assertMarker(annotatedArray, "array");
    Object component = TypeUseMetadata.arrayComponentTypeUse(annotatedArray);
    assertNotNull(component);
    assertNull(TypeUseMetadata.typeUseAnnotation(component, Marker.class));

    assertNull(TypeUseMetadata.arrayComponentTypeUse(component));
  }

  @Test
  public void testConstructorParameterTypeUses() throws Exception {
    Constructor<?> constructor = TypeUses.class.getDeclaredConstructor(List.class);
    Object[] parameters = TypeUseMetadata.constructorParameterTypeUses(constructor);
    assertEquals(parameters.length, 1);
    Object[] arguments = TypeUseMetadata.typeUseArguments(parameters[0]);
    assertEquals(arguments.length, 1);
    assertMarker(arguments[0], "parameter");
  }

  @Test
  public void testWildcardBounds() throws Exception {
    Object upperWildcard = onlyTypeArgument("upperBound");
    assertMarker(upperWildcard, "wildcard");
    Object[] upperBounds = TypeUseMetadata.wildcardUpperBounds(upperWildcard);
    assertEquals(upperBounds.length, 1);
    assertMarker(upperBounds[0], "upper");
    assertEquals(TypeUseMetadata.wildcardLowerBounds(upperWildcard).length, 0);

    Object lowerWildcard = onlyTypeArgument("lowerBound");
    Object[] lowerBounds = TypeUseMetadata.wildcardLowerBounds(lowerWildcard);
    assertEquals(lowerBounds.length, 1);
    assertMarker(lowerBounds[0], "lower");
    assertEquals(TypeUseMetadata.wildcardUpperBounds(lowerWildcard).length, 1);

    assertNull(TypeUseMetadata.wildcardUpperBounds(lowerBounds[0]));
    assertNull(TypeUseMetadata.wildcardLowerBounds(lowerBounds[0]));
  }

  private static Object fieldTypeUse(String name) throws Exception {
    Field field = TypeUses.class.getDeclaredField(name);
    return TypeUseMetadata.fieldTypeUse(field);
  }

  private static Object onlyTypeArgument(String fieldName) throws Exception {
    Object[] arguments = TypeUseMetadata.typeUseArguments(fieldTypeUse(fieldName));
    assertEquals(arguments.length, 1);
    return arguments[0];
  }

  private static void assertMarker(Object typeUse, String value) {
    Marker marker = TypeUseMetadata.typeUseAnnotation(typeUse, Marker.class);
    assertNotNull(marker);
    assertEquals(marker.value(), value);
  }
}
