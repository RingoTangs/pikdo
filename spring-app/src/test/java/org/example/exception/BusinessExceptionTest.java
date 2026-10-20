package org.example.exception;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {
    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> BusinessException.of(null));
        assertThrows(NullPointerException.class, () -> BusinessException.of(null, "value"));
        assertThrows(
                NullPointerException.class,
                () -> BusinessException.of(CommonError.NOT_FOUND, (Object[]) null));
    }

    @Test
    void retainsDefinitionAndCopiesArguments() {
        Object[] args = {"alice"};
        BusinessException exception = BusinessException.of(CommonError.NOT_FOUND, args);
        args[0] = "bob";
        Object[] returned = exception.getArgs();
        returned[0] = "carol";
        assertEquals("alice", exception.getArgs()[0]);
        assertSame(CommonError.NOT_FOUND, exception.getError());
        assertEquals(CommonError.NOT_FOUND.getCode(), exception.getCode());
        assertEquals(CommonError.NOT_FOUND.getStatus(), exception.getStatus());
        assertEquals("error.common.not_found", exception.getMessage());
        assertEquals(0, BusinessException.of(CommonError.NOT_FOUND).getArgs().length);
        assertEquals("alice", BusinessException.of(CommonError.NOT_FOUND, "alice").getArgs()[0]);
    }
}
