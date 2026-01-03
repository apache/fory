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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.resolver.ClassResolver;
import org.testng.annotations.Test;

public class DescriptorGrouperTest extends ForyTestBase {

  private Descriptor createDescriptor(
      TypeRef<?> typeRef, String name, int modifier, String declaringClass, boolean trackingRef) {
    return new Descriptor(
        typeRef,
        typeRef.getType().getTypeName(),
        name,
        modifier,
        declaringClass,
        trackingRef,
        !typeRef.isPrimitive());
  }

  private List<Descriptor> createDescriptors() {
    List<Descriptor> descriptors = new ArrayList<>();
    int index = 0;
    for (Class<?> aClass : Primitives.allPrimitiveTypes()) {
      descriptors.add(createDescriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass", false));
    }
    for (Class<?> t : Primitives.allWrapperTypes()) {
      descriptors.add(createDescriptor(TypeRef.of(t), "f" + index++, -1, "TestClass", false));
    }
    descriptors.add(
        createDescriptor(TypeRef.of(String.class), "f" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Object.class), "f" + index++, -1, "TestClass", false));
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
      descriptors.add(createDescriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass", false));
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
      descriptors.add(createDescriptor(TypeRef.of(aClass), "f" + index++, -1, "TestClass", false));
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
    descriptors.add(
        createDescriptor(TypeRef.of(Object.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Date.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Instant.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(TypeRef.of(Instant.class), "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(new TypeRef<List<String>>() {}, "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(new TypeRef<List<Integer>>() {}, "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(
            new TypeRef<Map<String, Integer>>() {}, "c" + index++, -1, "TestClass", false));
    descriptors.add(
        createDescriptor(
            new TypeRef<Map<String, String>>() {}, "c" + index++, -1, "TestClass", false));
    DescriptorGrouper grouper =
        DescriptorGrouper.createDescriptorGrouper(
                d -> ReflectionUtils.isMonomorphic(d.getRawType()),
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
      // Sorted by type name: List<Integer> < List<String> (alphabetically)
      List<TypeRef<?>> expected =
          Arrays.asList(new TypeRef<List<Integer>>() {}, new TypeRef<List<String>>() {});
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
          grouper.getBuildInDescriptors().stream()
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
                d -> ReflectionUtils.isMonomorphic(d.getRawType()),
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

  /**
   * Test that ClassResolver's comparator normalizes Collection/Map subtypes for consistent
   * ordering. This ensures List/ArrayList/HashSet are all treated as Collection, and
   * HashMap/TreeMap are all treated as Map.
   */
  @Test
  public void testNormalizedTypeNameComparator() {
    Fory fory = builder().build();
    ClassResolver classResolver = fory.getClassResolver();
    Comparator<Descriptor> comparator = classResolver.createTypeAndNameComparator();

    // Create descriptors with different Collection/Map subtypes
    List<Descriptor> descriptors = new ArrayList<>();
    // List type
    descriptors.add(
        createDescriptor(new TypeRef<List<String>>() {}, "listField", -1, "TestClass", false));
    // Set type
    descriptors.add(
        createDescriptor(new TypeRef<Set<String>>() {}, "setField", -1, "TestClass", false));
    // Collection type
    descriptors.add(
        createDescriptor(
            new TypeRef<Collection<String>>() {}, "collField", -1, "TestClass", false));
    // ArrayList type
    descriptors.add(
        createDescriptor(
            new TypeRef<ArrayList<String>>() {}, "arrayListField", -1, "TestClass", false));
    // HashMap type
    descriptors.add(
        createDescriptor(
            new TypeRef<HashMap<String, Integer>>() {}, "hashMapField", -1, "TestClass", false));
    // Map type
    descriptors.add(
        createDescriptor(
            new TypeRef<Map<String, Integer>>() {}, "mapField", -1, "TestClass", false));

    // Sort with the normalized comparator
    descriptors.sort(comparator);

    // Get field names after sorting
    List<String> fieldNames =
        descriptors.stream().map(Descriptor::getName).collect(Collectors.toList());

    // All Collection types should be grouped together (sorted by field name within the group)
    // All Map types should be grouped together (sorted by field name within the group)
    // Collection types come before Map types alphabetically ("java.util.Collection" <
    // "java.util.Map")
    List<String> expected =
        Arrays.asList(
            "arrayListField",
            "collField",
            "listField",
            "setField", // Collection types
            "hashMapField",
            "mapField" // Map types
            );
    assertEquals(fieldNames, expected);
  }

  /**
   * Test that the DescriptorGrouper's static COMPARATOR_BY_TYPE_AND_NAME does NOT normalize
   * Collection/Map types (it uses the raw type name).
   */
  @Test
  public void testStaticComparatorDoesNotNormalize() {
    // Create descriptors with different Collection/Map subtypes
    List<Descriptor> descriptors = new ArrayList<>();
    descriptors.add(
        createDescriptor(new TypeRef<List<String>>() {}, "listField", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(
            new TypeRef<Collection<String>>() {}, "collField", -1, "TestClass", false));
    descriptors.add(
        createDescriptor(
            new TypeRef<ArrayList<String>>() {}, "arrayListField", -1, "TestClass", false));

    // Sort with the static comparator
    descriptors.sort(DescriptorGrouper.COMPARATOR_BY_TYPE_AND_NAME);

    // Get type names after sorting
    List<String> typeNames =
        descriptors.stream().map(Descriptor::getTypeName).collect(Collectors.toList());

    // The static comparator should sort by actual type name, not normalized
    // ArrayList < Collection < List (alphabetically)
    assertEquals(
        typeNames,
        Arrays.asList(
            "java.util.ArrayList<java.lang.String>",
            "java.util.Collection<java.lang.String>",
            "java.util.List<java.lang.String>"));
  }
}
