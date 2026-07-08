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

Options: `--scan-classpath`, `--select-class`, `--select-package`,
`--select-method`, `--select-unique-id`, `--load-list=FILE`, positional test
ids, and `--list` to enumerate without running. Test ids are the JUnit
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
