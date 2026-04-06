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

package org.apache.fory.serializer;

import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.resolver.TypeResolver;

/**
 * Serializer used only for copy after registration has been frozen.
 *
 * <p>Read/write keep the same security failure semantics as the normal insecure path, while copy
 * reuses {@link AbstractObjectSerializer}'s field-copy implementation.
 */
public final class CopyOnlyObjectSerializer<T> extends AbstractObjectSerializer<T> {
  public CopyOnlyObjectSerializer(TypeResolver typeResolver, Class<T> type) {
    super(typeResolver, type);
  }

  @Override
  public void write(WriteContext writeContext, T value) {
    throw insecureException();
  }

  @Override
  public T read(ReadContext readContext) {
    throw insecureException();
  }

  private InsecureException insecureException() {
    String message =
        String.format(
            "%s is not registered, please check whether it's the type you want to serialize or "
                + "a **vulnerability**. If safe, you should invoke `Fory#register` to register "
                + "class,  which will have better performance by skipping classname "
                + "serialization. If your env is 100%% secure, you can also avoid this "
                + "exception by disabling class registration check using "
                + "`ForyBuilder#requireClassRegistration(false)`",
            type);
    return new InsecureException(message);
  }
}
