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

package org.apache.fory.builder;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates unique marker classes for each layer in a class hierarchy. These marker classes serve as
 * unique keys in {@code metaContext.classMap} to distinguish different layers during serialization.
 *
 * <p>For a class hierarchy {@code C extends B extends A}, this generator creates unique marker
 * classes for:
 *
 * <ul>
 *   <li>A's layer with index 0 - marker for A's layer fields
 *   <li>B's layer with index 1 - marker for B's layer fields
 *   <li>C's layer with index 2 - marker for C's layer fields
 * </ul>
 *
 * <p>This implementation uses array classes as unique markers. For layer index N, we create an
 * (N+3)-dimensional array class of the target class. The +3 offset ensures marker classes don't
 * conflict with regular array types (1D, 2D arrays are common). Array classes are created by the
 * JVM and don't require reflection registration in GraalVM.
 *
 * <p>Common exception hierarchy classes are pre-filled at class initialization to ensure
 * availability in GraalVM native images.
 */
public class LayerMarkerClassGenerator {

  /** Maximum supported layer depth. */
  private static final int MAX_LAYER_DEPTH = 16;

  /**
   * ClassValue-based cache for layer marker classes. For each target class, we store a map from
   * layer index to the generated array class marker.
   */
  private static final ClassValue<Map<Integer, Class<?>>> layerClassCache =
      new ClassValue<Map<Integer, Class<?>>>() {
        @Override
        protected Map<Integer, Class<?>> computeValue(Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  // Pre-fill common exception hierarchy to ensure GraalVM compatibility
  static {
    prefillCommonClasses();
  }

  /** Pre-fill marker classes for common JDK classes that use ObjectStreamSerializer. */
  private static void prefillCommonClasses() {
    // Exception hierarchy (most exceptions have writeObject/readObject)
    Class<?>[] exceptionClasses = {
      Throwable.class,
      Exception.class,
      RuntimeException.class,
      Error.class,
      // Common runtime exceptions
      NullPointerException.class,
      IllegalArgumentException.class,
      IllegalStateException.class,
      IndexOutOfBoundsException.class,
      ArrayIndexOutOfBoundsException.class,
      StringIndexOutOfBoundsException.class,
      ClassCastException.class,
      ArithmeticException.class,
      SecurityException.class,
      UnsupportedOperationException.class,
      NumberFormatException.class,
      // Common checked exceptions
      java.io.IOException.class,
      java.io.FileNotFoundException.class,
      java.io.EOFException.class,
      java.io.NotSerializableException.class,
      java.io.InvalidClassException.class,
      ClassNotFoundException.class,
      NoSuchMethodException.class,
      NoSuchFieldException.class,
      InterruptedException.class,
      ReflectiveOperationException.class,
      // Common errors
      AssertionError.class,
      OutOfMemoryError.class,
      StackOverflowError.class,
      NoClassDefFoundError.class,
      LinkageError.class,
      ExceptionInInitializerError.class,
      VirtualMachineError.class,
    };

    // Pre-generate marker classes for common depths (0-3 covers most hierarchies)
    for (Class<?> cls : exceptionClasses) {
      Map<Integer, Class<?>> layerMap = layerClassCache.get(cls);
      for (int i = 0; i < 4; i++) {
        layerMap.computeIfAbsent(i, idx -> createArrayClass(cls, idx));
      }
    }
  }

  /** Get or create a unique marker class for the given target class and layer index. */
  public static Class<?> getOrCreate(Class<?> targetClass, int layerIndex) {
    if (layerIndex >= MAX_LAYER_DEPTH) {
      throw new IllegalArgumentException(
          "Layer index " + layerIndex + " exceeds maximum depth " + MAX_LAYER_DEPTH);
    }
    Map<Integer, Class<?>> layerMap = layerClassCache.get(targetClass);
    return layerMap.computeIfAbsent(layerIndex, idx -> createArrayClass(targetClass, idx));
  }

  /**
   * Base dimension offset for marker arrays. We use N+3 dimensions to avoid confusion with regular
   * array types that users might use (1D, 2D arrays are common).
   */
  private static final int DIMENSION_OFFSET = 3;

  /**
   * Create a unique array class marker for the given target class and layer index.
   *
   * <p>Uses (N+3)-dimensional array of the target class as the marker. Array classes are unique per
   * dimension and don't require GraalVM reflection registration. The +3 offset ensures marker
   * classes are unlikely to conflict with regular array types.
   *
   * @param targetClass the target class
   * @param layerIndex the layer index
   * @return a unique array class for this layer marker
   */
  private static Class<?> createArrayClass(Class<?> targetClass, int layerIndex) {
    // Create an (layerIndex+3)-dimensional array class
    // Layer 0 -> 3D array (e.g., Foo[][][])
    // Layer 1 -> 4D array (e.g., Foo[][][][])
    // etc.
    int[] dimensions = new int[layerIndex + DIMENSION_OFFSET];
    return Array.newInstance(targetClass, dimensions).getClass();
  }
}
