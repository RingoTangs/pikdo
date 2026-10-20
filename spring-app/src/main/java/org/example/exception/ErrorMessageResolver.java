package org.example.exception;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ErrorMessageResolver {
    private final MessageSource messageSource;

    public ErrorMessageResolver(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public ErrorResponse resolve(ErrorCode error, Locale locale, Object... args) {
        String message;
        try {
            message = messageSource.getMessage(error.getMessageKey(), args, locale);
        } catch (NoSuchMessageException | IllegalArgumentException exception) {
            message = safeMessage(locale);
        }
        return new ErrorResponse(error.getCode(), message);
    }

    private String safeMessage(Locale locale) {
        try {
            return messageSource.getMessage(
                    CommonError.INTERNAL_SERVER_ERROR.getMessageKey(), null, locale);
        } catch (NoSuchMessageException | IllegalArgumentException exception) {
            return "Internal server error";
        }
    }
}
