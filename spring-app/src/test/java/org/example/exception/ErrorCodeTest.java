package org.example.exception;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

class ErrorCodeTest {
    @Test
    void commonCodesHaveModulePrefix() {
        for (CommonError error : CommonError.values()) {
            assertTrue(error.getCode().startsWith("COMMON_"), error.getCode());
        }
    }

    @Test
    void requestCodesHaveModulePrefix() {
        for (RequestError error : RequestError.values()) {
            assertTrue(error.getCode().startsWith("REQUEST_"), error.getCode());
        }
    }

    @Test
    void codesAreUniqueAcrossCommonAndRequestErrors() {
        Set<String> codes = new HashSet<>();
        Stream.concat(Stream.of(CommonError.values()), Stream.of(RequestError.values()))
                .forEach(error -> assertTrue(codes.add(error.getCode()), error.getCode()));
    }
}
