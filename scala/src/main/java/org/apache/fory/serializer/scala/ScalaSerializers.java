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

import org.apache.fory.AbstractThreadSafeFory;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.resolver.TypeResolver;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import scala.collection.immutable.NumericRange;
import scala.collection.immutable.Range;

import static org.apache.fory.serializer.scala.ToFactorySerializers.IterableToFactoryClass;
import static org.apache.fory.serializer.scala.ToFactorySerializers.MapToFactoryClass;

public class ScalaSerializers {

  public static void registerSerializers(ThreadSafeFory fory) {
    AbstractThreadSafeFory threadSafeFory = (AbstractThreadSafeFory) fory;
    threadSafeFory.registerCallback(ScalaSerializers::registerSerializers);
  }

  public static void registerSerializers(Fory fory) {
    TypeResolver resolver = setSerializerFactory(fory);

    resolver.registerSerializer(IterableToFactoryClass, new ToFactorySerializers.IterableToFactorySerializer(fory));
    resolver.registerSerializer(MapToFactoryClass, new ToFactorySerializers.MapToFactorySerializer(fory));

    // Seq
    resolver.register(scala.collection.immutable.Seq.class);
    resolver.register(scala.collection.immutable.Nil$.class);
    resolver.register(scala.collection.immutable.List$.class);
    resolver.register(scala.collection.immutable.$colon$colon.class);
    // StrictOptimizedSeqFactory -> ... extends -> IterableFactory
    resolver.register(scala.collection.immutable.Vector$.class);
    resolver.register("scala.collection.immutable.VectorImpl");
    resolver.register("scala.collection.immutable.Vector0");
    resolver.register("scala.collection.immutable.Vector1");
    resolver.register("scala.collection.immutable.Vector2");
    resolver.register("scala.collection.immutable.Vector3");
    resolver.register("scala.collection.immutable.Vector4");
    resolver.register("scala.collection.immutable.Vector5");
    resolver.register("scala.collection.immutable.Vector6");
    resolver.register(scala.collection.immutable.Queue.class);
    resolver.register(scala.collection.immutable.Queue$.class);
    resolver.register(scala.collection.immutable.LazyList.class);
    resolver.register(scala.collection.immutable.LazyList$.class);
    resolver.register(scala.collection.immutable.ArraySeq.class);
    resolver.register(scala.collection.immutable.ArraySeq$.class);

    // Set
    resolver.register(scala.collection.immutable.Set.class);
    // IterableFactory
    resolver.register(scala.collection.immutable.Set$.class);
    resolver.register(scala.collection.immutable.Set.Set1.class);
    resolver.register(scala.collection.immutable.Set.Set2.class);
    resolver.register(scala.collection.immutable.Set.Set3.class);
    resolver.register(scala.collection.immutable.Set.Set4.class);
    resolver.register(scala.collection.immutable.HashSet.class);
    resolver.register(scala.collection.immutable.TreeSet.class);
    // SortedIterableFactory
    resolver.register(scala.collection.immutable.TreeSet$.class);
    // IterableFactory
    resolver.register(scala.collection.immutable.HashSet$.class);
    resolver.register(scala.collection.immutable.ListSet.class);
    resolver.register(scala.collection.immutable.ListSet$.class);
    resolver.register("scala.collection.immutable.Set$EmptySet$");
    resolver.register("scala.collection.immutable.SetBuilderImpl");
    resolver.register("scala.collection.immutable.SortedMapOps$ImmutableKeySortedSet");

    // Map
    resolver.register(scala.collection.immutable.Map.class);
    resolver.register(scala.collection.immutable.Map$.class);
    resolver.register(scala.collection.immutable.Map.Map1.class);
    resolver.register(scala.collection.immutable.Map.Map2.class);
    resolver.register(scala.collection.immutable.Map.Map3.class);
    resolver.register(scala.collection.immutable.Map.Map4.class);
    resolver.register(scala.collection.immutable.Map.WithDefault.class);
    resolver.register("scala.collection.immutable.MapBuilderImpl");
    resolver.register("scala.collection.immutable.Map$EmptyMap$");
    resolver.register("scala.collection.immutable.SeqMap$EmptySeqMap$");
    resolver.register(scala.collection.immutable.HashMap.class);
    resolver.register(scala.collection.immutable.HashMap$.class);
    resolver.register(scala.collection.immutable.TreeMap.class);
    resolver.register(scala.collection.immutable.TreeMap$.class);
    resolver.register(scala.collection.immutable.SortedMap$.class);
    resolver.register(scala.collection.immutable.TreeSeqMap.class);
    resolver.register(scala.collection.immutable.TreeSeqMap$.class);
    resolver.register(scala.collection.immutable.ListMap.class);
    resolver.register(scala.collection.immutable.ListMap$.class);
    resolver.register(scala.collection.immutable.IntMap.class);
    resolver.register(scala.collection.immutable.IntMap$.class);
    resolver.register(scala.collection.immutable.LongMap.class);
    resolver.register(scala.collection.immutable.LongMap$.class);

    // Range
    resolver.register("scala.math.Numeric$IntIsIntegral$");
    resolver.register("scala.math.Numeric$LongIsIntegral$");
    resolver.registerSerializerAndType(
        Range.Inclusive.class, new RangeSerializer(fory, Range.Inclusive.class));
    resolver.registerSerializerAndType(
        Range.Exclusive.class, new RangeSerializer(fory, Range.Exclusive.class));
    resolver.registerSerializerAndType(
        NumericRange.class, new NumericRangeSerializer<>(fory, NumericRange.class));
    resolver.registerSerializerAndType(
        NumericRange.Exclusive.class,
        new NumericRangeSerializer<>(fory, NumericRange.Exclusive.class));
    resolver.registerSerializerAndType(
        NumericRange.Inclusive.class,
        new NumericRangeSerializer<>(fory, NumericRange.Inclusive.class));

    resolver.register(scala.collection.generic.SerializeEnd$.class);
    resolver.register(scala.collection.generic.DefaultSerializationProxy.class);
    resolver.register(scala.runtime.ModuleSerializationProxy.class);

    // mutable collection types
    resolver.register(scala.collection.mutable.StringBuilder.class);
    resolver.register(scala.collection.mutable.ArrayBuffer.class);
    resolver.register(scala.collection.mutable.ArrayBuffer$.class);
    resolver.register(scala.collection.mutable.ArraySeq.class);
    resolver.register(scala.collection.mutable.ArraySeq$.class);
    resolver.register(scala.collection.mutable.ListBuffer.class);
    resolver.register(scala.collection.mutable.ListBuffer$.class);
    resolver.register(scala.collection.mutable.Buffer$.class);
    resolver.register(scala.collection.mutable.ArrayDeque.class);
    resolver.register(scala.collection.mutable.ArrayDeque$.class);

    resolver.register(scala.collection.mutable.HashSet.class);
    resolver.register(scala.collection.mutable.HashSet$.class);
    resolver.register(scala.collection.mutable.TreeSet.class);
    resolver.register(scala.collection.mutable.TreeSet$.class);

    resolver.register(scala.collection.mutable.HashMap.class);
    resolver.register(scala.collection.mutable.HashMap$.class);
    resolver.register(scala.collection.mutable.TreeMap.class);
    resolver.register(scala.collection.mutable.TreeMap$.class);
    resolver.register(scala.collection.mutable.LinkedHashMap.class);
    resolver.register(scala.collection.mutable.LinkedHashMap$.class);
    resolver.register(scala.collection.mutable.LinkedHashSet.class);
    resolver.register(scala.collection.mutable.LinkedHashSet$.class);
    resolver.register(scala.collection.mutable.LongMap.class);
    resolver.register(scala.collection.mutable.LongMap$.class);

    resolver.register(scala.collection.mutable.Queue.class);
    resolver.register(scala.collection.mutable.Queue$.class);
    resolver.register(scala.collection.mutable.Stack.class);
    resolver.register(scala.collection.mutable.Stack$.class);
    resolver.register(scala.collection.mutable.BitSet.class);
    resolver.register(scala.collection.mutable.BitSet$.class);
  }

  private static TypeResolver setSerializerFactory(Fory fory) {
    TypeResolver resolver = fory.getTypeResolver();
    ScalaDispatcher dispatcher = new ScalaDispatcher();
    SerializerFactory factory = resolver.getSerializerFactory();
    if (factory != null) {
      SerializerFactory newFactory = (f, cls) -> {
        Serializer serializer = factory.createSerializer(f, cls);
        if (serializer == null) {
          serializer = dispatcher.createSerializer(f, cls);
        }
        return serializer;
      };
      resolver.setSerializerFactory(newFactory);
    } else {
      resolver.setSerializerFactory(dispatcher);
    }
    return resolver;
  }
}
