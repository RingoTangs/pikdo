package io.github.ringotangs.pikdo.server.authentication.security;

import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.core.serializer.Deserializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectStreamClass;

final class SessionAttributeDeserializer implements Deserializer<Object> {
    private static final String LEGACY_PRINCIPAL =
            "io.github.ringotangs.pikdo.server.identity.security.UserPrincipal";

    @Override
    public Object deserialize(InputStream input) throws IOException {
        try (var stream = new PrincipalInputStream(input)) {
            return stream.readObject();
        } catch (ClassNotFoundException failure) {
            throw new IOException("Unable to deserialize Session attribute", failure);
        }
    }

    private static final class PrincipalInputStream extends ConfigurableObjectInputStream {
        PrincipalInputStream(InputStream input) throws IOException {
            super(input, UserPrincipal.class.getClassLoader());
        }

        @Override
        protected ObjectStreamClass readClassDescriptor()
                throws IOException, ClassNotFoundException {
            var descriptor = super.readClassDescriptor();
            if (!LEGACY_PRINCIPAL.equals(descriptor.getName())) return descriptor;
            var fields = descriptor.getFields();
            if (descriptor.getSerialVersionUID() != 0L
                    || fields.length != 2
                    || !"authenticatedAt".equals(fields[0].getName())
                    || fields[0].getTypeCode() != 'J'
                    || !"userId".equals(fields[1].getName())
                    || !"Ljava/lang/String;".equals(fields[1].getTypeString()))
                throw new InvalidClassException("Unsupported legacy Session principal schema");
            // 只替换这一种历史载体的描述符；其他对象仍走标准反序列化。
            return ObjectStreamClass.lookup(UserPrincipal.class);
        }
    }
}
