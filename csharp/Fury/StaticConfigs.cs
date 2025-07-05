using System.Diagnostics.Contracts;
using System.Runtime.CompilerServices;

namespace Fury;

internal static class StaticConfigs
{
    private const int StackAllocLimit = 256;
    public const int CharStackAllocLimit = StackAllocLimit / sizeof(char);

    public const int BuiltInListDefaultCapacity = 16;
    public const int BuiltInBufferDefaultCapacity = 256;
}
