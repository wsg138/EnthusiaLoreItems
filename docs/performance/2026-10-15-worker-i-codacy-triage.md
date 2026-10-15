# Worker I — hosted Codacy #154 exact-head annotation review

Evidence: [general CI 38693118362](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38693118362), exact source `5a4a25ce471c89e310458dbe066c2ab4c9e5eb9d`; job emitted **50 annotations on one new disposable CommonJS client**. The first 50 are a capped result, not a count of all possible findings. These 50 were reviewed individually below; no global exclusions or lint disables were used. Earlier passing [exact-head genuine Paper/SQLite 38693117978](https://github.com/wsg138/EnthusiaLoreItems/actions/runs/38693117978) demonstrates valid Node execution and runtime-resolved Mineflayer package paths.

The `waitFile(name)` and `mark(name)` string arguments are not raw external paths: they are rejected unless they match strict enumerated checkpoint patterns before interpolation into an unchanging `/tmp/acc-track-phase2/` fixture root. Cases, suffixes and root are source constants; server input never supplies a path. Both dynamic-path **failure** annotations (#32 and #41, source lines 20 and 31) are assessed as guarded for this isolated CI fixture, **not** a general policy that arbitrary dynamic paths are safe. Synchronous file operations persist checkpoints before process termination; pending async errors reach `main().catch` and write `bot.failed`. Neither Node timers nor modern JS syntax are invalid in the runtime. Container and client are disposable; no production file operations.

| # | Source line | Severity | Reported rule | Individual disposition |
|---:|---:|---|---|---|
| 1 | 3 | warning | Do not import Node.js builtin module "fs" | CommonJS Node test client, not browser/import-only application |
| 2 | 3 | warning | ES2015 block-scoped variables are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 3 | 3 | warning | Require statement not part of import statement. | CommonJS Node test client, not browser/import-only application |
| 4 | 4 | warning | ES2015 block-scoped variables are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 5 | 5 | warning | Can't resolve '/tmp/acc-track-phase2/bot/node_modules/mineflayer' in '/src/acceptance-harness/scripts' | Runtime-only npm fixture path; installed in workflow before execution; exact-head native run passed |
| 6 | 5 | warning | ES2015 block-scoped variables are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 7 | 5 | warning | Require statement not part of import statement. | CommonJS Node test client, not browser/import-only application |
| 8 | 6 | warning | Can't resolve '/tmp/acc-track-phase2/bot/node_modules/vec3' in '/src/acceptance-harness/scripts' | Runtime-only npm fixture path; installed in workflow before execution; exact-head native run passed |
| 9 | 6 | warning | ES2015 block-scoped variables are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 10 | 6 | warning | Require statement not part of import statement. | CommonJS Node test client, not browser/import-only application |
| 11 | 7 | warning | "Promise" is not defined. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 12 | 7 | warning | ES2015 'Promise' class is forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 13 | 7 | warning | ES2015 arrow function expressions are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 14 | 7 | warning | ES2015 block-scoped variables are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 15 | 7 | warning | Missing "ms" parameter type annotation. | Plain JavaScript CJS; TypeScript annotation rule inapplicable |
| 16 | 7 | warning | Promise is not supported in op_mini all | Node timer/promise supported and bounded in disposable host |
| 17 | 7 | warning | Restricted async operation "setTimeout" | Node timer/promise supported and bounded in disposable host |
| 18 | 7 | warning | Return values from promise executor functions cannot be read. | Executor only resolves timer; return value not consumed |
| 19 | 9 | warning | Function must end with a return statement, so that it doesn't return 'undefined' | Void side-effect/test waiter; no value-contract caller |
| 20 | 9 | warning | Missing "line" parameter type annotation. | Plain JavaScript CJS; TypeScript annotation rule inapplicable |
| 21 | 10 | warning | ES2015 block-scoped variables are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 22 | 11 | warning | Detect console.log() with non Literal argument | Local test evidence message; not user input or application telemetry |
| 23 | 12 | warning | Unexpected sync method: 'appendFileSync'. | Intentional synchronous immutable fixture marker/log evidence; bounded local data |
| 24 | 15 | warning | ES2017 async function declarations are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 25 | 15 | warning | Function must end with a return statement, so that it doesn't return 'undefined' | Void side-effect/test waiter; no value-contract caller |
| 26 | 15 | warning | Invalid usage of async-await. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 27 | 15 | warning | Missing "name" parameter type annotation. | Plain JavaScript CJS; TypeScript annotation rule inapplicable |
| 28 | 19 | warning | ES2015 block-scoped variables are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 29 | 20 | warning | Detected that function argument 'name' has entered the fs module. | Reviewed guarded path: exact marker allowlist; fixed /tmp root; only disposable CI writes |
| 30 | 20 | warning | Found existsSync from package "fs" with non literal argument at index 0 | Reviewed guarded path: exact marker allowlist; fixed /tmp root; only disposable CI writes |
| 31 | 20 | warning | Return statement must return an explicit value, so that it doesn't evaluate to 'undefined' | Void side-effect/test waiter; no value-contract caller |
| 32 | 20 | failure | The application dynamically constructs file or path information. | Reviewed guarded path: exact marker allowlist; fixed /tmp root; only disposable CI writes |
| 33 | 20 | warning | Unexpected sync method: 'existsSync'. | Intentional synchronous immutable fixture marker/log evidence; bounded local data |
| 34 | 21 | warning | Unexpected sync method: 'existsSync'. | Intentional synchronous immutable fixture marker/log evidence; bounded local data |
| 35 | 22 | warning | Unexpected 'await' inside a loop. | Finite polling loop (max 2,000 × 100 ms) required to wait for actual host fixture |
| 36 | 24 | warning | Unhandled errors detected in asynchronous function | Explicit `main().catch` marks bot.failed and fails process |
| 37 | 27 | warning | Function must end with a return statement, so that it doesn't return 'undefined' | Void side-effect/test waiter; no value-contract caller |
| 38 | 27 | warning | Missing "name" parameter type annotation. | Plain JavaScript CJS; TypeScript annotation rule inapplicable |
| 39 | 31 | warning | Detected that function argument 'name' has entered the fs module. | Reviewed guarded path: exact marker allowlist; fixed /tmp root; only disposable CI writes |
| 40 | 31 | warning | Found writeFileSync from package "fs" with non literal argument at index 0 | Reviewed guarded path: exact marker allowlist; fixed /tmp root; only disposable CI writes |
| 41 | 31 | failure | The application dynamically constructs file or path information. | Reviewed guarded path: exact marker allowlist; fixed /tmp root; only disposable CI writes |
| 42 | 31 | warning | Unexpected sync method: 'writeFileSync'. | Intentional synchronous immutable fixture marker/log evidence; bounded local data |
| 43 | 35 | warning | ES2017 async function declarations are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 44 | 35 | warning | Function must end with a return statement, so that it doesn't return 'undefined' | Void side-effect/test waiter; no value-contract caller |
| 45 | 35 | warning | Invalid usage of async-await. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 46 | 35 | warning | Missing "bot" parameter type annotation. | Plain JavaScript CJS; TypeScript annotation rule inapplicable |
| 47 | 36 | warning | "Promise" is not defined. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 48 | 36 | warning | ES2015 'Promise' class is forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 49 | 36 | warning | ES2015 arrow function expressions are forbidden. | Valid Node 24 syntax; ECMA/browser-restricted profile mismatch |
| 50 | 36 | warning | Function must end with a return statement, so that it doesn't return 'undefined' | Void side-effect/test waiter; no value-contract caller |

**Review outcome:** none of these first 50 constitutes a demonstrated unsafe production operation. The two dynamic-file-path failures have explicit precondition guards rather than being ignored; they remain candidates for a stronger analyzer-specific flow review. Hosted Codacy itself is **RED**, and no false claim of passing static CI or zero total warnings is made. If a future exact-head run surfaces further uncapped findings, classify each individually. No production migration, permission, main merge, or release follows from this review.
