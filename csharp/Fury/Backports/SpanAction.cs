#if !NET5_0_OR_GREATER && !NETSTANDARD2_1
// ReSharper disable once CheckNamespace
namespace System.Buffers;
internal delegate void SpanAction<T, in TArg>(Span<T> span, TArg arg);
#endif
