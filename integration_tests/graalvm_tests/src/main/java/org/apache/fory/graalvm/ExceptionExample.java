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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.ExceptionSerializers;
import org.apache.fory.util.Preconditions;

public class ExceptionExample {
  private static final Fory FORY =
      Fory.builder()
          .withName(ExceptionExample.class.getName())
          .requireClassRegistration(true)
          .withRefTracking(false)
          .build();

  static {
    FORY.register(CustomException.class);
  }

  public static void main(String[] args) {
    testBuiltInException();
    testCustomException();
    System.out.println("ExceptionExample succeed");
  }

  private static void testBuiltInException() {
    IllegalArgumentException cause = new IllegalArgumentException("built-in-cause");
    IllegalStateException value = new IllegalStateException("built-in", cause);
    IllegalStateException copy = (IllegalStateException) FORY.deserialize(FORY.serialize(value));
    Preconditions.checkArgument(
        FORY.getTypeResolver().getSerializerClass(IllegalStateException.class)
            == ExceptionSerializers.ExceptionSerializer.class);
    Preconditions.checkArgument(copy.getMessage().equals(value.getMessage()));
    Preconditions.checkArgument(copy.getCause() != null);
    Preconditions.checkArgument(copy.getCause().getClass() == cause.getClass());
    Preconditions.checkArgument(copy.getCause().getMessage().equals(cause.getMessage()));
    Preconditions.checkArgument(copy.getStackTrace().length == value.getStackTrace().length);
    Preconditions.checkArgument(copy.getStackTrace()[0].equals(value.getStackTrace()[0]));
  }

  private static void testCustomException() {
    CustomException value =
        new CustomException("custom-native")
            .withParentCode(42)
            .withTags(new ArrayList<>(Arrays.asList("left", "right")));
    value.retryable = true;
    CustomException copy = (CustomException) FORY.deserialize(FORY.serialize(value));
    Preconditions.checkArgument(
        FORY.getTypeResolver().getSerializerClass(CustomException.class)
            == ExceptionSerializers.ExceptionSerializer.class);
    Preconditions.checkArgument(copy.getMessage().equals(value.getMessage()));
    Preconditions.checkArgument(copy.parentCode == value.parentCode);
    Preconditions.checkArgument(copy.retryable == value.retryable);
    Preconditions.checkArgument(copy.tags.equals(value.tags));
    Preconditions.checkArgument(copy.getCause() == null);
    Preconditions.checkArgument(
        ReflectionUtils.getObjectFieldValue(
                copy, ReflectionUtils.getField(Throwable.class, "cause"))
            == copy);
  }

  public static class ParentException extends RuntimeException {
    int parentCode;
    boolean retryable;

    public ParentException(String message) {
      super(message);
    }
  }

  public static class CustomException extends ParentException {
    List<String> tags;

    public CustomException(String message) {
      super(message);
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
