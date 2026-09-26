# Contributing

Thanks for helping. Pick an issue labelled `good first issue` or `help wanted` and comment
that you're taking it, so two people don't build the same thing.

## Build and test

Requires JDK 17+ and Maven.

```bash
mvn test        # unit tests
mvn package     # builds target/selenium-boot-migrator.jar
```

## Ground rules

- **Read-only by default.** Nothing may modify the user's original project.
- **Deterministic first.** Rules are AST-based; no AI-generated transformations.
- **Honest wording.** Say "estimated"; never promise a fully automatic migration.
- **Ground rules in the framework.** Check a mapping against the Selenium Boot docs before
  adding it. If the target API doesn't exist, flag the pattern for manual review instead.
- Every rule needs a test.

## Pull requests

Branch from `main`, keep the change focused, reference the issue (`Closes #N`), and make sure
`mvn test` passes.
