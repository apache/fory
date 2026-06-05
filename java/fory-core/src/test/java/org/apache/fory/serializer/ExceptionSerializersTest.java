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

package org.apache.fory.serializer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.ForyException;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.reflect.ReflectionUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ExceptionSerializersTest extends ForyTestBase {
  @Test(dataProvider = "javaFory")
  public void testBuiltInThrowableRoundTrip(Fory fory) {
    IllegalArgumentException cause = new IllegalArgumentException("inner-cause");
    IllegalStateException value = new IllegalStateException("outer-message", cause);
    value.addSuppressed(new RuntimeException("suppressed-1"));
    value.addSuppressed(new IllegalArgumentException("suppressed-2"));

    IllegalStateException copy = serDe(fory, value);

    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(value.getClass()),
        ExceptionSerializers.ExceptionSerializer.class);
    Assert.assertEquals(copy.getClass(), value.getClass());
    Assert.assertEquals(copy.getMessage(), value.getMessage());
    Assert.assertNotNull(copy.getCause());
    Assert.assertEquals(copy.getCause().getClass(), cause.getClass());
    Assert.assertEquals(copy.getCause().getMessage(), cause.getMessage());
    Assert.assertEquals(copy.getStackTrace().length, value.getStackTrace().length);
    Assert.assertEquals(copy.getStackTrace()[0], value.getStackTrace()[0]);
    Assert.assertEquals(copy.getSuppressed().length, value.getSuppressed().length);
    Assert.assertEquals(copy.getSuppressed()[0].getClass(), RuntimeException.class);
    Assert.assertEquals(copy.getSuppressed()[0].getMessage(), "suppressed-1");
    Assert.assertEquals(copy.getSuppressed()[1].getClass(), IllegalArgumentException.class);
    Assert.assertEquals(copy.getSuppressed()[1].getMessage(), "suppressed-2");
  }

  @Test
  public void testSuppressedCountLimit() {
    Fory writer = Fory.builder().withXlang(false).withCompatible(false).build();
    Fory reader =
        Fory.builder().withXlang(false).withMaxCollectionSize(1).withCompatible(false).build();
    RuntimeException value = new RuntimeException("outer");
    RuntimeException suppressed1 = new RuntimeException("suppressed-1");
    RuntimeException suppressed2 = new RuntimeException("suppressed-2");
    value.setStackTrace(new StackTraceElement[0]);
    suppressed1.setStackTrace(new StackTraceElement[0]);
    suppressed2.setStackTrace(new StackTraceElement[0]);
    value.addSuppressed(suppressed1);
    value.addSuppressed(suppressed2);
    byte[] bytes = writer.serialize(value);

    Assert.assertThrows(ForyException.class, () -> reader.deserialize(bytes));
  }

  @Test(dataProvider = "javaFory")
  public void testStackTraceElementRoundTrip(Fory fory) {
    StackTraceElement value = new Exception().getStackTrace()[0];

    StackTraceElement copy = serDe(fory, value);

    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(StackTraceElement.class),
        ExceptionSerializers.StackTraceElementSerializer.class);
    Assert.assertEquals(copy, value);
  }

  @Test
  public void testThrowableWithoutRefTrackingKeepsSelfCauseField() {
    Fory fory = builder().withRefTracking(false).withCodegen(false).build();
    CustomException value =
        new CustomException("self-cause")
            .withParentCode(7)
            .withTags(new ArrayList<>(Arrays.asList("a", "b")));

    CustomException copy = serDe(fory, value);

    Assert.assertNull(copy.getCause());
    Assert.assertEquals(copy.getSuppressed().length, 0);
    Assert.assertSame(
        ReflectionUtils.getObjectFieldValue(
            copy, ReflectionUtils.getField(Throwable.class, "cause")),
        copy);
    Assert.assertEquals(copy.parentCode, value.parentCode);
    Assert.assertEquals(copy.tags, value.tags);
  }

  @Test
  public void testBuiltInThrowableWithClassRegistrationRequired() {
    Fory fory =
        builder().requireClassRegistration(true).withRefTracking(false).withCodegen(false).build();
    IllegalStateException value =
        new IllegalStateException("registered-built-in", new IllegalArgumentException("cause"));
    value.addSuppressed(new RuntimeException("registered-suppressed"));

    IllegalStateException copy = serDe(fory, value);

    Assert.assertEquals(copy.getMessage(), value.getMessage());
    Assert.assertNotNull(copy.getCause());
    Assert.assertEquals(copy.getCause().getClass(), value.getCause().getClass());
    Assert.assertEquals(copy.getCause().getMessage(), value.getCause().getMessage());
    Assert.assertEquals(copy.getStackTrace()[0], value.getStackTrace()[0]);
    Assert.assertEquals(copy.getSuppressed().length, 1);
    Assert.assertEquals(copy.getSuppressed()[0].getClass(), RuntimeException.class);
    Assert.assertEquals(copy.getSuppressed()[0].getMessage(), "registered-suppressed");
  }

  @Test
  public void testTryWithResourcesSuppressedRoundTrip() {
    Fory fory = builder().withRefTracking(true).withCodegen(false).build();
    RuntimeException value = buildTryWithResourcesException();

    RuntimeException copy = serDe(fory, value);

    Assert.assertEquals(copy.getMessage(), "main-failure");
    Assert.assertEquals(copy.getSuppressed().length, 1);
    Assert.assertEquals(copy.getSuppressed()[0].getClass(), IllegalStateException.class);
    Assert.assertEquals(copy.getSuppressed()[0].getMessage(), "close-failure");
  }

  @Test
  public void testThrowableCycleWithMessageConstructor() {
    Fory fory = builder().withRefTracking(true).withCodegen(false).build();
    CustomException value =
        new CustomException("root")
            .withParentCode(11)
            .withTags(new ArrayList<>(Arrays.asList("root-tag")));
    RuntimeException cause = new RuntimeException("cause");
    value.initCause(cause);
    cause.initCause(value);
    CustomException suppressed =
        new CustomException("suppressed")
            .withParentCode(12)
            .withTags(new ArrayList<>(Arrays.asList("suppressed-tag")));
    suppressed.initCause(value);
    value.addSuppressed(suppressed);

    CustomException copy = serDe(fory, value);

    Assert.assertEquals(copy.getMessage(), "root");
    Assert.assertEquals(copy.parentCode, 11);
    Assert.assertEquals(copy.tags, Arrays.asList("root-tag"));
    Assert.assertEquals(copy.getCause().getMessage(), "cause");
    Assert.assertSame(copy.getCause().getCause(), copy);
    Assert.assertEquals(copy.getSuppressed().length, 1);
    Assert.assertEquals(copy.getSuppressed()[0].getMessage(), "suppressed");
    Assert.assertSame(copy.getSuppressed()[0].getCause(), copy);
    CustomException suppressedCopy = (CustomException) copy.getSuppressed()[0];
    Assert.assertEquals(suppressedCopy.parentCode, 12);
    Assert.assertEquals(suppressedCopy.tags, Arrays.asList("suppressed-tag"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testThrowableReadsMainWireOrderWithCyclicCause() {
    Fory fory = builder().withRefTracking(true).withCodegen(false).build();
    RuntimeException value = new RuntimeException("root");
    RuntimeException cause = new RuntimeException("cause");
    value.initCause(cause);
    cause.initCause(value);

    MemoryBuffer payload = MemoryUtils.buffer(512);
    WriteContext writeContext = fory.getWriteContext();
    writeContext.prepare(payload, null);
    writeContext.getRefWriter().writeRefOrNull(MemoryUtils.buffer(8), value);
    writeContext.writeRef(value.getStackTrace());
    writeContext.writeRef(value.getCause());
    writeContext.writeStringRef(value.getMessage());
    payload.writeVarUInt32(0);
    payload.writeVarUInt32(0);
    payload.writeVarUInt32Small7(2);

    payload.readerIndex(0);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(payload, null, false);
    readContext.preserveRefId();
    Serializer<RuntimeException> serializer =
        (Serializer<RuntimeException>) fory.getTypeResolver().getSerializer(RuntimeException.class);
    RuntimeException copy = serializer.read(readContext);

    Assert.assertEquals(copy.getMessage(), "root");
    Assert.assertEquals(copy.getCause().getMessage(), "cause");
    Assert.assertSame(copy.getCause().getCause(), copy);
  }

  @Test
  public void testThrowableWithoutMessageConstructorUsesFallback() {
    Fory fory = builder().withRefTracking(true).withCodegen(false).build();
    IllegalStateException cause = new IllegalStateException("fallback-cause");
    NoMessageConstructorException value = new NoMessageConstructorException(cause);
    value.code = 99;
    value.addSuppressed(new RuntimeException("fallback-suppressed"));

    NoMessageConstructorException copy = serDe(fory, value);

    Assert.assertEquals(copy.getMessage(), value.getMessage());
    Assert.assertEquals(copy.getCause().getClass(), IllegalStateException.class);
    Assert.assertEquals(copy.getCause().getMessage(), "fallback-cause");
    Assert.assertEquals(copy.code, 99);
    Assert.assertEquals(copy.getSuppressed().length, 1);
    Assert.assertEquals(copy.getSuppressed()[0].getMessage(), "fallback-suppressed");
  }

  @Test
  public void testThrowableCompatibleRoundTrip() {
    Fory fory = builder().withRefTracking(true).withCodegen(false).withCompatible(true).build();
    CustomException cause =
        new CustomException("cause")
            .withParentCode(1)
            .withTags(new ArrayList<>(Arrays.asList("x")));
    CustomException value =
        new CustomException("custom", cause)
            .withParentCode(9)
            .withTags(new ArrayList<>(Arrays.asList("left", "right")));
    value.retryable = true;
    value.addSuppressed(new IllegalStateException("suppressed-custom"));

    CustomException copy = serDe(fory, value);

    Assert.assertEquals(copy.getMessage(), value.getMessage());
    Assert.assertNotNull(copy.getCause());
    Assert.assertEquals(copy.getCause().getClass(), cause.getClass());
    Assert.assertEquals(copy.getCause().getMessage(), cause.getMessage());
    Assert.assertEquals(copy.parentCode, value.parentCode);
    Assert.assertEquals(copy.tags, value.tags);
    Assert.assertEquals(copy.retryable, value.retryable);
    Assert.assertEquals(copy.getSuppressed().length, 1);
    Assert.assertEquals(copy.getSuppressed()[0].getMessage(), "suppressed-custom");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testThrowableRejectsMismatchedClassLayerCount() {
    Fory fory = builder().withRefTracking(false).withCodegen(false).withCompatible(true).build();
    MemoryBuffer payload = MemoryUtils.buffer(128);
    WriteContext writeContext = fory.getWriteContext();
    writeContext.prepare(payload, null);
    writeContext.writeRef(null);
    writeContext.writeRef(null);
    writeContext.writeStringRef("layer-count");
    payload.writeVarUInt32(0);
    payload.writeVarUInt32(0);
    payload.writeVarUInt32Small7(0);

    payload.readerIndex(0);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(payload, null, false);
    Serializer<CustomException> serializer =
        (Serializer<CustomException>) fory.getTypeResolver().getSerializer(CustomException.class);

    Assert.assertThrows(ForyException.class, () -> serializer.read(readContext));
  }

  private static RuntimeException buildTryWithResourcesException() {
    try {
      try (FailingCloseable ignored = new FailingCloseable()) {
        throw new RuntimeException("main-failure");
      }
    } catch (RuntimeException e) {
      return e;
    }
  }

  private static final class FailingCloseable implements AutoCloseable {
    @Override
    public void close() {
      throw new IllegalStateException("close-failure");
    }
  }

  public static class NoMessageConstructorException extends RuntimeException {
    int code;

    public NoMessageConstructorException(Throwable cause) {
      super(cause);
    }
  }

  public static class ParentException extends RuntimeException {
    int parentCode;
    boolean retryable;

    public ParentException(String message) {
      super(message);
    }

    public ParentException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class CustomException extends ParentException {
    List<String> tags;

    public CustomException(String message) {
      super(message);
    }

    public CustomException(String message, Throwable cause) {
      super(message, cause);
    }

    CustomException withParentCode(int parentCode) {
      this.parentCode = parentCode;
      return this;
    }

    CustomException withTags(List<String> tags) {
      this.tags = tags;
      return this;
    }
  }
}
