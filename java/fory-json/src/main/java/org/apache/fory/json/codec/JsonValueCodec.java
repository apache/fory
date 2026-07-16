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

package org.apache.fory.json.codec;

/**
 * A streaming codec for one complete JSON value of type {@code T}.
 *
 * <p>The codec reads from and writes to Fory JSON's concrete readers and writers directly; it is
 * not a JSON abstract syntax tree (AST) codec. Each operation owns the complete value, including
 * its JSON {@code null} representation. There is no secondary non-null codec protocol: field
 * omission belongs to object-field handling, while primitive null rejection belongs to the
 * primitive codec or primitive field owner.
 *
 * <p>A value codec never handles map keys. Map keys are encoded and decoded by {@link MapKeyCodec}.
 *
 * <p>Built-in and user-registered codecs implement this complete interface because one codec owns
 * the Java type's semantics for every representation. Generated object specializations implement
 * only the narrow capability they accelerate and are installed independently in the corresponding
 * {@link org.apache.fory.json.resolver.JsonTypeInfo} slot.
 *
 * <p>Application codecs whose semantics are representation-neutral may extend {@link
 * AbstractJsonValueCodec}. Performance-sensitive codecs should implement this interface directly.
 */
public interface JsonValueCodec<T>
    extends StringWriterCodec<T>,
        Utf8WriterCodec<T>,
        Latin1ReaderCodec<T>,
        Utf16ReaderCodec<T>,
        Utf8ReaderCodec<T> {}
