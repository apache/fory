using System;

namespace Fury.Context;

[Flags]
internal enum ObjectMetaOption
{
    ReferenceMeta = 1,
    TypeMeta = 2,
}
