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

package probe;

import closed.ClosedValue;
import java.nio.charset.StandardCharsets;
import opened.OpenedValue;
import org.apache.fory.json.ForyJson;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.PropertyNamingStrategy;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

public final class Probe {
  private Probe() {}

  public static void main(String[] args) {
    ForyJson json = ForyJson.builder().registerCodec(Value.class, new Codec()).build();
    require("\"latin\"".equals(json.toJson(new Value("latin"))));
    require(
        "\"utf8\""
            .equals(
                new String(
                    json.toJsonBytes(new Value("utf8")), StandardCharsets.UTF_8)));
    require("latin".equals(json.fromJson("\"latin\"", Value.class).text));
    require("\u6587".equals(json.fromJson("\"\u6587\"", Value.class).text));
    require(
        "utf8"
            .equals(
                json.fromJson(
                        "\"utf8\"".getBytes(StandardCharsets.UTF_8), Value.class)
                    .text));

    ForyJson annotated = ForyJson.builder().withAsyncCompilation(false).build();
    AnnotatedHolder holder = new AnnotatedHolder();
    holder.value = new AnnotatedValue("annotated");
    require("{\"value\":\"annotated\"}".equals(annotated.toJson(holder)));
    require(
        "{\"value\":\"annotated\"}"
            .equals(new String(annotated.toJsonBytes(holder), StandardCharsets.UTF_8)));
    require(
        "latin"
            .equals(
                annotated.fromJson("{\"value\":\"latin\"}", AnnotatedHolder.class).value.text));
    require(
        "\u6587"
            .equals(
                annotated.fromJson("{\"value\":\"\u6587\"}", AnnotatedHolder.class).value.text));
    require(
        "utf8"
            .equals(
                annotated
                    .fromJson(
                        "{\"value\":\"utf8\"}".getBytes(StandardCharsets.UTF_8),
                        AnnotatedHolder.class)
                    .value
                    .text));
    require("\"opened\"".equals(annotated.toJson(new OpenedValue("opened"))));
    require("opened".equals(annotated.fromJson("\"opened\"", OpenedValue.class).text));
    try {
      annotated.toJson(new ClosedValue("closed"));
      throw new AssertionError("closed codec package must be rejected");
    } catch (ForyJsonException expected) {
      require(expected.getMessage().contains("export or open package closed"));
      require(ClosedValue.Codec.constructions == 0);
    }

    ForyJson generated =
        ForyJson.builder()
            .withAsyncCompilation(false)
            .withPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)
            .build();
    Pojo pojo = new Pojo();
    pojo.id = 7;
    pojo.name = "latin";
    require("{\"id\":7,\"name\":\"latin\"}".equals(generated.toJson(pojo)));
    require("{\"id\":7,\"name\":\"latin\"}".equals(generated.toJson(pojo, Pojo.class)));
    require(
        "{\"id\":7,\"name\":\"latin\"}"
            .equals(new String(generated.toJsonBytes(pojo), StandardCharsets.UTF_8)));
    require(generated.fromJson("{\"id\":8,\"name\":\"latin\"}", Pojo.class).id == 8);
    require(generated.fromJson("{\"id\":9,\"name\":\"\u6587\"}", Pojo.class).id == 9);
    require(
        generated
                .fromJson(
                    "{\"id\":10,\"name\":\"utf8\"}".getBytes(StandardCharsets.UTF_8),
                    Pojo.class)
                .id
            == 10);
  }

  public static final class Value {
    private final String text;

    private Value(String text) {
      this.text = text;
    }
  }

  public static final class Pojo {
    @JsonProperty("id")
    public int id;
    public String name;

    public Pojo() {}
  }

  @JsonCodec(AnnotatedCodec.class)
  public static final class AnnotatedValue {
    private final String text;

    private AnnotatedValue(String text) {
      this.text = text;
    }
  }

  public static final class AnnotatedHolder {
    public AnnotatedValue value;

    public AnnotatedHolder() {}
  }

  public static final class AnnotatedCodec implements JsonValueCodec<AnnotatedValue> {
    @Override
    public void writeString(StringJsonWriter writer, AnnotatedValue value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.text);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, AnnotatedValue value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.text);
      }
    }

    @Override
    public AnnotatedValue readLatin1(Latin1JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AnnotatedValue(reader.readString());
    }

    @Override
    public AnnotatedValue readUtf16(Utf16JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AnnotatedValue(reader.readString());
    }

    @Override
    public AnnotatedValue readUtf8(Utf8JsonReader reader) {
      return reader.tryReadNullToken() ? null : new AnnotatedValue(reader.readString());
    }
  }

  private static final class Codec implements JsonValueCodec<Value> {
    @Override
    public void writeString(StringJsonWriter writer, Value value) {
      require(writer.typeResolver() != null);
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.text);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Value value) {
      require(writer.typeResolver() != null);
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.text);
      }
    }

    @Override
    public Value readLatin1(Latin1JsonReader reader) {
      require(reader.typeResolver() != null);
      return reader.tryReadNullToken() ? null : new Value(reader.readString());
    }

    @Override
    public Value readUtf16(Utf16JsonReader reader) {
      require(reader.typeResolver() != null);
      return reader.tryReadNullToken() ? null : new Value(reader.readString());
    }

    @Override
    public Value readUtf8(Utf8JsonReader reader) {
      require(reader.typeResolver() != null);
      return reader.tryReadNullToken() ? null : new Value(reader.readString());
    }
  }

  private static void require(boolean condition) {
    if (!condition) {
      throw new AssertionError("custom codec module-path probe failed");
    }
  }
}
