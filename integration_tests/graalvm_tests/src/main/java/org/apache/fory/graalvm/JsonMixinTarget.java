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

package org.apache.fory.graalvm;

/** Third-party-shaped model configured only through {@link JsonMixinModel}. */
public final class JsonMixinTarget {
  private final int id;
  private final Address address;

  private JsonMixinTarget(int id, Address address) {
    this.id = id;
    this.address = address;
  }

  public int getId() {
    return id;
  }

  public Address getAddress() {
    return address;
  }

  public static JsonMixinTarget create(int id, Address address) {
    return new JsonMixinTarget(id, address);
  }

  public static final class Address {
    public String city;
    public int zip;

    public Address() {}

    public Address(String city, int zip) {
      this.city = city;
      this.zip = zip;
    }
  }
}
