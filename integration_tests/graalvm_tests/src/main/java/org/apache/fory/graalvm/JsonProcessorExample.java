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

import org.apache.fory.json.ForyJson;
import org.apache.fory.json.annotation.JsonMixin;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.util.Preconditions;

/**
 * Models compiled with the annotation processor alongside the unprocessed Native Image fixtures.
 */
public final class JsonProcessorExample {
  private JsonProcessorExample() {}

  public static void verify() {
    for (boolean codegen : new boolean[] {true, false}) {
      ForyJson json = ForyJson.builder().withCodegen(codegen).build();
      User user = json.fromJson("{\"id\":7,\"name\":\"Ada\"}", User.class);
      Preconditions.checkArgument(user.id() == 7 && user.name().equals("Ada"));
      Preconditions.checkArgument(json.fromJson(json.toJson(user), User.class).equals(user));
      Message message =
          json.fromJson("{\"kind\":\"TextMessage\",\"text\":\"hello\"}", Message.class);
      Preconditions.checkArgument(message.equals(new TextMessage("hello")));

      ForyJson mixinJson =
          ForyJson.builder().withCodegen(codegen).registerMixin(ExternalMixin.class).build();
      External external = mixinJson.fromJson("{\"user_id\":9}", External.class);
      Preconditions.checkArgument(external.getId() == 9);
      Preconditions.checkArgument(mixinJson.toJson(external).equals("{\"user_id\":9}"));
    }
  }

  @JsonType
  public record User(long id, String name) {}

  @JsonType
  @JsonSubTypes(property = "kind")
  public sealed interface Message permits TextMessage {}

  @JsonType
  public record TextMessage(String text) implements Message {}

  public static final class External {
    private int id;

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id;
    }
  }

  @JsonMixin(target = External.class)
  public abstract static class ExternalMixin {
    @JsonProperty("user_id")
    public abstract int getId();
  }
}
