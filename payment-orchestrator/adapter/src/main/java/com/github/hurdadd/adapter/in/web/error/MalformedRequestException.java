package com.github.hurdadd.adapter.in.web.error;

/**
 * The request could not be understood as shaped: a malformed identifier, an
 * unparseable field. Distinct from a domain rule violation on purpose.
 *
 * <p>Both {@code IllegalArgumentException} from the domain and
 * {@code UUID.fromString} look identical to a {@code @ExceptionHandler}. If the
 * web layer reuses that type for its own syntax errors, a client bug stops being
 * a 400 and becomes a 422. So the boundary declares its own type.
 */
public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String message) {
        super(message);
    }

    public MalformedRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
