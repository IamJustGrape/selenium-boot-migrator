# Selenium Boot Migration Report

## Summary

- Files found: 3
- Files parsed: 2
- Automatic findings: 1
- Manual findings: 1
- Unparsable files: 1

## Detected technologies

- Build system: Maven

## Recognized technologies

- Selenium
- JUnit 5

## Rules applied per file

### `Auto.java`

- MIG-002 at line 4: WebDriverManager

### `Page.java`

- MIG-003 at line 8: WebDriverWait / ExpectedConditions

## Warnings

- `Page.java:8`: Detected: WebDriverWait / ExpectedConditions. Why not automatic: manual review is required. Suggested approach: Use $(locator) auto-wait or getWait(); review the condition by hand.
- `Broken.java` could not be parsed

## Manual actions

- `Page.java:8`: Use $(locator) auto-wait or getWait(); review the condition by hand.

## Estimated migration confidence

50% estimated migration confidence. This is an estimate, not a guarantee.
