# Releasing

Artifacts are published to Maven Central via the [Central Portal].

[Central Portal]: https://central.sonatype.com/

## Cutting a release

1. Set the version and commit:

   ```
   mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   git commit -am "Release 0.1.0"
   git tag -s v0.1.0 -m "Release 0.1.0"
   ```

2. Deploy to Central:

   ```
   mvn -P release clean deploy
   ```

   This signs the artifacts with GPG and uploads them via the
   `central-publishing-maven-plugin`. With `autoPublish=true` the
   deployment is promoted automatically once validation passes.

3. Bump to the next snapshot and push:

   ```
   mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "Post-release version bump"
   git push && git push --tags
   ```
