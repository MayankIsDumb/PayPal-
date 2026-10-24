package com.commishguard.model;

/** One immutable line in a commission's evidence trail. */
public record AuditEvent(String at, String actor, String action, String detail) {
}
