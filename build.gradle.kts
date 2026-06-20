// Root build script.
//
// All shared configuration (Java 21 toolchain, repositories, test framework, group/version)
// lives in the `mcplatform.java-conventions` convention plugin under buildSrc/.
// Each module applies that convention plugin and then declares ONLY the dependencies it is
// permitted to have per PROGRESS.md section 5 — that strict, per-module declaration IS the
// enforcement of the hexagonal dependency direction.
//
// There is intentionally no `subprojects { }` block here: modules are explicit and self-describing.
