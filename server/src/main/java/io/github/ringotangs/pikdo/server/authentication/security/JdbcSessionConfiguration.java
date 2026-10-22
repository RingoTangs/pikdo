package io.github.ringotangs.pikdo.server.authentication.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

@Configuration(proxyBeanMethods = false)
public class JdbcSessionConfiguration {
    @Bean
    @Qualifier("springSessionConversionService")
    @ConditionalOnMissingBean(name = "springSessionConversionService")
    ConversionService springSessionConversionService() {
        var conversion = new GenericConversionService();
        conversion.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversion.addConverter(
                byte[].class,
                Object.class,
                new DeserializingConverter(new SessionAttributeDeserializer()));
        return conversion;
    }
}
