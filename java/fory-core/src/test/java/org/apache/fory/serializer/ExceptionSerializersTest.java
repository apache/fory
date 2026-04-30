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
import org.apache.fory.reflect.ReflectionUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ExceptionSerializersTest extends ForyTestBase {
  @Test(dataProvider = "javaFory")
  public void testBuiltInThrowableRoundTrip(Fory fory) {
    IllegalArgumentException cause = new IllegalArgumentException("inner-cause");
    IllegalStateException value = new IllegalStateException("outer-message", cause);

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

    IllegalStateException copy = serDe(fory, value);

    Assert.assertEquals(copy.getMessage(), value.getMessage());
    Assert.assertNotNull(copy.getCause());
    Assert.assertEquals(copy.getCause().getClass(), value.getCause().getClass());
    Assert.assertEquals(copy.getCause().getMessage(), value.getCause().getMessage());
    Assert.assertEquals(copy.getStackTrace()[0], value.getStackTrace()[0]);
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

    CustomException copy = serDe(fory, value);

    Assert.assertEquals(copy.getMessage(), value.getMessage());
    Assert.assertNotNull(copy.getCause());
    Assert.assertEquals(copy.getCause().getClass(), cause.getClass());
    Assert.assertEquals(copy.getCause().getMessage(), cause.getMessage());
    Assert.assertEquals(copy.parentCode, value.parentCode);
    Assert.assertEquals(copy.tags, value.tags);
    Assert.assertEquals(copy.retryable, value.retryable);
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
