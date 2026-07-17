package com.seatsync.auth.service.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("Email " + email + " is already registered");
    }
}
