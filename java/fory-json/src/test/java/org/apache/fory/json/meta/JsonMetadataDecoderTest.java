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

package org.apache.fory.json.meta;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.fory.json.ForyJsonException;
import org.testng.annotations.Test;

public class JsonMetadataDecoderTest {
  @Test
  public void testChunkedDecode() {
    Bytes body = new Bytes();
    body.index(0).byteValue(JsonMetadataFormat.TOKEN_INACCESSIBLE).index(0).index(0);
    byte[] stream = stream(JsonTypeMetadata.OBJECT, record(JsonMetadataFormat.TOKEN, body.bytes()));
    byte[][] chunks = new byte[stream.length][];
    for (int i = 0; i < stream.length; i++) {
      chunks[i] = new byte[] {stream[i]};
    }

    JsonDecodedMetadata decoded =
        JsonMetadataDecoder.decode(
            Model.class,
            JsonTypeMetadata.OBJECT,
            new JsonTypeMetadataData(chunks, new String[][] {{"example.Hidden"}}));

    assertEquals(decoded.tokenCount(), 1);
    assertEquals(decoded.token(0).mode(), JsonMetadataFormat.TOKEN_INACCESSIBLE);
    assertEquals(decoded.token(0).inaccessibleName(), "example.Hidden");
  }

  @Test
  public void testStrictFraming() {
    byte[] valid = stream(JsonTypeMetadata.OBJECT);
    byte[] trailing = new byte[valid.length + 1];
    System.arraycopy(valid, 0, trailing, 0, valid.length);
    assertThrows(
        ForyJsonException.class,
        () ->
            JsonMetadataDecoder.decode(
                Model.class,
                JsonTypeMetadata.OBJECT,
                new JsonTypeMetadataData(new byte[][] {trailing}, null)));

    byte[] nonCanonical =
        new byte[] {'F', 'J', 'M', 'D', (byte) 0x81, 0, JsonTypeMetadata.OBJECT, 0};
    ForyJsonException exception =
        expectThrows(
            ForyJsonException.class,
            () ->
                JsonMetadataDecoder.decode(
                    Model.class,
                    JsonTypeMetadata.OBJECT,
                    new JsonTypeMetadataData(new byte[][] {nonCanonical}, null)));
    assertTrue(exception.getMessage().contains("non-canonical"));
  }

  @Test
  public void testStrictUtf8() {
    Bytes body = new Bytes();
    body.byteValue(0).index(2).byteValue(0xc3).byteValue(0x28);
    byte[] stream =
        stream(JsonTypeMetadata.SUBTYPES, record(JsonMetadataFormat.SUBTYPE_TABLE, body.bytes()));
    ForyJsonException exception =
        expectThrows(
            ForyJsonException.class,
            () ->
                JsonMetadataDecoder.decode(
                    Model.class,
                    JsonTypeMetadata.SUBTYPES,
                    new JsonTypeMetadataData(new byte[][] {stream}, null)));
    assertTrue(exception.getMessage().contains("UTF-8"));
  }

  @Test
  public void testNoUnusedNames() {
    ForyJsonException exception =
        expectThrows(
            ForyJsonException.class,
            () ->
                JsonMetadataDecoder.decode(
                    Model.class,
                    JsonTypeMetadata.OBJECT,
                    new JsonTypeMetadataData(
                        new byte[][] {stream(JsonTypeMetadata.OBJECT)},
                        new String[][] {{"example.Unused"}})));
    assertTrue(exception.getMessage().contains("unused inaccessible type name"));
  }

  public static byte[] stream(int section, byte[]... records) {
    Bytes bytes = new Bytes();
    bytes.byteValue('F').byteValue('J').byteValue('M').byteValue('D');
    bytes.index(JsonMetadataFormat.VERSION).byteValue(section).index(records.length);
    for (byte[] record : records) {
      bytes.raw(record);
    }
    return bytes.bytes();
  }

  public static byte[] record(int tag, byte[] body) {
    return new Bytes().byteValue(tag).index(body.length).raw(body).bytes();
  }

  public static final class Bytes {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    public Bytes byteValue(int value) {
      output.write(value);
      return this;
    }

    public Bytes index(int value) {
      int remaining = value;
      do {
        int next = remaining & 0x7f;
        remaining >>>= 7;
        output.write(remaining == 0 ? next : next | 0x80);
      } while (remaining != 0);
      return this;
    }

    public Bytes string(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      return index(bytes.length).raw(bytes);
    }

    public Bytes raw(byte[] bytes) {
      output.write(bytes, 0, bytes.length);
      return this;
    }

    public byte[] bytes() {
      return output.toByteArray();
    }
  }

  private static final class Model {}
}
