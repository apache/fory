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

package org.apache.fory.format.type;

import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.BinaryView;
import org.apache.arrow.vector.types.pojo.ArrowType.LargeListView;
import org.apache.arrow.vector.types.pojo.ArrowType.ListView;
import org.apache.arrow.vector.types.pojo.ArrowType.RunEndEncoded;
import org.apache.arrow.vector.types.pojo.ArrowType.Utf8View;

/** A default arrow type visitor to make overriding easier. */
public class DefaultTypeVisitor<T> implements ArrowType.ArrowTypeVisitor<T> {

  @Override
  public T visit(ArrowType.Null type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Struct type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.List type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.LargeList type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.FixedSizeList type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Union type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Map type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Int type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.FloatingPoint type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Utf8 type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.LargeUtf8 type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Binary type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.LargeBinary type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.FixedSizeBinary type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Bool type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Decimal type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Date type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Time type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Timestamp type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Interval type) {
    return unsupported(type);
  }

  @Override
  public T visit(ArrowType.Duration type) {
    return unsupported(type);
  }

  @Override
  public T visit(RunEndEncoded type) {
    return unsupported(type);
  }

  @Override
  public T visit(BinaryView type) {
    return unsupported(type);
  }

  @Override
  public T visit(LargeListView type) {
    return unsupported(type);
  }

  @Override
  public T visit(ListView type) {
    return unsupported(type);
  }

  @Override
  public T visit(Utf8View type) {
    return unsupported(type);
  }

  protected T unsupported(ArrowType type) {
    throw new UnsupportedOperationException("Unsupported type " + type);
  }
}
