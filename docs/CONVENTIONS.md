# Code Conventions

These rules apply to all code in this repository.

## Readability

- Prefer clear names and small methods over explanation.
- Keep each class focused on one responsibility.
- Follow the module and layer rules in the [LLD](LLD.md).

## Comments

- Write comments in plain English, so a new reader can see what each part is and why it exists.
- Add them to Dockerfiles, scripts, templates, config and code wherever they help.
- Put a comment above the function, section or setting it describes.
- Never add a comment at the end of a code line.
- Keep comments short, and don't comment every line or repeat what the code plainly says.

## Configuration

- Never hardcode a tunable value. Put engineering-owned settings in a `.properties` file.
- Use one file per topic, such as database, Kafka or Redis. See the LLD for the list.
- Add a short comment above each setting or group, saying what it controls.

## Formatting

Spotless applies Google Java Format.

```bash
./gradlew spotlessApply
```

## CI

Every push to `main` and every pull request runs `./gradlew build`, which checks formatting, compiles the code and runs all tests. A failing run must be fixed before merging.

JaCoCo fails the build if `documentengine` drops below 90% instruction or branch coverage. New modules join this check as they are built. The report is at `build/reports/jacoco/test/html/index.html`.

## Documentation

Any change to behaviour, design, configuration or local setup updates the matching file in `docs/` or the README in the same commit.
