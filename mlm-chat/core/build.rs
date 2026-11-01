// Builds SQLCipher (decision T18: the on-device database is SQLCipher) from the `sqlcipher-src`
// amalgamation with its bundled libtomcrypt provider, so no system OpenSSL is needed on any
// target (Windows, Android). rusqlite's libsqlite3-sys runs in "sqlcipher, not bundled" mode and
// asks the linker for a library named `sqlcipher`; this script provides it as one static archive.

fn main() {
    let dir = sqlcipher_src::source_dir();
    let ltc_include = dir.join(sqlcipher_src::LIBTOMCRYPT_INCLUDE_DIR);
    println!("cargo:rerun-if-changed=build.rs");

    // libtomcrypt: every file its own translation unit, with LTC_SOURCE, default LTC_* switches.
    let mut ltc = cc::Build::new();
    ltc.include(&ltc_include)
        .define("LTC_SOURCE", None)
        .define("LTC_NO_TEST", None)
        .warnings(false)
        .opt_level(2);
    for source in sqlcipher_src::LIBTOMCRYPT_SOURCES {
        ltc.file(dir.join(source));
    }
    let ltc_objects = ltc.compile_intermediates();

    // SQLCipher amalgamation with the libtomcrypt provider, linked into the same archive.
    let mut sql = cc::Build::new();
    sql.file(dir.join(sqlcipher_src::SOURCE_FILE))
        .include(dir)
        .include(&ltc_include)
        .define("SQLITE_HAS_CODEC", None)
        .define("SQLCIPHER_CRYPTO_LIBTOMCRYPT", None)
        .define("SQLITE_EXTRA_INIT", "sqlcipher_extra_init")
        .define("SQLITE_EXTRA_SHUTDOWN", "sqlcipher_extra_shutdown")
        // Temporary tables and indices stay in memory, never in a temp file on disk.
        .define("SQLITE_TEMP_STORE", "3")
        .define("SQLITE_THREADSAFE", "1")
        .define("SQLITE_OMIT_LOAD_EXTENSION", "1")
        .define("SQLITE_OMIT_DEPRECATED", "1")
        .define("SQLITE_DQS", "0")
        .warnings(false)
        .opt_level(2);
    for object in ltc_objects {
        sql.object(object);
    }
    sql.compile("sqlcipher");

    // libtomcrypt's rng_get_bytes reads the OS generator: CryptGenRandom on Windows.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        println!("cargo:rustc-link-lib=advapi32");
    }
}
