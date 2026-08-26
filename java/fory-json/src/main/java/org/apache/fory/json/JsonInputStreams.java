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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Java 8 implementation for reading a complete JSON input stream. */
final class JsonInputStreams {
  private static final int BUFFER_SIZE = 8192;

  private JsonInputStreams() {}

  static byte[] readAllBytes(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[BUFFER_SIZE];
    int count;
    while ((count = input.read(buffer)) != -1) {
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }
}
