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

import org.apache.fory.context.CopyContext;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;

/**
 * Marker interface for {@link Serializer} instances that can be shared across equivalent Fory
 * runtimes and concurrent operations.
 *
 * <p>Implementing this interface is a stronger contract than ordinary thread safety. A shareable
 * serializer must satisfy <b>all</b> of the following conditions:
 *
 * <ul>
 *   <li><b>Thread-safe:</b> the serializer is safe for concurrent use by multiple threads with no
 *       external synchronization.
 *   <li><b>No operation-local mutable state:</b> the serializer does not retain active {@link
 *       WriteContext}, {@link ReadContext}, {@link CopyContext}, or temporary {@link MemoryBuffer}
 *       references across calls. All operation state must be passed through the context parameters.
 *   <li><b>No runtime-local mutable state:</b> the serializer does not depend on mutable state tied
 *       to a specific runtime instance, such as mutable resolver state from a particular
 *       class-loading or registration session.
 *   <li><b>Immutable or concurrency-safe retained fields:</b> any fields retained by the serializer
 *       are immutable, effectively immutable, or otherwise concurrency-safe and independent of the
 *       calling runtime.
 * </ul>
 *
 * <h3>When to implement</h3>
 *
 * <ul>
 *   <li>Serializers that only depend on {@link org.apache.fory.config.Config} (immutable
 *       configuration) are typically shareable.
 *   <li>Serializers for immutable value types (primitives, time types, enums, UUIDs, etc.) are
 *       almost always shareable.
 *   <li>Serializers whose fields are derived entirely from the target type and configuration at
 *       construction time are shareable.
 * </ul>
 *
 * <h3>When NOT to implement</h3>
 *
 * <ul>
 *   <li>Serializers that retain a {@link org.apache.fory.resolver.TypeResolver} reference are
 *       usually <b>not</b> shareable because the resolver carries runtime-local mutable state.
 *   <li>Serializers that cache intermediate results per-operation (scratch buffers, temporary
 *       collections, etc.) in instance fields are not shareable.
 *   <li>When in doubt, do not implement this interface. The default assumption is that a serializer
 *       is runtime-local.
 * </ul>
 *
 * <h3>Usage</h3>
 *
 * <p>Implement this interface on concrete leaf serializer classes only. Do not implement it on
 * intermediate abstract base classes to avoid accidentally widening sharing for subclasses that
 * carry mutable state.
 *
 * <pre>
 * public final class FooSerializer extends Serializer&lt;Foo&gt; implements Shareable {
 *   public FooSerializer(Config config) {
 *     super(config, Foo.class);
 *   }
 *
 *   public void write(WriteContext ctx, Foo value) { ... }
 *
 *   public Foo read(ReadContext ctx) { ... }
 * }
 * </pre>
 *
 * <p>Consumers can check shareability via {@code serializer instanceof Shareable}.
 *
 * @see Serializer
 */
public interface Shareable {}
