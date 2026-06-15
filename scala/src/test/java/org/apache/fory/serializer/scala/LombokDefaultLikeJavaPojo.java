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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class LombokDefaultLikeJavaPojo {
  private String id;
  private List<String> imageRelations;

  public LombokDefaultLikeJavaPojo() {
    this(null, Collections.emptyList());
  }

  public LombokDefaultLikeJavaPojo(String id, List<String> imageRelations) {
    this.id = id;
    this.imageRelations = imageRelations;
  }

  public String getId() {
    return id;
  }

  public List<String> getImageRelations() {
    return imageRelations;
  }

  private static List<String> $default$imageRelations() {
    return Collections.emptyList();
  }

  private static List<String> $default$1() {
    return Collections.emptyList();
  }

  private List<String> $default$2() {
    return Collections.emptyList();
  }

  private static List<String> $default$99() {
    return Collections.emptyList();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LombokDefaultLikeJavaPojo)) {
      return false;
    }
    LombokDefaultLikeJavaPojo that = (LombokDefaultLikeJavaPojo) o;
    return Objects.equals(id, that.id) && Objects.equals(imageRelations, that.imageRelations);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, imageRelations);
  }
}
