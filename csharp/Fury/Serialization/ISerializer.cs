using System;
using System.Threading;
using System.Threading.Tasks;
using Fury.Context;

namespace Fury.Serialization;

// This interface is used to support polymorphism.
public interface ISerializer : IDisposable
{
    bool Serialize(SerializationWriter writer, object value);

    void Reset();
}

// It is very common that the data is not all available at once, so we need to read it asynchronously.
public interface IDeserializer : IDisposable
{
    /// <summary>
    /// The object which is being deserialized.
    /// </summary>
    /// <remarks>
    /// <para>
    /// This property is used for circular dependency scenarios.
    /// </para>
    /// <para>
    /// It will be called when circular dependency is detected.
    /// For each deserialization process, this property can be called at most once.
    /// </para>
    /// <para>
    /// The returned object should be the same as the returned value
    /// of <see cref="Deserialize"/> or <see cref="DeserializeAsync"/>.
    /// </para>
    /// </remarks>
    public object ReferenceableObject { get; }

    // /// <summary>
    // /// Try to create an instance of the object which will be deserialized.
    // /// </summary>
    // /// <param name="reader">
    // ///     The reader which contains the state of the deserialization process.
    // /// </param>
    // /// <returns>
    // /// <see langword="true"/> if the instance is created completely; otherwise, <see langword="false"/>.
    // /// </returns>
    // /// <seealso cref="CreateInstanceAsync"/>
    // ReadValueResult<object> CreateInstance(DeserializationReader reader);

    // /// <summary>
    // /// Try to read the serialized data and populate the given object.
    // /// </summary>
    // /// <param name="reader">
    // ///     The reader which contains the state of the deserialization process.
    // /// </param>
    // /// <returns>
    // /// <see langword="true"/> if the object is deserialized completely; otherwise, <see langword="false"/>.
    // /// </returns>
    // /// <seealso cref="FillInstanceAsync"/>
    // bool FillInstance(DeserializationReader reader);

    ReadValueResult<object> Deserialize(DeserializationReader reader);

    // /// <summary>
    // /// Create an instance of the object which will be deserialized.
    // /// </summary>
    // /// <param name="reader">
    // ///     The reader which contains the state of the deserialization process.
    // /// </param>
    // /// <param name="cancellationToken">
    // ///     The token to monitor for cancellation requests.
    // /// </param>
    // /// <returns>
    // /// An instance of the object which is not deserialized yet.
    // /// </returns>
    // /// <remarks>
    // /// <para>
    // /// This method is used to solve the circular reference problem.
    // /// When deserializing an object which may be referenced by itself or its child objects,
    // /// we need to create an instance before reading its fields.
    // /// So that we can reference it before it is fully deserialized.
    // /// </para>
    // /// <para>
    // /// You can read some necessary data from the reader to create the instance, e.g. the length of an array.
    // /// </para>
    // /// <para>
    // /// If the object certainly does not have circular references, you can return a fully deserialized object
    // /// and keep the <see cref="FillInstanceAsync"/> method empty.<br/>
    // /// Be careful that the default implementation of <see cref="IDeserializer{TTarget}.DeserializeAsync"/>
    // /// in <see cref="AbstractDeserializer{TTarget}"/> use this method to create an instance.<br/>
    // /// If you want to do all the deserialization here, it is recommended to override
    // /// <see cref="IDeserializer{TTarget}.DeserializeAsync"/> and call it in this method.
    // /// </para>
    // /// </remarks>
    // /// <seealso cref="CreateInstance"/>
    // ValueTask<ReadValueResult<object>> CreateInstanceAsync(DeserializationReader reader,
    //     CancellationToken cancellationToken = default);

    // /// <summary>
    // /// Read the serialized data and populate the given object.
    // /// </summary>
    // /// <param name="reader">
    // ///     The reader which contains the state of the deserialization process.
    // /// </param>
    // /// <param name="cancellationToken">
    // ///     The token to monitor for cancellation requests.
    // /// </param>
    // /// <seealso cref="FillInstance"/>
    // ValueTask<bool> FillInstanceAsync(DeserializationReader reader,
    //     CancellationToken cancellationToken = default);

    ValueTask<ReadValueResult<object>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    );

    void Reset();
}

public interface ISerializer<TTarget> : ISerializer
{
    bool Serialize(SerializationWriter writer, in TTarget value);
}

public interface IDeserializer<TTarget> : IDeserializer
{
    /// <summary>
    /// Read the serialized data and create an instance of the object.
    /// </summary>
    /// <param name="reader">
    /// The reader which contains the state of the deserialization process.
    /// </param>
    /// <returns>
    /// The result of the read operation.
    /// If <see cref="ReadValueResult{TValue}.IsSuccess"/> is <see langword="true"/>,
    /// the deserialized value will be in <see cref="ReadValueResult{TValue}.Value"/>.
    /// Otherwise, the default value of <typeparamref name="TTarget"/> will
    /// be in <see cref="ReadValueResult{TValue}.Value"/>.
    /// </returns>
    /// <remarks>
    /// This method is designed to avoid boxing and unboxing.
    /// </remarks>
    /// <seealso cref="Deserialize"/>
    new ReadValueResult<TTarget> Deserialize(DeserializationReader reader);

    /// <summary>
    /// Read the serialized data and create an instance of the object.
    /// </summary>
    /// <param name="reader">
    /// The reader which contains the state of the deserialization process.
    /// </param>
    /// <param name="cancellationToken">
    /// The token to monitor for cancellation requests.
    /// </param>
    /// <returns>
    /// The result of the read operation.
    /// If <see cref="ReadValueResult{TValue}.IsSuccess"/> is <see langword="true"/>,
    /// the deserialized value will be in <see cref="ReadValueResult{TValue}.Value"/>.
    /// Otherwise, the default value of <typeparamref name="TTarget"/> will
    /// be in <see cref="ReadValueResult{TValue}.Value"/>.
    /// </returns>
    /// <remarks>
    /// This method is designed to avoid boxing and unboxing.
    /// </remarks>
    /// <seealso cref="Deserialize"/>
    new ValueTask<ReadValueResult<TTarget>> DeserializeAsync(
        DeserializationReader reader,
        CancellationToken cancellationToken = default
    );
}
