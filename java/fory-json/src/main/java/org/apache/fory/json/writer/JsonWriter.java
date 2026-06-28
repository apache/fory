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

package org.apache.fory.json.writer;

import org.apache.fory.json.meta.JsonFieldInfo;

public abstract class JsonWriter {
  private final boolean writeNullFields;

  JsonWriter(boolean writeNullFields) {
    this.writeNullFields = writeNullFields;
  }

  public final boolean writeNullFields() {
    return writeNullFields;
  }

  public abstract void writeNull();

  public abstract void writeBoolean(boolean value);

  public abstract void writeInt(int value);

  public abstract void writeLong(long value);

  public abstract void writeFloat(float value);

  public abstract void writeDouble(double value);

  public abstract void writeNumber(String value);

  public abstract void writeChar(char value);

  public abstract void writeString(String value);

  public abstract void writeFieldName(String name);

  public abstract void writeFieldName(JsonFieldInfo field);

  public abstract void writeIntFieldName(int value);

  public abstract void writeLongFieldName(long value);

  public abstract void writeObjectStart();

  public abstract void writeObjectEnd();

  public abstract void writeArrayStart();

  public abstract void writeArrayEnd();

  public abstract void writeComma(int index);
}
