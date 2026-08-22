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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.fory.reflect.TypeRef;
import org.testng.annotations.Test;

public class JsonStreamDecoderTest {
  private final ForyJson json =
      ForyJson.builder().withAsyncCompilation(false).withConcurrencyLevel(1).build();

  @Test
  public void decodeArrayAtEverySplit() {
    String document = "[\"a,]}\",\"b\\\"c\",\"d\\\\e\",\"你好\"]";
    byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
    List<String> expected = Arrays.asList("a,]}", "b\"c", "d\\e", "你好");
    for (int split = 0; split <= bytes.length; split++) {
      JsonStreamDecoder<String> decoder = json.newArrayStreamDecoder(String.class, 1024);
      List<String> values = new ArrayList<>();
      drain(decoder, ByteBuffer.wrap(bytes, 0, split), values);
      drain(decoder, ByteBuffer.wrap(bytes, split, bytes.length - split), values);
      assertFalse(decoder.finish());
      assertEquals(values, expected, "split=" + split);
    }
  }

  @Test
  public void decodeArrayBoundaries() {
    JsonStreamDecoder<Integer> delimiterAtEnd = json.newArrayStreamDecoder(Integer.class, 16);
    ByteBuffer first = utf8("[1,");
    assertTrue(delimiterAtEnd.decodeNext(first));
    assertEquals(delimiterAtEnd.value(), Integer.valueOf(1));
    assertEquals(first.position(), first.limit());
    ByteBuffer second = utf8("2]");
    assertTrue(delimiterAtEnd.decodeNext(second));
    assertEquals(delimiterAtEnd.value(), Integer.valueOf(2));
    assertEquals(second.position(), second.limit());
    assertFalse(delimiterAtEnd.finish());

    JsonStreamDecoder<Integer> delimiterAtStart = json.newArrayStreamDecoder(Integer.class, 16);
    first = utf8("[1");
    assertFalse(delimiterAtStart.decodeNext(first));
    assertEquals(first.position(), first.limit());
    second = utf8(",2]");
    assertTrue(delimiterAtStart.decodeNext(second));
    assertEquals(delimiterAtStart.value(), Integer.valueOf(1));
    assertEquals(second.position(), 1);
    assertTrue(delimiterAtStart.decodeNext(second));
    assertEquals(delimiterAtStart.value(), Integer.valueOf(2));
    assertEquals(second.position(), second.limit());
    assertFalse(delimiterAtStart.finish());
  }

  @Test
  public void decodeNestedArrayValues() {
    String document =
        "[{\"text\":\"a,]}\",\"nested\":[1,{\"x\":2}]}," + "{\"text\":\"z\",\"nested\":[]}]";
    TypeRef<Map<String, Object>> type = new TypeRef<Map<String, Object>>() {};
    JsonStreamDecoder<Map<String, Object>> decoder = json.newArrayStreamDecoder(type, 4096);
    List<Map<String, Object>> values = decode(decoder, buffers(document, 3, 11, 19, 37));

    assertEquals(values.size(), 2);
    assertEquals(values.get(0).get("text"), "a,]}");
    assertEquals(values.get(1).get("text"), "z");
  }

  @Test
  public void decodeBufferVariants() {
    byte[] bytes = "[1,2,3]".getBytes(StandardCharsets.UTF_8);
    List<ByteBuffer> inputs = new ArrayList<>();
    inputs.add(ByteBuffer.wrap(bytes.clone()));

    ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
    direct.put(bytes).flip();
    inputs.add(direct);

    byte[] padded = new byte[bytes.length + 4];
    System.arraycopy(bytes, 0, padded, 2, bytes.length);
    inputs.add(ByteBuffer.wrap(padded, 2, bytes.length).slice());
    inputs.add(ByteBuffer.wrap(bytes.clone()).asReadOnlyBuffer());

    for (ByteBuffer input : inputs) {
      input.order(ByteOrder.LITTLE_ENDIAN);
      JsonStreamDecoder<Integer> decoder = json.newArrayStreamDecoder(Integer.class, 32);
      assertEquals(decode(decoder, Collections.singletonList(input)), Arrays.asList(1, 2, 3));
      assertEquals(input.order(), ByteOrder.LITTLE_ENDIAN);
      assertEquals(input.position(), input.limit());
    }
  }

  @Test
  public void decodeNdjsonAtEverySplit() {
    String document = "\n {\"v\":1}\r\n\t\r\n{\"v\":2}\n{\"v\":3}";
    byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
    TypeRef<Map<String, Integer>> type = new TypeRef<Map<String, Integer>>() {};
    for (int split = 0; split <= bytes.length; split++) {
      JsonStreamDecoder<Map<String, Integer>> decoder = json.newNdjsonStreamDecoder(type, 128);
      List<Map<String, Integer>> values = new ArrayList<>();
      drain(decoder, ByteBuffer.wrap(bytes, 0, split), values);
      drain(decoder, ByteBuffer.wrap(bytes, split, bytes.length - split), values);
      if (decoder.finish()) {
        values.add(decoder.value());
      }
      assertEquals(values.size(), 3, "split=" + split);
      assertEquals(values.get(0).get("v"), Integer.valueOf(1));
      assertEquals(values.get(2).get("v"), Integer.valueOf(3));
    }
  }

