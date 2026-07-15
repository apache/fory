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

package org.apache.fory.android.json.jar;

import org.apache.fory.json.annotation.JsonType;

@JsonType
public class JarPrimitiveBean {
  private boolean boolValue;
  private byte byteValue;
  private char charValue;
  private short shortValue;
  private int intValue;
  private long longValue;
  private float floatValue;
  private double doubleValue;
  private String textValue;

  public JarPrimitiveBean() {}

  public boolean isBoolValue() {
    return boolValue;
  }

  public void setBoolValue(boolean boolValue) {
    this.boolValue = boolValue;
  }

  public byte getByteValue() {
    return byteValue;
  }

  public void setByteValue(byte byteValue) {
    this.byteValue = byteValue;
  }

  public char getCharValue() {
    return charValue;
  }

  public void setCharValue(char charValue) {
    this.charValue = charValue;
  }

  public short getShortValue() {
    return shortValue;
  }

  public void setShortValue(short shortValue) {
    this.shortValue = shortValue;
  }

  public int getIntValue() {
    return intValue;
  }

  public void setIntValue(int intValue) {
    this.intValue = intValue;
  }

  public long getLongValue() {
    return longValue;
  }

  public void setLongValue(long longValue) {
    this.longValue = longValue;
  }

  public float getFloatValue() {
    return floatValue;
  }

  public void setFloatValue(float floatValue) {
    this.floatValue = floatValue;
  }

  public double getDoubleValue() {
    return doubleValue;
  }

  public void setDoubleValue(double doubleValue) {
    this.doubleValue = doubleValue;
  }

  public String getTextValue() {
    return textValue;
  }

  public void setTextValue(String textValue) {
    this.textValue = textValue;
  }
}
