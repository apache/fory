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

package org.apache.fory.meta;

import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.context.MetaReadContext;
import org.apache.fory.context.MetaWriteContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.serializer.SerializerFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Regression test: two identical {@link Fory} instances (same code, shared meta contexts, neither
 * pre-registering a {@code SerializerFactory}-handled class) must round-trip a struct that nests a
 * NON-collection class routed through a factory custom serializer.
 *
 * <p>Without the fix, the writer lazily resolves the nested subclass to its factory custom
 * serializer and encodes root kind {@code NAMED_EXT} (32), while the reader decoding the field
 * through field metadata expects {@code NAMED_COMPATIBLE_STRUCT} (30) — the class is neither
 * registered nor a recognized non-struct family — producing {@code "TypeDef root kind does not
 * match ... expected=30, actual=32"}.
 *
 * <p>{@link #withRegistration_roundTripOk()} documents that pre-registering the subclass also keeps
 * the root kind consistent.
 */
public class TypeDefRootKindSymmetricReproTest {

  /** Base interface of a class that is serialized through the factory custom serializer. */
  public interface Restriction {}

  /** Non-collection subclass routed through the factory custom serializer. */
  public static class MyRestriction implements Restriction {
    private String code;

    public MyRestriction() {}

    public MyRestriction(String code) {
      this.code = code;
    }

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }
  }

  /** Struct whose field is typed with the base interface and holds the subclass at runtime. */
  public static class Container {
    private Restriction restriction;

    public Container() {}

    public Container(Restriction restriction) {
      this.restriction = restriction;
    }

    public Restriction getRestriction() {
      return restriction;
    }

    public void setRestriction(Restriction restriction) {
      this.restriction = restriction;
    }
  }

  /** Custom serializer supplied by the factory for {@link MyRestriction}. */
  public static class RestrictionSerializer extends Serializer<MyRestriction> {
    @Override
    public void write(WriteContext ctx, MyRestriction value) {
      ctx.writeRef(value.getCode());
    }

    @Override
    public MyRestriction read(ReadContext ctx) {
      MyRestriction r = new MyRestriction((String) ctx.readRef());
      ctx.reference(r);
      return r;
    }
  }

  private static SerializerFactory restrictionFactory() {
    return (typeResolver, cls) -> Restriction.class.isAssignableFrom(cls) ? new RestrictionSerializer() : null;
  }

  private static Fory buildFory(boolean preRegister) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withMetaShare(true)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(CompatibleMode.COMPATIBLE)
            .withIntCompressed(true)
            .withLongCompressed(org.apache.fory.config.Int64Encoding.VARINT)
            .withCodegen(true)
            .withAsyncCompilation(false)
            .withSerializerFactory(restrictionFactory())
            .build();
    if (preRegister) {
      fory.register(MyRestriction.class);
    }
    return fory;
  }

  @Test
  public void noRegistration_symmetric_roundTripOk() {
    Fory writer = buildFory(false);
    Fory reader = buildFory(false);
    MetaWriteContext wc = new MetaWriteContext();
    MetaReadContext rc = new MetaReadContext();
    writer.setMetaWriteContext(wc);
    writer.setMetaReadContext(rc);
    reader.setMetaWriteContext(wc);
    reader.setMetaReadContext(rc);

    byte[] bytes = writer.serialize(new Container(new MyRestriction("A")));
    // Regression guard: before the ClassResolver root-kind fix this threw
    // DeserializationException "TypeDef root kind does not match ... expected=30, actual=32".
    Container read = reader.deserialize(bytes, Container.class);
    Assert.assertNotNull(read);
    Assert.assertNotNull(read.getRestriction());
    Assert.assertEquals(((MyRestriction) read.getRestriction()).getCode(), "A");
  }

  @Test
  public void withRegistration_roundTripOk() {
    Fory writer = buildFory(true);
    Fory reader = buildFory(true);
    MetaWriteContext wc = new MetaWriteContext();
    MetaReadContext rc = new MetaReadContext();
    writer.setMetaWriteContext(wc);
    writer.setMetaReadContext(rc);
    reader.setMetaWriteContext(wc);
    reader.setMetaReadContext(rc);

    byte[] bytes = writer.serialize(new Container(new MyRestriction("A")));
    Container read = reader.deserialize(bytes, Container.class);
    Assert.assertNotNull(read);
    Assert.assertNotNull(read.getRestriction());
    Assert.assertEquals(((MyRestriction) read.getRestriction()).getCode(), "A");
  }
}
