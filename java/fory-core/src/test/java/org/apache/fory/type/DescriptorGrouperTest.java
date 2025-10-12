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

package org.apache.fory.type;

import static org.testng.Assert.*;

import com.google.common.primitives.Primitives;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class DescriptorGrouperTest {

  private List<Descriptor> createDescriptors() {
    List<Descriptor> descriptors = new ArrayList<>();
    int index = 0;
    for (Class<?> aClass : Primitives.allPrimitiveTypes()) {
      descriptors.add(new Descriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass"));
    }
    for (Class<?> t : Primitives.allWrapperTypes()) {
      descriptors.add(new Descriptor(TypeRef.of(t), "f" + index++, -1, "TestClass"));
    }
    descriptors.add(new Descriptor(TypeRef.of(String.class), "f" + index++, -1, "TestClass"));
    descriptors.add(new Descriptor(TypeRef.of(Object.class), "f" + index++, -1, "TestClass"));
    Collections.shuffle(descriptors, new Random(17));
    return descriptors;
  }

  @Test
  public void testComparatorByTypeAndName() {
    List<Descriptor> descriptors = createDescriptors();
    descriptors.sort(DescriptorGrouper.COMPARATOR_BY_TYPE_AND_NAME);
    List<? extends Class<?>> classes =
        descriptors.stream().map(Descriptor::getRawType).collect(Collectors.toList());
    List<Class<?>> expected =
        Arrays.asList(
            boolean.class,
            byte.class,
            char.class,
            double.class,
            float.class,
            int.class,
            Boolean.class,
            Byte.class,
            Character.class,
            Double.class,
            Float.class,
            Integer.class,
            Long.class,
            Object.class,
            Short.class,
            String.class,
            Void.class,
            long.class,
            short.class,
            void.class);
    assertEquals(classes, expected);
  }

  @Test
  public void testPrimitiveComparator() {
    List<Descriptor> descriptors = new ArrayList<>();
    int index = 0;
    for (Class<?> aClass : Primitives.allPrimitiveTypes()) {
      descriptors.add(new Descriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass"));
    }
    Collections.shuffle(descriptors, new Random(7));
    descriptors.sort(DescriptorGrouper.getPrimitiveComparator(false, false));
    List<? extends Class<?>> classes =
        descriptors.stream().map(Descriptor::getRawType).collect(Collectors.toList());
    List<Class<?>> expected =
        Arrays.asList(
            double.class,
            long.class,
            float.class,
            int.class,
            short.class,
            char.class,
            byte.class,
            boolean.class,
            void.class);
    assertEquals(classes, expected);
  }

  @Test
  public void testPrimitiveCompressedComparator() {
    List<Descriptor> descriptors = new ArrayList<>();
    int index = 0;
    for (Class<?> aClass : Primitives.allPrimitiveTypes()) {
      descriptors.add(new Descriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass"));
    }
    Collections.shuffle(descriptors, new Random(7));
    descriptors.sort(DescriptorGrouper.getPrimitiveComparator(true, true));
    List<? extends Class<?>> classes =
        descriptors.stream().map(Descriptor::getRawType).collect(Collectors.toList());
    List<Class<?>> expected =
        Arrays.asList(
            double.class,
            float.class,
            short.class,
            char.class,
            byte.class,
            boolean.class,
            void.class,
            long.class,
            int.class);
    assertEquals(classes, expected);
  }

  @Test
  public void testGrouper() {
    List<Descriptor> descriptors = createDescriptors();
    int index = 0;
    descriptors.add(new Descriptor(TypeRef.of(Object.class), "c" + index++, -1, "TestClass"));
    descriptors.add(new Descriptor(TypeRef.of(Date.class), "c" + index++, -1, "TestClass"));
    descriptors.add(new Descriptor(TypeRef.of(Instant.class), "c" + index++, -1, "TestClass"));
    descriptors.add(new Descriptor(TypeRef.of(Instant.class), "c" + index++, -1, "TestClass"));
    descriptors.add(new Descriptor(new TypeRef<List<String>>() {}, "c" + index++, -1, "TestClass"));
    descriptors.add(
        new Descriptor(new TypeRef<List<Integer>>() {}, "c" + index++, -1, "TestClass"));
    descriptors.add(
        new Descriptor(new TypeRef<Map<String, Integer>>() {}, "c" + index++, -1, "TestClass"));
    descriptors.add(
        new Descriptor(new TypeRef<Map<String, String>>() {}, "c" + index++, -1, "TestClass"));
    DescriptorGrouper grouper =
        DescriptorGrouper.createDescriptorGrouper(
                ReflectionUtils::isMonomorphic,
                descriptors,
                false,
                null,
                false,
                false,
                DescriptorGrouper.COMPARATOR_BY_TYPE_AND_NAME)
            .sort();
    {
      List<? extends Class<?>> classes =
          grouper.getPrimitiveDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      List<Class<?>> expected =
          Arrays.asList(
              double.class,
              long.class,
              float.class,
              int.class,
              short.class,
              char.class,
              byte.class,
              boolean.class,
              void.class);
      assertEquals(classes, expected);
    }
    {
      List<? extends Class<?>> classes =
          grouper.getBoxedDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      List<Class<?>> expected =
          Arrays.asList(
              Double.class,
              Long.class,
              Float.class,
              Integer.class,
              Short.class,
              Character.class,
              Byte.class,
              Boolean.class,
              Void.class);
      assertEquals(classes, expected);
    }
    {
      List<TypeRef<?>> types =
          grouper.getCollectionDescriptors().stream()
              .map(Descriptor::getTypeRef)
              .collect(Collectors.toList());
      List<TypeRef<?>> expected =
          Arrays.asList(new TypeRef<List<String>>() {}, new TypeRef<List<Integer>>() {});
      assertEquals(types, expected);
    }
    {
      List<TypeRef<?>> types =
          grouper.getMapDescriptors().stream()
              .map(Descriptor::getTypeRef)
              .collect(Collectors.toList());
      List<TypeRef<?>> expected =
          Arrays.asList(
              new TypeRef<Map<String, Integer>>() {}, new TypeRef<Map<String, String>>() {});
      assertEquals(types, expected);
    }
    {
      List<? extends Class<?>> classes =
          grouper.getFinalDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      assertEquals(classes, Arrays.asList(String.class, Instant.class, Instant.class));
    }
    {
      List<? extends Class<?>> classes =
          grouper.getOtherDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      assertEquals(classes, Arrays.asList(Object.class, Object.class, Date.class));
    }
  }

  @Test
  public void testCompressedPrimitiveGrouper() {
    DescriptorGrouper grouper =
        DescriptorGrouper.createDescriptorGrouper(
                ReflectionUtils::isMonomorphic,
                createDescriptors(),
                false,
                null,
                true,
                true,
                DescriptorGrouper.COMPARATOR_BY_TYPE_AND_NAME)
            .sort();
    {
      List<? extends Class<?>> classes =
          grouper.getPrimitiveDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      List<Class<?>> expected =
          Arrays.asList(
              double.class,
              float.class,
              short.class,
              char.class,
              byte.class,
              boolean.class,
              void.class,
              long.class,
              int.class);
      assertEquals(classes, expected);
    }
    {
      List<? extends Class<?>> classes =
          grouper.getBoxedDescriptors().stream()
              .map(Descriptor::getRawType)
              .collect(Collectors.toList());
      List<Class<?>> expected =
          Arrays.asList(
              Double.class,
              Float.class,
              Short.class,
              Character.class,
              Byte.class,
              Boolean.class,
              Void.class,
              Long.class,
              Integer.class);
      assertEquals(classes, expected);
    }
  }
}
