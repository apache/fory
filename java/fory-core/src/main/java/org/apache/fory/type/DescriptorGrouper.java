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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.record.RecordUtils;

/**
 * A utility class to group class fields into groups.
 *
 * <ul>
 *   <li>primitive fields
 *   <li>boxed primitive fields
 *   <li>final fields
 *   <li>collection fields
 *   <li>map fields
 *   <li>other fields
 * </ul>
 *
 * <p><b>IMPORTANT:</b> Resorting fields is mandatory in cross-language (xlang) serialization. The
 * Fory protocol specification requires that both serialization peers (e.g., Java, Rust, Go, Python)
 * use exactly the same sorting algorithm to determine field order. The in-flight byte order of
 * fields is not guaranteed to match any particular peer's original declaration order. Instead, each
 * peer must independently sort fields using the same algorithm to ensure consistent
 * serialization/deserialization.
 *
 * <p>The sorting groups fields by type category (primitives, boxed, collections, maps, etc.) and
 * then sorts by field name within each category. Both reader and writer must apply this sorting to
 * produce identical field ordering.
 */
public class DescriptorGrouper {

  private final Collection<Descriptor> descriptors;
  private final Predicate<Descriptor> usesPrimitiveFieldOrdering;
  private final Predicate<Descriptor> isBuildIn;
  private final Predicate<Descriptor> isCollection;
  private final Function<Descriptor, Descriptor> descriptorUpdater;
  private final boolean descriptorsGroupedOrdered;
  private boolean sorted = false;
  private Predicate<Descriptor> sortTogetherPredicate;

  private final Collection<Descriptor> primitiveDescriptors;
  private final Collection<Descriptor> boxedDescriptors;
  // The element type should be final.
  private final Collection<Descriptor> collectionDescriptors;
  // The key/value type should be final.
  private final Collection<Descriptor> mapDescriptors;
  private final Collection<Descriptor> buildInDescriptors;
  private Collection<Descriptor> otherDescriptors;

  /**
   * Create a descriptor grouper.
   *
   * @param isBuildIn whether the class is build-in types.
   * @param descriptors descriptors may have field with same name.
   * @param descriptorsGroupedOrdered whether the descriptors are grouped and ordered.
   * @param descriptorUpdater create a new descriptor from original one.
   * @param primitiveComparator comparator for primitive/boxed fields.
   * @param comparator comparator for non-primitive fields.
   */
  private DescriptorGrouper(
      Predicate<Descriptor> usesPrimitiveFieldOrdering,
      Predicate<Descriptor> isBuildIn,
      Predicate<Descriptor> isCollection,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdater,
      Comparator<Descriptor> primitiveComparator,
      Comparator<Descriptor> comparator) {
    this.usesPrimitiveFieldOrdering = usesPrimitiveFieldOrdering;
    this.descriptors = descriptors;
    this.isBuildIn = isBuildIn;
    this.isCollection = isCollection;
    this.descriptorUpdater = descriptorUpdater;
    this.descriptorsGroupedOrdered = descriptorsGroupedOrdered;
    this.primitiveDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(primitiveComparator);
    this.boxedDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(primitiveComparator);
    this.collectionDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.mapDescriptors = descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.buildInDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    this.otherDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
  }

  public DescriptorGrouper setOtherDescriptorComparator(Comparator<Descriptor> comparator) {
    Preconditions.checkArgument(!sorted);
    this.otherDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    return this;
  }

  public DescriptorGrouper setSortTogetherComparator(
      Comparator<Descriptor> comparator, Predicate<Descriptor> predicate) {
    Preconditions.checkArgument(!sorted);
    sortTogetherPredicate = predicate;
    this.otherDescriptors =
        descriptorsGroupedOrdered ? new ArrayList<>() : new TreeSet<>(comparator);
    return this;
  }

