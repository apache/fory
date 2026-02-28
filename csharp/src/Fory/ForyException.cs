// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

namespace Apache.Fory;

/// <summary>
/// Base exception type for Apache Fory C# runtime errors.
/// </summary>
public class ForyException : Exception
{
    /// <summary>
    /// Creates a new Fory exception with the provided message.
    /// </summary>
    /// <param name="message">Error description.</param>
    public ForyException(string message) : base(message)
    {
    }
}

/// <summary>
/// Thrown when input data is malformed or inconsistent with expected wire format.
/// </summary>
public sealed class InvalidDataException : ForyException
{
    /// <summary>
    /// Creates a new invalid data exception.
    /// </summary>
    /// <param name="message">Invalid data details.</param>
    public InvalidDataException(string message) : base($"Invalid data: {message}")
    {
    }
}

/// <summary>
/// Thrown when received type metadata does not match expected type metadata.
/// </summary>
public sealed class TypeMismatchException : ForyException
{
    /// <summary>
    /// Creates a new type mismatch exception.
    /// </summary>
    /// <param name="expected">Expected wire type id.</param>
    /// <param name="actual">Actual wire type id.</param>
    public TypeMismatchException(uint expected, uint actual)
        : base($"Type mismatch: expected {expected}, got {actual}")
    {
    }
}

/// <summary>
/// Thrown when an unregistered type is serialized or deserialized in a registered-type path.
/// </summary>
public sealed class TypeNotRegisteredException : ForyException
{
    /// <summary>
    /// Creates a new type-not-registered exception.
    /// </summary>
    /// <param name="message">Unregistered type details.</param>
    public TypeNotRegisteredException(string message) : base($"Type not registered: {message}")
    {
    }
}

/// <summary>
/// Thrown when reference metadata is invalid or inconsistent.
/// </summary>
public sealed class RefException : ForyException
{
    /// <summary>
    /// Creates a new reference exception.
    /// </summary>
    /// <param name="message">Reference error details.</param>
    public RefException(string message) : base($"Reference error: {message}")
    {
    }
}

/// <summary>
/// Thrown when encoding or decoding logic encounters unsupported or invalid state.
/// </summary>
public sealed class EncodingException : ForyException
{
    /// <summary>
    /// Creates a new encoding exception.
    /// </summary>
    /// <param name="message">Encoding error details.</param>
    public EncodingException(string message) : base($"Encoding error: {message}")
    {
    }
}

/// <summary>
/// Thrown when buffer cursor movement would exceed available bounds.
/// </summary>
public sealed class OutOfBoundsException : ForyException
{
    /// <summary>
    /// Creates a new out-of-bounds exception.
    /// </summary>
    /// <param name="cursor">Current cursor offset.</param>
    /// <param name="need">Requested byte count.</param>
    /// <param name="length">Buffer length.</param>
    public OutOfBoundsException(int cursor, int need, int length)
        : base($"Buffer out of bounds: cursor={cursor}, need={need}, length={length}")
    {
    }
}
