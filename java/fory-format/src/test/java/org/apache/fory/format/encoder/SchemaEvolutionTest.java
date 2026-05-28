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

package org.apache.fory.format.encoder;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.apache.fory.format.annotation.ForySchema;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SchemaEvolutionTest {

  /** Original v1 bean: just a name and an age. */
  @Data
  public static class PersonV1 {
    private String name;
    private int age;
  }

  /**
   * v2: added an email. The codec built against this class must still be able to read v1 payloads
   * (email will default to null).
   */
  @Data
  public static class PersonV2 {
    private String name;
    private int age;

    @ForyVersion(since = 2)
    private String email;
  }

  /**
   * v3: same as v2 with the age field removed. The codec built against this class must read v1
   * payloads (with age) and v2 payloads (with age + email).
   */
  @Data
  @ForySchema(removedFields = PersonV3.History.class)
  public static class PersonV3 {
    private String name;

    @ForyVersion(since = 2)
    private String email;

    interface History {
      @ForyVersion(until = 3)
      int age();
    }
  }

  /** Round-trip at the current version: writing PersonV2, reading PersonV2 with evolution on. */
  @Test
  public void currentVersionRoundTrip() {
    RowEncoder<PersonV2> codec =
        Encoders.buildBeanCodec(PersonV2.class).withSchemaEvolution().build().get();
    PersonV2 in = new PersonV2();
    in.setName("alice");
    in.setAge(30);
    in.setEmail("alice@example.com");
    byte[] bytes = codec.encode(in);
    PersonV2 out = codec.decode(bytes);
    Assert.assertEquals(out.getName(), "alice");
    Assert.assertEquals(out.getAge(), 30);
    Assert.assertEquals(out.getEmail(), "alice@example.com");
  }

  /**
   * The crux: a payload produced by PersonV1 (literally a different Java class with the v1-shaped
   * schema) decoded by PersonV2's evolution-enabled codec. We use PersonV1 as a stand-in for "what
   * older code wrote." Both classes are encoded with schema evolution on so they share the
   * strict-hash format; PersonV1's history is a single entry, and PersonV2's history contains both
   * v1 (without email) and v2 (with email) entries that match PersonV1's single entry by hash.
   */
  @Test
  public void olderPayloadReadByNewerCodec() {
    RowEncoder<PersonV1> oldWriter =
        Encoders.buildBeanCodec(PersonV1.class).withSchemaEvolution().build().get();
    RowEncoder<PersonV2> newReader =
        Encoders.buildBeanCodec(PersonV2.class).withSchemaEvolution().build().get();

    PersonV1 in = new PersonV1();
    in.setName("alice");
    in.setAge(30);
    byte[] bytes = oldWriter.encode(in);

    PersonV2 out = newReader.decode(bytes);
    Assert.assertEquals(out.getName(), "alice");
    Assert.assertEquals(out.getAge(), 30);
    Assert.assertNull(out.getEmail());
  }

  // --- Compact row format ---

  @Test
  public void compactRowOlderPayloadReadByNewerCodec() {
    RowEncoder<PersonV1> oldWriter =
        Encoders.buildBeanCodec(PersonV1.class)
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    RowEncoder<PersonV2> newReader =
        Encoders.buildBeanCodec(PersonV2.class)
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    PersonV1 in = new PersonV1();
    in.setName("bob");
    in.setAge(42);
    byte[] bytes = oldWriter.encode(in);
    PersonV2 out = newReader.decode(bytes);
    Assert.assertEquals(out.getName(), "bob");
    Assert.assertEquals(out.getAge(), 42);
    Assert.assertNull(out.getEmail());
  }

  /**
   * The byte[] overloads use bytes.length for the body size; the MemoryBuffer overloads write and
   * read an embedded int32 size prefix ahead of the 8-byte hash. That framing is a distinct code
   * path, so exercise a projection hit (older payload, newer reader) through it. Two records are
   * written into one buffer and read back in order to confirm the reader advances past each
   * record's embedded size.
   */
  @Test
  public void streamingOlderPayloadReadByNewerCodec() {
    RowEncoder<PersonV1> oldWriter =
        Encoders.buildBeanCodec(PersonV1.class).withSchemaEvolution().build().get();
    RowEncoder<PersonV2> newReader =
        Encoders.buildBeanCodec(PersonV2.class).withSchemaEvolution().build().get();

    PersonV1 alice = new PersonV1();
    alice.setName("alice");
    alice.setAge(30);
    PersonV1 bob = new PersonV1();
    bob.setName("bob");
    bob.setAge(42);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    oldWriter.encode(buffer, alice);
    oldWriter.encode(buffer, bob);

    PersonV2 outAlice = newReader.decode(buffer);
    PersonV2 outBob = newReader.decode(buffer);
    Assert.assertEquals(outAlice.getName(), "alice");
    Assert.assertEquals(outAlice.getAge(), 30);
    Assert.assertNull(outAlice.getEmail());
    Assert.assertEquals(outBob.getName(), "bob");
    Assert.assertEquals(outBob.getAge(), 42);
    Assert.assertNull(outBob.getEmail());
  }

  // --- Array of versioned beans ---

  @Test
  public void arrayStandardOlderPayloadReadByNewerCodec() {
    ArrayEncoder<List<PersonV1>> oldWriter =
        Encoders.buildArrayCodec(new TypeRef<List<PersonV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<PersonV2>> newReader =
        Encoders.buildArrayCodec(new TypeRef<List<PersonV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    PersonV1 a = new PersonV1();
    a.setName("alice");
    a.setAge(30);
    PersonV1 b = new PersonV1();
    b.setName("bob");
    b.setAge(42);
    byte[] bytes = oldWriter.encode(Arrays.asList(a, b));
    List<PersonV2> out = newReader.decode(bytes);
    Assert.assertEquals(out.size(), 2);
    Assert.assertEquals(out.get(0).getName(), "alice");
    Assert.assertEquals(out.get(0).getAge(), 30);
    Assert.assertNull(out.get(0).getEmail());
    Assert.assertEquals(out.get(1).getName(), "bob");
  }

  @Test
  public void arrayCompactOlderPayloadReadByNewerCodec() {
    ArrayEncoder<List<PersonV1>> oldWriter =
        Encoders.buildArrayCodec(new TypeRef<List<PersonV1>>() {})
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<PersonV2>> newReader =
        Encoders.buildArrayCodec(new TypeRef<List<PersonV2>>() {})
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    PersonV1 p = new PersonV1();
    p.setName("carol");
    p.setAge(25);
    byte[] bytes = oldWriter.encode(Arrays.asList(p));
    List<PersonV2> out = newReader.decode(bytes);
    Assert.assertEquals(out.size(), 1);
    Assert.assertEquals(out.get(0).getName(), "carol");
    Assert.assertEquals(out.get(0).getAge(), 25);
    Assert.assertNull(out.get(0).getEmail());
  }

  // --- Map with versioned bean values ---

  @Test
  public void mapStandardOlderPayloadReadByNewerCodec() {
    MapEncoder<Map<String, PersonV1>> oldWriter =
        Encoders.buildMapCodec(new TypeRef<Map<String, PersonV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<String, PersonV2>> newReader =
        Encoders.buildMapCodec(new TypeRef<Map<String, PersonV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    Map<String, PersonV1> in = new HashMap<>();
    PersonV1 p = new PersonV1();
    p.setName("dave");
    p.setAge(40);
    in.put("k1", p);
    byte[] bytes = oldWriter.encode(in);
    Map<String, PersonV2> out = newReader.decode(bytes);
    Assert.assertEquals(out.size(), 1);
    Assert.assertEquals(out.get("k1").getName(), "dave");
    Assert.assertEquals(out.get("k1").getAge(), 40);
    Assert.assertNull(out.get("k1").getEmail());
  }

  @Test
  public void mapCompactOlderPayloadReadByNewerCodec() {
    MapEncoder<Map<String, PersonV1>> oldWriter =
        Encoders.buildMapCodec(new TypeRef<Map<String, PersonV1>>() {})
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    MapEncoder<Map<String, PersonV2>> newReader =
        Encoders.buildMapCodec(new TypeRef<Map<String, PersonV2>>() {})
            .compactEncoding()
            .withSchemaEvolution()
            .build()
            .get();
    Map<String, PersonV1> in = new HashMap<>();
    PersonV1 p = new PersonV1();
    p.setName("eve");
    p.setAge(28);
    in.put("k1", p);
    byte[] bytes = oldWriter.encode(in);
    Map<String, PersonV2> out = newReader.decode(bytes);
    Assert.assertEquals(out.get("k1").getName(), "eve");
    Assert.assertEquals(out.get("k1").getAge(), 28);
    Assert.assertNull(out.get("k1").getEmail());
  }

  // --- Interface-typed beans ---
  //
  // The wire field name is derived from each interface's accessor method name (via
  // lowerCamelToLowerUnderscore), so two interfaces that share the same accessor names produce
  // the same wire layout. Use accessor-style getters consistently across versions.

  /** v1 interface: just name and age. */
  public interface PersonIfaceV1 {
    String getName();

    int getAge();
  }

  /** v2 interface: adds email. Same accessor naming so the wire field names match. */
  public interface PersonIfaceV2 {
    String getName();

    int getAge();

    @ForyVersion(since = 2)
    String getEmail();
  }

  @Test
  public void interfaceOlderPayloadReadByNewerCodec() {
    RowEncoder<PersonIfaceV1> oldWriter =
        Encoders.buildBeanCodec(PersonIfaceV1.class).withSchemaEvolution().build().get();
    RowEncoder<PersonIfaceV2> newReader =
        Encoders.buildBeanCodec(PersonIfaceV2.class).withSchemaEvolution().build().get();
    PersonIfaceV1 in =
        new PersonIfaceV1() {
          public String getName() {
            return "alice";
          }

          public int getAge() {
            return 30;
          }
        };
    byte[] bytes = oldWriter.encode(in);
    PersonIfaceV2 out = newReader.decode(bytes);
    Assert.assertEquals(out.getName(), "alice");
    Assert.assertEquals(out.getAge(), 30);
    // email was added in v2; v1 payload has none. The interface proxy returns the default.
    Assert.assertNull(out.getEmail());
  }

  /**
   * v3 interface: name and email; age removed (only present in v1 and v2). The history interface
   * declares the removed field's original signature; its method name follows the same JavaBeans
   * accessor convention as the live interface, so {@code getAge()} maps to wire name {@code age}.
   */
  @ForySchema(removedFields = PersonIfaceV3.History.class)
  public interface PersonIfaceV3 {
    String getName();

    @ForyVersion(since = 2)
    String getEmail();

    interface History {
      @ForyVersion(until = 3)
      int getAge();
    }
  }

  @Test
  public void interfaceRemovedFieldReadByNewerCodec() {
    RowEncoder<PersonIfaceV2> v2Writer =
        Encoders.buildBeanCodec(PersonIfaceV2.class).withSchemaEvolution().build().get();
    RowEncoder<PersonIfaceV3> v3Reader =
        Encoders.buildBeanCodec(PersonIfaceV3.class).withSchemaEvolution().build().get();
    PersonIfaceV2 in =
        new PersonIfaceV2() {
          public String getName() {
            return "alice";
          }

          public int getAge() {
            return 30;
          }

          public String getEmail() {
            return "alice@example.com";
          }
        };
    byte[] bytes = v2Writer.encode(in);
    PersonIfaceV3 out = v3Reader.decode(bytes);
    Assert.assertEquals(out.getName(), "alice");
    Assert.assertEquals(out.getEmail(), "alice@example.com");
  }

  /** Removed-field test: v3 codec reads v2 payload, dropping the no-longer-present 'age'. */
  @Test
  public void removedFieldReadByNewerCodec() {
    RowEncoder<PersonV2> v2Writer =
        Encoders.buildBeanCodec(PersonV2.class).withSchemaEvolution().build().get();
    RowEncoder<PersonV3> v3Reader =
        Encoders.buildBeanCodec(PersonV3.class).withSchemaEvolution().build().get();

    PersonV2 in = new PersonV2();
    in.setName("alice");
    in.setAge(30);
    in.setEmail("alice@example.com");
    byte[] bytes = v2Writer.encode(in);

    PersonV3 out = v3Reader.decode(bytes);
    Assert.assertEquals(out.getName(), "alice");
    Assert.assertEquals(out.getEmail(), "alice@example.com");
  }

  // ---------------------------------------------------------------------------
  // Compositional test
  //
  // Outer mutable bean evolves v1 -> v2 (adds displayName, removes legacyName).
  // The bean carries diverse nested data shapes that themselves do not evolve:
  // a concrete struct, an interface-typed struct (lazy proxy), an inline list
  // of structs, and an inline map<string, struct>. The test exercises one
  // dispatch boundary (the outer codec, or the outer list codec) and verifies
  // that the projected outer correctly carries every nested shape through.
  // ---------------------------------------------------------------------------

  @Data
  public static class Profile {
    private String bio;
    private int rating;
  }

  /** Address is interface-typed; the row codec generates a lazy proxy for reads. */
  public interface Address {
    String getStreet();

    String getCity();
  }

  @Data
  public static class Item {
    private String name;
    private long quantity;
  }

  @Data
  public static class OuterV1 {
    private long id;
    private String legacyName;
    private Profile profile;
    private Address address;
    private List<Item> items;
    private Map<String, Item> properties;
  }

  /**
   * OuterV2 adds {@code displayName} at version 2 and removes {@code legacyName} at version 2.
   * Everything else carries forward unchanged. The compositional test writes an OuterV1 and reads
   * as OuterV2.
   */
  @Data
  @ForySchema(removedFields = OuterV2.History.class)
  public static class OuterV2 {
    private long id;

    @ForyVersion(since = 2)
    private String displayName;

    private Profile profile;
    private Address address;
    private List<Item> items;
    private Map<String, Item> properties;

    interface History {
      @ForyVersion(until = 2)
      String legacyName();
    }
  }

  private static OuterV1 sampleV1() {
    OuterV1 in = new OuterV1();
    in.setId(7);
    in.setLegacyName("retired");
    Profile p = new Profile();
    p.setBio("hello");
    p.setRating(5);
    in.setProfile(p);
    in.setAddress(
        new Address() {
          public String getStreet() {
            return "1 Main";
          }

          public String getCity() {
            return "Springfield";
          }
        });
    Item a = new Item();
    a.setName("a");
    a.setQuantity(1);
    Item b = new Item();
    b.setName("b");
    b.setQuantity(2);
    in.setItems(Arrays.asList(a, b));
    Map<String, Item> props = new HashMap<>();
    props.put("k1", a);
    props.put("k2", b);
    in.setProperties(props);
    return in;
  }

  private static void assertProjectedToV2(OuterV2 out) {
    Assert.assertEquals(out.getId(), 7);
    Assert.assertNull(out.getDisplayName()); // added in v2, absent in v1 wire
    Assert.assertEquals(out.getProfile().getBio(), "hello");
    Assert.assertEquals(out.getProfile().getRating(), 5);
    Assert.assertEquals(out.getAddress().getStreet(), "1 Main");
    Assert.assertEquals(out.getAddress().getCity(), "Springfield");
    Assert.assertEquals(out.getItems().size(), 2);
    Assert.assertEquals(out.getItems().get(0).getName(), "a");
    Assert.assertEquals(out.getItems().get(1).getQuantity(), 2);
    Assert.assertEquals(out.getProperties().get("k1").getName(), "a");
    Assert.assertEquals(out.getProperties().get("k2").getQuantity(), 2);
  }

  @Test
  public void compositionalRowEvolution() {
    RowEncoder<OuterV1> writer =
        Encoders.buildBeanCodec(OuterV1.class).withSchemaEvolution().build().get();
    RowEncoder<OuterV2> reader =
        Encoders.buildBeanCodec(OuterV2.class).withSchemaEvolution().build().get();
    byte[] bytes = writer.encode(sampleV1());
    assertProjectedToV2(reader.decode(bytes));
  }

  @Test
  public void compositionalArrayEvolution() {
    ArrayEncoder<List<OuterV1>> writer =
        Encoders.buildArrayCodec(new TypeRef<List<OuterV1>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    ArrayEncoder<List<OuterV2>> reader =
        Encoders.buildArrayCodec(new TypeRef<List<OuterV2>>() {})
            .withSchemaEvolution()
            .build()
            .get();
    byte[] bytes = writer.encode(Arrays.asList(sampleV1(), sampleV1()));
    List<OuterV2> out = reader.decode(bytes);
    Assert.assertEquals(out.size(), 2);
    assertProjectedToV2(out.get(0));
    assertProjectedToV2(out.get(1));
  }
}