  public DescriptorGrouper sort() {
    if (sorted) {
      return this;
    }
    if (sortTogetherPredicate != null) {
      boolean sortTogether = true;
      for (Descriptor descriptor : descriptors) {
        if (!sortTogetherPredicate.test(descriptor)) {
          sortTogether = false;
          break;
        }
      }
      if (sortTogether) {
        for (Descriptor descriptor : descriptors) {
          otherDescriptors.add(descriptorUpdater.apply(descriptor));
        }
        sorted = true;
        return this;
      }
    }
    for (Descriptor descriptor : descriptors) {
      if (usesPrimitiveFieldOrdering.test(descriptor)) {
        if (!descriptor.isNullable()) {
          primitiveDescriptors.add(descriptorUpdater.apply(descriptor));
        } else {
          boxedDescriptors.add(descriptorUpdater.apply(descriptor));
        }
      } else if (isCollection.test(descriptor)) {
        collectionDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (TypeUtils.isMap(descriptor.getRawType())) {
        mapDescriptors.add(descriptorUpdater.apply(descriptor));
      } else if (isBuildIn.test(descriptor)) {
        buildInDescriptors.add(descriptorUpdater.apply(descriptor));
      } else {
        otherDescriptors.add(descriptorUpdater.apply(descriptor));
      }
    }
    sorted = true;
    return this;
  }

  public List<Descriptor> getSortedDescriptors() {
    Preconditions.checkArgument(sorted);
    List<Descriptor> descriptors = new ArrayList<>(getNumDescriptors());
    descriptors.addAll(getPrimitiveDescriptors());
    descriptors.addAll(getBoxedDescriptors());
    descriptors.addAll(getBuildInDescriptors());
    descriptors.addAll(getCollectionDescriptors());
    descriptors.addAll(getMapDescriptors());
    descriptors.addAll(getOtherDescriptors());
    return descriptors;
  }

  public Collection<Descriptor> getPrimitiveDescriptors() {
    Preconditions.checkArgument(sorted);
    return primitiveDescriptors;
  }

  public Collection<Descriptor> getBoxedDescriptors() {
    Preconditions.checkArgument(sorted);
    return boxedDescriptors;
  }

  public Collection<Descriptor> getCollectionDescriptors() {
    Preconditions.checkArgument(sorted);
    return collectionDescriptors;
  }

  public Collection<Descriptor> getMapDescriptors() {
    Preconditions.checkArgument(sorted);
    return mapDescriptors;
  }

  public Collection<Descriptor> getBuildInDescriptors() {
    Preconditions.checkArgument(sorted);
    return buildInDescriptors;
  }

  public Collection<Descriptor> getOtherDescriptors() {
    Preconditions.checkArgument(sorted);
    return otherDescriptors;
  }

  private static Descriptor createDescriptor(Descriptor d) {
    Method readMethod = d.getReadMethod();
    if (readMethod != null && !RecordUtils.isRecord(readMethod.getDeclaringClass())) {
      readMethod = null;
    }
    // getter/setter may lose some inner state of an object, so we set them to null.
    if (readMethod == null && d.getWriteMethod() == null) {
      return d;
    }
    return d.copy(readMethod, null);
  }

  public static DescriptorGrouper createDescriptorGrouper(
      Predicate<Descriptor> isBuildIn,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator,
      Comparator<Descriptor> primitiveComparator,
      Comparator<Descriptor> comparator) {
    return createDescriptorGrouper(
        descriptor ->
            TypeUtils.isPrimitive(descriptor.getRawType())
                || TypeUtils.isBoxed(descriptor.getRawType()),
        isBuildIn,
        DescriptorGrouper::isDefaultCollectionDescriptor,
        descriptors,
        descriptorsGroupedOrdered,
        descriptorUpdator,
        primitiveComparator,
        comparator);
  }

  public static DescriptorGrouper createDescriptorGrouper(
      Predicate<Descriptor> usesPrimitiveFieldOrdering,
      Predicate<Descriptor> isBuildIn,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator,
      Comparator<Descriptor> primitiveComparator,
      Comparator<Descriptor> comparator) {
    return createDescriptorGrouper(
        usesPrimitiveFieldOrdering,
        isBuildIn,
        DescriptorGrouper::isDefaultCollectionDescriptor,
        descriptors,
        descriptorsGroupedOrdered,
        descriptorUpdator,
        primitiveComparator,
        comparator);
  }

  public static DescriptorGrouper createDescriptorGrouper(
      Predicate<Descriptor> usesPrimitiveFieldOrdering,
      Predicate<Descriptor> isBuildIn,
      Predicate<Descriptor> isCollection,
      Collection<Descriptor> descriptors,
      boolean descriptorsGroupedOrdered,
      Function<Descriptor, Descriptor> descriptorUpdator,
      Comparator<Descriptor> primitiveComparator,
      Comparator<Descriptor> comparator) {
    return new DescriptorGrouper(
        usesPrimitiveFieldOrdering,
        isBuildIn,
        isCollection,
        descriptors,
        descriptorsGroupedOrdered,
        descriptorUpdator == null ? DescriptorGrouper::createDescriptor : descriptorUpdator,
        primitiveComparator,
        comparator);
  }

  private static boolean isDefaultCollectionDescriptor(Descriptor descriptor) {
    return TypeUtils.isCollection(descriptor.getRawType())
        || TypeAnnotationUtils.usesCollectionProtocolForPrimitiveList(
            descriptor.getTypeAnnotation(), descriptor.getRawType());
  }

  public int getNumDescriptors() {
    Preconditions.checkArgument(sorted);
    return primitiveDescriptors.size()
        + boxedDescriptors.size()
        + collectionDescriptors.size()
        + mapDescriptors.size()
        + buildInDescriptors.size()
        + otherDescriptors.size();
  }
}
