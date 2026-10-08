package com.moneyflow.service;

/** An external API failed, timed out or returned data that could not be used. */
public class UpstreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UpstreamException(String message) {
        super(message);
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}
