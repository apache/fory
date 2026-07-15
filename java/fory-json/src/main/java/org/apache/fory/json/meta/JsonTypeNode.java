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

import org.apache.fory.annotation.Internal;

/** One inert generated generic-type node whose referenced tokens remain unresolved. */
@Internal
public final class JsonTypeNode {
  private static final int[] NO_INDEXES = new int[0];

  private final int kind;
  private final int codecToken;
  private final int codecOperation;
  private final String codecSource;
  private final int token;
  private final int ownerNode;
  private final int[] children;
  private final int[] lowerBounds;
  private final int variableKey;

  JsonTypeNode(
      int kind,
      int codecToken,
      int codecOperation,
      String codecSource,
      int token,
      int ownerNode,
      int[] children,
      int[] lowerBounds,
      int variableKey) {
    this.kind = kind;
    this.codecToken = codecToken;
    this.codecOperation = codecOperation;
    this.codecSource = codecSource;
    this.token = token;
    this.ownerNode = ownerNode;
    this.children = children == null ? NO_INDEXES : children;
    this.lowerBounds = lowerBounds == null ? NO_INDEXES : lowerBounds;
    this.variableKey = variableKey;
  }

  public int kind() {
    return kind;
  }

  public boolean hasCodec() {
    return codecToken >= 0;
  }

  public int codecToken() {
    return codecToken;
  }

  public int codecOperation() {
    return codecOperation;
  }

  public String codecSource() {
    return codecSource;
  }

  /** Returns the primitive kind, raw-type token, or array component node for this node kind. */
  public int token() {
    return token;
  }

  public int ownerNode() {
    return ownerNode;
  }

  public int childCount() {
    return children.length;
  }

  public int child(int index) {
    return children[index];
  }

  public int lowerBoundCount() {
    return lowerBounds.length;
  }

  public int lowerBound(int index) {
    return lowerBounds[index];
  }

  public int variableKey() {
    return variableKey;
  }
}
