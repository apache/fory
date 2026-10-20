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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.testng.annotations.Test;

/** Covers the token-level reader and writer operations used by external serializers. */
public class ForyJsonTokenOperationTest {
  // One pooled state: an unreleased state would make the next operation wait forever.
  private final ForyJson json =
      ForyJson.builder().withAsyncCompilation(false).withConcurrencyLevel(1).build();

  @Test
  public void writeAndReadObjectTokens() throws IOException {
    byte[] bytes =
        json.writeUtf8(
            writer -> {
              writer.writeObjectStart();
              writer.writeFieldName("id");
              writer.writeInt(7);
              writer.writeComma(1);
              writer.writeFieldName("name");
              writer.writeString("fory");
              writer.writeObjectEnd();
            });
    assertEquals(new String(bytes, StandardCharsets.UTF_8), "{\"id\":7,\"name\":\"fory\"}");

    Pair pair = json.readUtf8(bytes, 0, bytes.length, ForyJsonTokenOperationTest::readPair);
    assertEquals(pair.id, 7);
    assertEquals(pair.name, "fory");
    // The same document still reads through the object-mapping root API.
    assertEquals(json.fromJson(bytes, Pair.class).name, "fory");
  }

  @Test
  public void readsByteRangeAndRejectsTrailingContent() {
    byte[] bytes = "xx{\"id\":3,\"name\":\"a\"}yy".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        json.readUtf8(bytes, 2, bytes.length - 4, ForyJsonTokenOperationTest::readPair).id, 3);

    byte[] trailing = "1 2".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        ForyJsonException.class,
        () -> json.readUtf8(trailing, 0, trailing.length, Utf8JsonReader::readInt));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> json.readUtf8(trailing, 2, 5, Utf8JsonReader::readInt));
  }

  @Test
  public void propagatesOperationFailuresAndReleasesState() {
    byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
    IOException checked = new IOException("checked");
    IOException thrownChecked =
        expectThrows(
            IOException.class,
            () ->
                json.readUtf8(
                    bytes,
                    0,
                    bytes.length,
                    reader -> {
                      throw checked;
                    }));
    assertSame(thrownChecked, checked);
    IllegalStateException runtime = new IllegalStateException("runtime");
    IllegalStateException thrownRuntime =
        expectThrows(
            IllegalStateException.class,
            () ->
                json.writeUtf8(
                    writer -> {
                      writer.writeObjectStart();
                      throw runtime;
                    }));
    assertSame(thrownRuntime, runtime);
    assertThrows(
        ForyJsonException.class,
        () -> json.readUtf8(bytes, 0, bytes.length, reader -> reader.readInt()));

    // The pooled state was released after every failure and the writer starts clean.
    assertEquals(
        new String(json.writeUtf8(writer -> writer.writeInt(1)), StandardCharsets.UTF_8), "1");
    assertEquals(json.toJson(new Pair()), "{\"id\":0}");
    assertTrue(
        json.readUtf8(
            bytes,
            0,
            bytes.length,
            reader -> reader.consumeNextToken('{') && reader.consumeNextToken('}')));
  }

  @Test
  public void readerIsBorrowedFromThePool() {
    byte[] bytes = "true".getBytes(StandardCharsets.UTF_8);
    Utf8JsonReader first =
        json.readUtf8(
            bytes,
            0,
            bytes.length,
            reader -> {
              assertTrue(reader.readBoolean());
              return reader;
            });
    Utf8JsonReader second =
        json.readUtf8(
            bytes,
            0,
            bytes.length,
            reader -> {
              assertTrue(reader.readBoolean());
              return reader;
            });
    assertSame(second, first);
    assertFalse(
        json.readUtf8("false".getBytes(StandardCharsets.UTF_8), 0, 5, Utf8JsonReader::readBoolean));
  }

  private static Pair readPair(Utf8JsonReader reader) {
    Pair pair = new Pair();
    reader.expectNextToken('{');
    if (reader.consumeNextToken('}')) {
      return pair;
    }
    do {
      String name = reader.readFieldName();
      reader.expect(':');
      if (name.equals("id")) {
        pair.id = reader.readInt();
      } else if (name.equals("name")) {
        pair.name = reader.readString();
      } else {
        reader.skipValue();
      }
    } while (reader.consumeCommaOrEndObject());
    return pair;
  }

  public static class Pair {
    public int id;
    public String name;
  }
}
