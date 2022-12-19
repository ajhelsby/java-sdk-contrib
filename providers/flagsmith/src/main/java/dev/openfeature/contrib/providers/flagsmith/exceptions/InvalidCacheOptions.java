package dev.openfeature.contrib.providers.flagsmith.exceptions;

import dev.openfeature.sdk.ErrorCode;
import lombok.Getter;

/**
 * InvalidOptions is the super Exception used when we have a configuration exception.
 */
@Getter
public class InvalidCacheOptions extends FlagsmithProviderException {
    private static final long serialVersionUID = 1L;
    private final ErrorCode errorCode = ErrorCode.GENERAL;

    public InvalidCacheOptions(String message) {
        super(message);
    }

}
