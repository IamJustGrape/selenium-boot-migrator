package com.seleniumboot.migrator;

/** One detected pattern. AUTO = maps cleanly onto Selenium Boot; MANUAL = needs a human. */
public record Finding(String ruleId, Status status, String file, int line, String detected, String advice) {

    public enum Status { AUTO, MANUAL }
}
