# Releasing to Maven Central

How a maintainer publishes `com.tricoredb:tricoredb-kotlin`.

The namespace `com.tricoredb` is already verified on the
[Central Portal](https://central.sonatype.com) (DNS TXT on `tricoredb.com`), and the
same GPG key signs this library and the Java SDK. So a release here is: build a signed
bundle, upload it, publish it.

## Each release

1. Check the version in `gradle.properties`, `CHANGELOG.md` and `README.md`, and that
   the tree is committed.

2. Run the tests, including the live ones:

   ```bash
   ./gradlew build
   TRICORE_SERVER_BIN=/path/to/tricore-server ./gradlew integrationTest
   ```

3. Build the signed bundle. The key and its passphrase are passed in, never stored in
   the repository:

   ```bash
   ./gradlew centralBundle \
     -Ptricoredb.sign=true \
     -PsigningInMemoryKey="$(gpg --armor --export-secret-keys KEYID)" \
     -PsigningInMemoryKeyPassword='the passphrase'
   ```

   The zip lands in `build/distributions/tricoredb-kotlin-<version>-central-bundle.zip`
   and holds the jar, sources jar, javadoc jar, POM, their `.asc` signatures and the
   checksums, under `com/tricoredb/tricoredb-kotlin/<version>/`.

   Without `-Ptricoredb.sign=true` the task refuses to run: an unsigned bundle is
   rejected by Central anyway, and failing here says why.

4. Upload it at <https://central.sonatype.com/publishing> → **Publish Component**, then
   watch the deployment. When validation passes, click **Publish**; if it fails, the
   portal names the file and the reason.

5. Tag the release:

   ```bash
   git tag -a v0.1.0 -m "tricoredb-kotlin 0.1.0"
   git push origin v0.1.0
   ```

6. The artifact appears at
   <https://central.sonatype.com/artifact/com.tricoredb/tricoredb-kotlin> within
   minutes, and on `repo1.maven.org` and in search a little later.

A version published to Maven Central can never be changed or removed. If something is
wrong, release a new one.

## The signing key

The same key as the Java SDK: RSA 4096, published to `keyserver.ubuntu.com`. Central
verifies every `.asc` against it, so the public key has to be on a keyserver *before*
the upload. To check what the bundle was signed with:

```bash
gpg --verify build/central-bundle/com/tricoredb/tricoredb-kotlin/0.1.0/tricoredb-kotlin-0.1.0.jar.asc
```
