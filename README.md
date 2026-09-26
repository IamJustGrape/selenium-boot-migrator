# Selenium Boot Migrator

Already have Selenium tests? Don't rewrite them. This tool scans an existing Selenium Java
project and reports which parts map onto [Selenium Boot](https://github.com/seleniumboot/selenium-boot)
and which need a human.

**Status: early.** `analyze` is read-only and never changes your project. `migrate` is planned
(see the [milestones](https://github.com/seleniumboot/selenium-boot-migrator/milestones)).
It runs locally; your source never leaves your machine.

## Use

```bash
mvn package
java -jar target/selenium-boot-migrator.jar analyze ./my-selenium-project
```

Output is a count per rule, what maps cleanly vs. needs review, and an *estimated* confidence.
The estimate is a guide, not a guarantee.

## Rules

Each rule follows the [Selenium + TestNG migration guide](https://docs.seleniumboot.com).

| ID | Detects | Suggested change |
|---|---|---|
| MIG-001 | `ThreadLocal<WebDriver>` | Delete the factory; extend `BaseTest` |
| MIG-002 | `WebDriverManager` | Delete; Selenium Manager handles drivers |
| MIG-003 | `WebDriverWait`, `ExpectedConditions` | Auto-waiting locators / `getWait()` (manual review) |
| MIG-004 | `IRetryAnalyzer`, `IAnnotationTransformer` | `retry:` config / `@Retryable` |
| MIG-005 | Screenshot `ITestListener` | Delete; captured automatically |
| MIG-010 | Class with a `WebDriver` constructor parameter | Page-object candidate; review against `BasePage` |
| MIG-011 | `@FindBy` fields | Manual review; Selenium Boot documents `By` locator fields |
| MIG-012 | `PageFactory.initElements(...)` | Manual review; page initialization mapping is not documented |
| MIG-014 | `Thread.sleep` | Manual review |
| MIG-015 | Custom `*DriverManager` / `*DriverFactory` | Manual review |
| MIG-016 | `implicitlyWait` | Remove; manual review |

The Selenium Boot [getting-started guide](https://docs.seleniumboot.com/docs/getting-started) documents page objects extending `BasePage`, with a `WebDriver` constructor and `By` locator fields. It does not document `@FindBy` or `PageFactory.initElements`; the analyzer therefore reports their counts for review rather than treating them as a direct `BasePage` mapping.

## Adding a rule

1. Add detection in `Analyzer.scan(...)` and emit a `Finding` with a new `MIG-` ID.
2. Add a test in `AnalyzerTest` with a small inline source string.
3. Add a row to the table above.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Good first tasks are labelled
[`good first issue`](https://github.com/seleniumboot/selenium-boot-migrator/labels/good%20first%20issue).
