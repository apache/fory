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

package org.apache.fory.resolver;

import org.apache.fory.annotation.Internal;
import org.apache.fory.meta.EncodedMetaString;
import org.apache.fory.meta.MetaStringDecoder;

@Internal
public final class MetaStringRef {
  static final short DEFAULT_DYNAMIC_WRITE_STRING_ID = -1;
  final EncodedMetaString encoded;
  short dynamicWriteStringId = DEFAULT_DYNAMIC_WRITE_STRING_ID;

  MetaStringRef(EncodedMetaString encoded) {
    this.encoded = encoded;
  }

  public String decode(char specialChar1, char specialChar2) {
    return encoded.decode(specialChar1, specialChar2);
  }

  public String decode(MetaStringDecoder decoder) {
    return encoded.decode(decoder);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MetaStringRef)) {
      return false;
    }
    MetaStringRef that = (MetaStringRef) o;
    return encoded.equals(that.encoded);
  }

  @Override
  public int hashCode() {
    return encoded.hashCode();
  }

  @Override
  public String toString() {
    return "MetaStringRef{"
        + "encoded="
        + encoded
        + ", dynamicWriteStringId="
        + dynamicWriteStringId
        + '}';
  }
}
