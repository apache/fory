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

package org.apache.fory.format.encoder;

import lombok.Data;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.Schema;
import org.apache.fory.format.type.TypeInference;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * BinaryRowEncoder.decode must record the row body size on BinaryRow, not the full payload size
 * including the leading 8-byte schema hash.
 */
public class BinaryRowEncoderPointToTest {

  @Data
  public static class Tiny {
    private int x;
  }

  @Test
  public void decodedRowSizeMatchesRowBody() {
    Schema schema = TypeInference.inferSchema(Tiny.class);
    RowEncoder<Tiny> real = Encoders.bean(Tiny.class);
    Tiny in = new Tiny();
    in.setX(42);
    byte[] payload = real.encode(in);

    BinaryRow[] captured = new BinaryRow[1];
    GeneratedRowEncoder captor =
        new GeneratedRowEncoder() {
          @Override
          public BinaryRow toRow(Object obj) {
            return real.toRow((Tiny) obj);
          }

          @Override
          public Object fromRow(BinaryRow row) {
            captured[0] = row;
            return null;
          }
        };

    BinaryRowEncoder<Tiny> encoder =
        new BinaryRowEncoder<>(
            schema, DefaultCodecFormat.INSTANCE, captor, new BinaryRowWriter(schema), false);
    encoder.decode(payload);

    Assert.assertNotNull(captured[0], "decode must hand the row to fromRow");
    Assert.assertEquals(captured[0].getSizeInBytes(), payload.length - 8);
  }
}
