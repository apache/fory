using Fury.Context;

namespace Fury.Serialization;

public abstract class DictionarySerializer<TElement, TDictionary>(TypeRegistration keyRegistration, TypeRegistration valueRegistration) : AbstractSerializer<TDictionary>
where TDictionary : notnull
{

}
