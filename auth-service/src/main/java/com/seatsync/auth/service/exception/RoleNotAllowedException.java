package com.seatsync.auth.service.exception;

public class RoleNotAllowedException extends RuntimeException {

    public RoleNotAllowedException(String role) {
        super("Role " + role + " cannot be self-registered");
    }
}
