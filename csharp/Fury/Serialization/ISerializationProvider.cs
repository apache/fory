using System;
using Fury.Context;
using Fury.Meta;

namespace Fury.Serialization;

// For specific types, such as generics, it may be difficult to determine the exact type
// at the time of writing the code, so we need a mechanism to allow users to dynamically
// provide type registration information.

public interface ITypeRegistrationProvider
{
    TypeRegistration RegisterType(TypeRegistry registry, Type targetType);

    TypeRegistration GetTypeRegistration(TypeRegistry registry, TypeKind targetTypeKind, Type declaredType);

    TypeRegistration GetTypeRegistration(TypeRegistry registry, string? @namespace, string name);

    TypeRegistration GetTypeRegistration(TypeRegistry registry, int id);
}