  @Test
  public void decodeNdjsonBoundaries() {
    JsonStreamDecoder<Integer> splitCrlf = json.newNdjsonStreamDecoder(Integer.class, 16);
    ByteBuffer first = utf8("1\r");
    assertFalse(splitCrlf.decodeNext(first));
    assertEquals(first.position(), first.limit());
    ByteBuffer second = utf8("\n2\n");
    assertTrue(splitCrlf.decodeNext(second));
    assertEquals(splitCrlf.value(), Integer.valueOf(1));
    assertEquals(second.position(), 1);
    assertTrue(splitCrlf.decodeNext(second));
    assertEquals(splitCrlf.value(), Integer.valueOf(2));
    assertEquals(second.position(), second.limit());
    assertFalse(splitCrlf.finish());

    JsonStreamDecoder<Integer> splitBlankLine = json.newNdjsonStreamDecoder(Integer.class, 16);
    first = utf8("3\n");
    assertTrue(splitBlankLine.decodeNext(first));
    assertEquals(splitBlankLine.value(), Integer.valueOf(3));
    assertEquals(first.position(), first.limit());
    second = utf8("\n4");
    assertFalse(splitBlankLine.decodeNext(second));
    assertEquals(second.position(), second.limit());
    assertTrue(splitBlankLine.finish());
    assertEquals(splitBlankLine.value(), Integer.valueOf(4));
  }

  @Test
  public void distinguishNullValue() {
    JsonStreamDecoder<String> decoder = json.newArrayStreamDecoder(String.class, 16);
    ByteBuffer input = utf8("[null]");
    assertTrue(decoder.decodeNext(input));
    assertNull(decoder.value());
    assertFalse(decoder.finish());

    JsonStreamDecoder<String> ndjson = json.newNdjsonStreamDecoder(String.class, 16);
    assertFalse(ndjson.decodeNext(utf8("null")));
    assertTrue(ndjson.finish());
    assertNull(ndjson.value());
  }

  @Test
  public void decodeEmptyAndScalarArrays() {
    JsonStreamDecoder<Object> empty = json.newArrayStreamDecoder(Object.class, 8);
    assertEquals(
        decode(empty, Collections.singletonList(utf8(" \t[ ]\r\n"))), Collections.emptyList());

    JsonStreamDecoder<Object> scalars = json.newArrayStreamDecoder(Object.class, 5);
    assertEquals(
        decode(scalars, Collections.singletonList(utf8("[true,false,null]"))),
        Arrays.asList(Boolean.TRUE, Boolean.FALSE, null));
  }

  @Test
  public void enforceValueLimit() {
    JsonStreamDecoder<Integer> exact = json.newArrayStreamDecoder(Integer.class, 3);
    assertEquals(decode(exact, Collections.singletonList(utf8("[123]"))), Arrays.asList(123));

    JsonStreamDecoder<Integer> oversized = json.newArrayStreamDecoder(Integer.class, 2);
    ByteBuffer input = utf8("[123]");
    JsonStreamValueLimitException error =
        expectThrows(JsonStreamValueLimitException.class, () -> oversized.decodeNext(input));
    assertEquals(error.getMaxValueBytes(), 2);
    assertTrue(input.position() > 0);
    assertThrows(IllegalStateException.class, () -> oversized.decodeNext(input));

    JsonStreamDecoder<Integer> crlf = json.newNdjsonStreamDecoder(Integer.class, 3);
    assertEquals(decode(crlf, Collections.singletonList(utf8("123\r\n"))), Arrays.asList(123));

    JsonStreamDecoder<Integer> blank = json.newNdjsonStreamDecoder(Integer.class, 2);
    assertThrows(ForyJsonException.class, () -> blank.decodeNext(utf8("   \n")));

    JsonStreamDecoder<Integer> cumulative = json.newArrayStreamDecoder(Integer.class, 1);
    assertEquals(
        decode(cumulative, Collections.singletonList(utf8("[1,2,3,4]"))),
        Arrays.asList(1, 2, 3, 4));

    JsonStreamDecoder<Integer> leadingWhitespace = json.newArrayStreamDecoder(Integer.class, 1);
    assertEquals(
        decode(leadingWhitespace, Collections.singletonList(utf8("[ \t1]"))),
        Collections.singletonList(1));

    JsonStreamDecoder<Integer> trailingWhitespace = json.newArrayStreamDecoder(Integer.class, 1);
    assertThrows(ForyJsonException.class, () -> trailingWhitespace.decodeNext(utf8("[1 ]")));

    JsonStreamDecoder<Integer> exactBlankLine = json.newNdjsonStreamDecoder(Integer.class, 2);
    assertEquals(
        decode(exactBlankLine, Collections.singletonList(utf8(" \t\n1\n"))),
        Collections.singletonList(1));

    JsonStreamDecoder<Integer> splitExact = json.newArrayStreamDecoder(Integer.class, 3);
    ByteBuffer valueChunk = utf8("[123");
    assertFalse(splitExact.decodeNext(valueChunk));
    assertEquals(valueChunk.position(), valueChunk.limit());
    ByteBuffer delimiterChunk = utf8("]");
    assertTrue(splitExact.decodeNext(delimiterChunk));
    assertEquals(splitExact.value(), Integer.valueOf(123));
    assertEquals(delimiterChunk.position(), delimiterChunk.limit());
    assertFalse(splitExact.finish());
  }

