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

import org.apache.fory.exception.ForyException;

/**
 * Root runtime exception for JSON syntax, mapping, codec, generated-code, and resource-limit
 * failures reported by Fory JSON.
 *
 * <p>An unchecked exception thrown by application code, such as a custom codec, while a {@code
 * fromJson} root operation reads its value is reported as this exception with the original
 * exception as its cause. A {@link ForyException} or an {@link Error} propagates unchanged. Write
 * operations and the operations passed to {@link ForyJson#readUtf8} and {@link ForyJson#writeUtf8}
 * may propagate application exceptions with their own type.
 */
public class ForyJsonException extends ForyException {
  public ForyJsonException(String message) {
    super(message);
  }

  public ForyJsonException(String message, Throwable cause) {
    super(message, cause);
  }
}
