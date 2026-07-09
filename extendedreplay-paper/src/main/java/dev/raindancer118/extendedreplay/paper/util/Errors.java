package dev.raindancer118.extendedreplay.paper.util;

import java.util.concurrent.CompletionException;

/** Human-readable one-liners for exceptions shown to players. */
public final class Errors {

    private Errors() {
    }

    /**
     * Unwraps async wrappers and never returns "null": exceptions without a message
     * (NPEs etc.) are described by their type instead.
     */
    public static String describe(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause.getCause() != null && cause.getMessage() == null)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null && !message.isBlank()
                ? message
                : cause.getClass().getSimpleName() + " (Details im Server-Log)";
    }
}
