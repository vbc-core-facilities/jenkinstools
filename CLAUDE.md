# jenkinstools

Jenkins shared library: every `vars/<name>.groovy` is a pipeline step (`deployService`,
`deployStandardProteomicsService`, notification steps, ...). Read `README.md` first.

## Groovy style (steps and Jenkinsfiles in all of the user's repos)

- Descriptive names that say what a thing is (`buildDockerImageParameters`, not `image`).
- Build a map/object in a single construction, never "create a base object, then mutate it". Constructing it ahead of
  the call is fine. Conditional keys: a literal with possibly-null values, then
  `findAll { name, value -> value != null }`.
- Never use implicit `it`; always name closure parameters.
- No dense inline chains like `[...].findAll { ... }.collect { ... }` in one expression; assign each step to a named
  variable.
- Lists: one element per line when there are more than 2 members, or when the set is open-ended even with 2 (e.g.
  branches); a bounded pair may stay on one line.
- Jenkinsfiles: all configuration as variables at the top; the step call only wires variables in. Layout: short
  comment with a jenkinstools link, then the standard config definitions, then the library line, then
  customizations beyond the standard, then the deploy call.
