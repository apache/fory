package org.apache.fory.format.encoder;

import org.apache.fory.reflect.TypeRef;

class CompactArrayEncoderBuilder extends ArrayEncoderBuilder {
    public CompactArrayEncoderBuilder(final TypeRef<?> clsType, final TypeRef<?> beanType) {
        super(clsType, beanType);
    }

    @Override
    protected String codecSuffix() {
        return "CompactCodec";
    }
}
