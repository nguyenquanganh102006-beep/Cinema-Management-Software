package com.cinema.cms.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

final class ConstraintViolations {
    private ConstraintViolations() {}

    static boolean violates(DataIntegrityViolationException e, String... constraintNames) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null && matches(cve.getConstraintName(), constraintNames)) return true;
        }
        String message = e.getMostSpecificCause().getMessage();
        return message != null && matches(message, constraintNames);
    }

    private static boolean matches(String text, String[] names) {
        String lower = text.toLowerCase();
        for (String name : names) if (lower.contains(name.toLowerCase())) return true;
        return false;
    }
}