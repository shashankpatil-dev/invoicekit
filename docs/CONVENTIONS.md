# Code Conventions

These rules apply to all code in this repository.

## Readability

- Prefer clear names and small methods over explanation.
- Keep each class focused on one responsibility.
- Follow the module and layer rules in the [LLD](LLD.md).

## Comments

- Never write inline comments.
- Add a comment above a function only when it needs one, such as for a non-obvious rule or trade-off.
- Describe why the code exists, not what each line does.
- Write class or function descriptions only where the name and signature aren't enough.

## Formatting

Spotless applies Google Java Format.

```bash
./gradlew spotlessApply
```

## CI

Every push to `main` and every pull request runs `./gradlew build`, which checks formatting, compiles the code and runs all tests. A failing run must be fixed before merging.

## Documentation

Any change to behaviour, design, configuration or local setup updates the matching file in `docs/` or the README in the same commit.
