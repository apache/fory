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

package org.apache.fory.test;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.apache.fory.Fory;
import org.testng.annotations.Test;

public class ReadResolveCircularTest {

  @Test
  public void testReadResolveCircular() {
    Container c = new Container("Circular References Test");
    c.addItem(new Item("Item 1"));
    c.addItem(new Item("Item 2"));

    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatible(false)
            .build();
    byte[] bytes = fory.serialize(c);
    System.out.println(fory.deserialize(bytes));
    System.out.println(
        "Container c has label '" + c.getLabel() + "' and has " + c.getItems().size() + " items.");
  }
}

class Container implements Serializable {
  private static final long serialVersionUID = 1L;
  private List<Item> items = new ArrayList<>();
  private String label = "";

  public Container() {}

  public Container(String label) {
    this.label = label;
  }

  public String getLabel() {
    return this.label;
  }

  public void setLabel(String label) {
    this.label = label;
  }

  public boolean addItem(Item item) {
    if (item != null) {
      item.setParent(this);
      return this.items.add(item);
    } else {
      return false;
    }
  }

  public boolean removeItem(Item item) {
    if (item != null) {
      item.setParent(null);
      return this.items.remove(item);
    } else {
      return false;
    }
  }

  public List<Item> getItems() {
    return new ArrayList<Item>(this.items);
  }

  private static final class SerializationProxy implements Serializable {

    private static final long serialVersionUID = 1L;
    private String containerLabel = "";
    private List<Item> items;

    private SerializationProxy() {}

    public SerializationProxy(Container c) {
      this.containerLabel = c.getLabel();
      this.items = new ArrayList<>();
      for (Item item : c.getItems()) {
        // The proxy reconstructs parent links in readResolve; serializing the original back-link
        // would point Item.parent at the proxy object after writeReplace.
        this.items.add(new Item(item.getName()));
      }
    }

    private Object readResolve() {
      Container c = new Container(this.containerLabel);
      for (int i = 0; this.items != null && i < this.items.size(); i++) {
        c.addItem(this.items.get(i));
      }
      return c;
    }
  }

  private Object writeReplace() {
    return new SerializationProxy(this);
  }

  private Object readObject(ObjectInputStream in) throws InvalidObjectException {
    throw new InvalidObjectException("Proxy Required!");
  }
}

class Item implements Serializable {
  private static final long serialVersionUID = 1L;
  private Container parent = null;
  private String name = "";

  public Item() {}

  public Item(String name) {
    this.name = name;
  }

  public Container getParent() {
    return this.parent;
  }

  public void setParent(Container parent) {
    this.parent = parent;
  }

  public String getName() {
    return this.name;
  }

  public void setName(String name) {
    this.name = name;
  }
}