  @Test
  public void rejectInvalidConfiguration() {
    assertThrows(
        NullPointerException.class, () -> json.newArrayStreamDecoder((Class<Object>) null, 1));
    assertThrows(
        NullPointerException.class, () -> json.newNdjsonStreamDecoder((TypeRef<Object>) null, 1));
    assertThrows(IllegalArgumentException.class, () -> json.newArrayStreamDecoder(Object.class, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> json.newArrayStreamDecoder(Object.class, Integer.MAX_VALUE));
  }

  @Test
  public void rejectMalformedFraming() {
    assertArrayFailure("1");
    assertArrayFailure("[1");
    assertArrayFailure("[,1]");
    assertArrayFailure("[1,]");
    assertArrayFailure("[]x");

    JsonStreamDecoder<Boolean> invalid = json.newArrayStreamDecoder(Boolean.class, 16);
    assertThrows(ForyJsonException.class, () -> invalid.decodeNext(utf8("[truX]")));
    assertEquals(json.fromJson("7", Integer.class), Integer.valueOf(7));

    JsonStreamDecoder<Integer> standaloneCr = json.newNdjsonStreamDecoder(Integer.class, 16);
    assertFalse(standaloneCr.decodeNext(utf8("1\r")));
    assertThrows(ForyJsonException.class, () -> standaloneCr.decodeNext(utf8("2\n")));
  }

  @Test
  public void enforceTerminalState() {
    JsonStreamDecoder<Integer> decoder = json.newArrayStreamDecoder(Integer.class, 16);
    assertThrows(IllegalStateException.class, decoder::value);
    ByteBuffer input = utf8("[1]");
    assertTrue(decoder.decodeNext(input));
    assertEquals(decoder.value(), Integer.valueOf(1));
    assertFalse(decoder.finish());
    assertThrows(IllegalStateException.class, decoder::finish);
    assertThrows(IllegalStateException.class, () -> decoder.decodeNext(ByteBuffer.allocate(0)));

    JsonStreamDecoder<Integer> invalid = json.newArrayStreamDecoder(Integer.class, 16);
    assertThrows(NullPointerException.class, () -> invalid.decodeNext(null));
    assertThrows(IllegalStateException.class, () -> invalid.decodeNext(ByteBuffer.allocate(0)));
  }

  @Test
  public void invalidatePreviousValue() {
    JsonStreamDecoder<Integer> decoder = json.newArrayStreamDecoder(Integer.class, 16);
    ByteBuffer input = utf8("[1,2]");
    assertTrue(decoder.decodeNext(input));
    assertEquals(decoder.value(), Integer.valueOf(1));
    assertEquals(input.position(), 3);
    assertTrue(decoder.decodeNext(input));
    assertEquals(decoder.value(), Integer.valueOf(2));
    assertEquals(input.position(), input.limit());
    assertFalse(decoder.decodeNext(input));
    assertThrows(IllegalStateException.class, decoder::value);
    assertFalse(decoder.finish());
  }

  private void assertArrayFailure(String document) {
    JsonStreamDecoder<Integer> decoder = json.newArrayStreamDecoder(Integer.class, 32);
    ByteBuffer input = utf8(document);
    assertThrows(
        RuntimeException.class,
        () -> {
          while (decoder.decodeNext(input)) {
            decoder.value();
          }
          decoder.finish();
        });
  }

  private static <T> List<T> decode(JsonStreamDecoder<T> decoder, List<ByteBuffer> inputs) {
    List<T> values = new ArrayList<>();
    for (ByteBuffer input : inputs) {
      drain(decoder, input, values);
    }
    if (decoder.finish()) {
      values.add(decoder.value());
    }
    return values;
  }

  private static <T> void drain(JsonStreamDecoder<T> decoder, ByteBuffer input, List<T> values) {
    while (decoder.decodeNext(input)) {
      values.add(decoder.value());
    }
  }

  private static List<ByteBuffer> buffers(String value, int... ends) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    List<ByteBuffer> buffers = new ArrayList<>();
    int start = 0;
    for (int end : ends) {
      buffers.add(ByteBuffer.wrap(bytes, start, end - start));
      start = end;
    }
    buffers.add(ByteBuffer.wrap(bytes, start, bytes.length - start));
    return buffers;
  }

  private static ByteBuffer utf8(String value) {
    return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
  }
}
