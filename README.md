# junit-subunit

Runs JUnit tests and writes a [subunit v2] stream to stdout, similar to
`python -m subunit.run`.

[subunit v2]: https://github.com/testing-cabal/subunit

## Build

```
mvn package
```

Produces `target/junit-subunit-<version>-standalone.jar`.

## Use

```
java -jar junit-subunit-standalone.jar --scan-classpath=build/test-classes
```

The option names and short forms follow the JUnit Platform ConsoleLauncher:

- Selectors: `--scan-classpath[=PATH]`, `-c/--select-class`,
  `-p/--select-package`, `-m/--select-method`, `-u/--select-uri`,
  `-f/--select-file`, `-d/--select-directory`, `-o/--select-module`,
  `-r/--select-resource`, `-i/--select-iteration`, `--select-unique-id`
  (aka `--uid`), and the generic `--select=PREFIX:VALUE`.
- Filters: `-n/--include-classname`, `-N/--exclude-classname`,
  `--include-package`, `--exclude-package`, `-t/--include-tag`,
  `-T/--exclude-tag`, `-e/--include-engine`, `-E/--exclude-engine`.
- Extras: `--load-list=FILE` and positional test ids, `-l/--list` to
  enumerate without running.

Both `--opt=VALUE` and `--opt VALUE` are accepted. Test ids are the JUnit
Platform unique id, e.g.
`[engine:junit-jupiter]/[class:com.example.Foo]/[method:bar()]`.

## testrepository / inquest

```
[DEFAULT]
test_command=java -cp build/test-classes:libs/junit-subunit-standalone.jar \
  io.github.jelmer.junitsubunit.Main $IDOPTION $LISTOPT
test_id_option=--load-list=$IDFILE
test_list_option=--list
```
