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

package org.apache.fory.serializer.collection;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CollectionCompatibleSerializerTest extends ForyTestBase {

  @Test
  public void testCustomSetCompatible() {
    Fory writer = compatibleFory();
    Fory reader = compatibleFory();
    CustomSet set = new CustomSet("set-marker");
    set.add("a");
    set.add("b");

    SetHolder holder = new SetHolder();
    holder.values = set;

    SetHolder copy = serDe(writer, reader, holder);
    Assert.assertEquals(copy.values.getClass(), CustomSet.class);
    CustomSet copySet = (CustomSet) copy.values;
    Assert.assertEquals(copySet.getMarker(), "set-marker");
    Assert.assertEquals(copySet, set);
  }

  @Test
  public void testCustomMapCompatible() {
    Fory writer = compatibleFory();
    Fory reader = compatibleFory();
    CustomMap map = new CustomMap("map-marker");
    map.put("k1", "v1");
    map.put("k2", "v2");

    MapHolder holder = new MapHolder();
    holder.values = map;

    MapHolder copy = serDe(writer, reader, holder);
    Assert.assertEquals(copy.values.getClass(), CustomMap.class);
    CustomMap copyMap = (CustomMap) copy.values;
    Assert.assertEquals(copyMap.getMarker(), "map-marker");
    Assert.assertEquals(copyMap, map);
  }

  private static Fory compatibleFory() {
    return builder().withRefTracking(true).withCodegen(true).withCompatible(true).build();
  }

  public static final class SetHolder {
    public Set<String> values;
  }

  public static final class MapHolder {
    public Map<String, String> values;
  }

  public static final class CustomSet extends AbstractSet<String> {
    private LinkedHashSet<String> values = new LinkedHashSet<>();
    private String marker;

    public CustomSet() {}

    private CustomSet(String marker) {
      this.marker = marker;
    }

    public String getMarker() {
      return marker;
    }

    @Override
    public boolean add(String value) {
      return values.add(value);
    }

    @Override
    public Iterator<String> iterator() {
      return values.iterator();
    }

    @Override
    public int size() {
      return values.size();
    }
  }

  public static final class CustomMap extends AbstractMap<String, String> {
    private LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private String marker;

    public CustomMap() {}

    private CustomMap(String marker) {
      this.marker = marker;
    }

    public String getMarker() {
      return marker;
    }

    @Override
    public String put(String key, String value) {
      return values.put(key, value);
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
      return values.entrySet();
    }
  }
}
