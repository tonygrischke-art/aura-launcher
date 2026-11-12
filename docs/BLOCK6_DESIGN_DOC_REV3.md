# Block 6 Design Doc — llama.cpp Android Integration (Rev 3)

**Status**: DESIGN ONLY — do not implement until gate passes  
**Base commit**: `llama.cpp@ac8e1694d837b11ed0aab944a6a2eee34b9c4d80` (tag `b11968`)  
**Evidence date**: 2026-10-31 (Phase 1 raw terminal output attached below)  
**Repo**: aura-launcher-hermes (Aura launcher, hermes-aetheria fork)

---

## 1. Verified API Signatures (Phase 1 Evidence)

### llama.h (llama.cpp/include/llama.h)

| Line | Symbol | Status |
|------|--------|--------|
| 593 | `llama_get_memory(const struct llama_context *)` → `llama_memory_t` | VERIFIED |
| 596 | `llama_model_get_vocab(const struct llama_model *)` → `const struct llama_vocab *` | VERIFIED |
| 755 | `llama_memory_clear(llama_memory_t mem, bool data)` | VERIFIED |
| 1211 | `llama_vocab_is_eog(const struct llama_vocab *, llama_token)` → `bool` | VERIFIED |
| 1242 | `llama_token_is_eog` — **DEPRECATED**, use `llama_vocab_is_eog` | VERIFIED |
| 1278 | `llama_tokenize(const struct llama_vocab *, const char *, int32_t, llama_token *, int32_t, bool, bool)` → `int32_t` | VERIFIED |
| 1292 | `llama_token_to_piece(const struct llama_vocab *, llama_token, char *, int32_t, bool, bool)` → `int32_t` | VERIFIED |
| 965 | `llama_batch_get_one(llama_token *, int32_t)` → `struct llama_batch` | VERIFIED |
| 976 | `llama_batch_init(int32_t, int32_t, int32_t)` → `struct llama_batch` | VERIFIED |
| 982 | `llama_batch_free(struct llama_batch)` | VERIFIED |

### common/*.h

| File | Symbol | Status |
|------|--------|--------|
| sampling.h:40-42 | `common_sampler_init(const struct llama_model *, struct common_params_sampling &)` → `struct common_sampler *` | VERIFIED |
| sampling.h:47 | `common_sampler_accept(struct common_sampler *, llama_token, bool)` | VERIFIED |
| sampling.h:68 | `common_sampler_sample(struct common_sampler *, struct llama_context *, int, bool)` → `llama_token` | VERIFIED |
| common/*.h | `common_batch_add`, `common_batch_clear` | **UNVERIFIED** (not found — use `llama_batch_*` C API instead) |

### Build files

- `lib/build.gradle.kts` — VERIFIED (full content captured)
- `lib/src/main/cpp/CMakeLists.txt` — VERIFIED (full content captured)
- `mtmd` references in `examples/llama.android` — **UNVERIFIED** (zero matches)

---

## 2. Required Fixes (Phase 2)

### 2.1 Gradle (`lib/build.gradle.kts`)

**Changes:**
1. In `android {}` block: add `packaging { jniLibs { useLegacyPackaging = true } }`
2. In `defaultConfig {}`:
   - Keep `ndk.abiFilters = ["arm64-v8a", "x86_64"]`
   - Consolidate all `externalNativeBuild.cmake.arguments` into one block
3. **Delete duplicate** `externalNativeBuild { cmake { ... } }` block (keep only one in `android {}`)
4. Ensure `-DGGML_LLAMAFILE=OFF` is present (already verified)
5. **Fix KleidiAI comment**: `GGML_CPU_KLEIDIAI` is set per-ABI in `CMakeLists.txt`, NOT by `GGML_CPU_ALL_VARIANTS`. Update comment accordingly.

### 2.2 CMake (`lib/src/main/cpp/CMakeLists.txt`)

**Changes:**
1. Comment out `add_subdirectory(${LLAMA_SRC} build-llama)` — submodule not used
2. Compute `LLAMA_SRC` from `CMAKE_CURRENT_LIST_DIR`:
   ```cmake
   set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../)
   message(STATUS "LLAMA_SRC resolved to: ${LLAMA_SRC}")
   ```
3. **Delete** `target_compile_definitions(...)` block (GGML_* defines set via CMake cache)
4. **Delete** `set(CMAKE_C_FLAGS ...)` and `set(CMAKE_CXX_FLAGS ...)` lines (FORCE cache strings not needed)

### 2.3 JNI (`ai_chat.cpp` — inferred from standard llama.android pattern)

**Changes — use exact verified signatures:**
1. **Delete** any `GetIntField` call for vocab size — use:
   ```cpp
   const struct llama_vocab *vocab = llama_model_get_vocab(model);
   int32_t vocab_size = llama_vocab_n_tokens(vocab);  // or llama_vocab_is_eog for EOG check
   ```
2. Use `llama_batch_*` C API (not `common_batch_*` — UNVERIFIED):
   - `llama_batch_init(n_tokens, n_seq_max, embd)` to allocate
   - `llama_batch_get_one(tokens, n_tokens)` for single-sequence batches
   - `llama_batch_free(batch)` to free
3. Call `llama_memory_clear(llama_get_memory(ctx), true)` at **start of each generate()**
4. Chunk prompt decode by `n_batch` (loop: `llama_decode(ctx, batch)` per batch)
5. Call `common_sampler_accept(sampler, token, true)` **per generated token**
6. Call `common_sampler_sample(sampler, ctx, idx, false)` to sample next token
7. Check abort flag **inside prompt loop** (not just generation loop)
8. **Drop** unused globals: `g_batch`, `token_buffer`

### 2.4 UTF-8 Decoding (JNI → Kotlin boundary)

**Changes:**
1. Replace boundary logic with correct **lead-byte / expected-length** check:
   - 0x00–0x7F: 1 byte
   - 0xC2–0xDF: 2 bytes
   - 0xE0–0xEF: 3 bytes
   - 0xF0–0xF4: 4 bytes
2. **Never flush incomplete bytes** — buffer until full codepoint or error
3. Return `jbyteArray` from JNI (not `jstring`)
4. Kotlin side: `String(bytes, Charsets.UTF_8)` instead of `NewStringUTF`

### 2.5 Kotlin (`AiChat.kt` / wrapper — inferred)

**Changes:**
1. **Remove default args** from `override` functions (Kotlin forbids)
2. **Dedupe** `state` and `DEFAULT_*` constants — single source of truth
3. Use `callbackFlow { trySend(...); awaitClose { nativeAbort() } }`
4. `return@withContext` at coroutine boundaries
5. Mark JNI externals `@JvmStatic` **or** move into companion object
6. Pick destination filename from picked document's `displayName`
7. `launch(arrayOf("*/*"))` for file picker intent

### 2.6 M1 Steps — Renumbered

| Step | Description | Target |
|------|-------------|--------|
| M1.1 | Gradle + CMake cleanup | Build passes |
| M1.2 | JNI vocab/batch/sampler/memory fixes | No crashes, tokens generated |
| M1.3 | UTF-8 byte-array return + Kotlin decode | No mojibake on multilingual output |
| M1.4 | Kotlin callbackFlow + abort wiring | Clean cancellation, no leaks |
| M1.5 | File picker + intent fixes | Model load from SAF URI works |

**TARGETS (UNVERIFIED — require on-device benchmark after implementation):**
- APK size **< 45 MB** (baseline APK of this repo measured separately; llama.cpp Android lib not yet built)
- Inference speed **> 15 tok/s** (Qwen2.5-1.5B Q4_K_M on Dimensity 7300)

> **Note**: A docs-only CI run proves nothing about the design. APK size and tok/s targets remain UNVERIFIED until the llama.cpp Android library is built and integrated into this repo, then measured on-device.

---

## 3. Phase 1 Raw Evidence (Appended for Audit)

```
=== llama.cpp commit ===
ac8e1694d837b11ed0aab944a6a2eee34b9c4d80
b11968

=== llama.h signatures (grep -n "llama_tokenize\|llama_token_to_piece\|llama_vocab_is_eog\|llama_get_memory\|llama_memory_clear" -A3) ===
593:    LLAMA_API           llama_memory_t   llama_get_memory  (const struct llama_context * ctx);
594-    LLAMA_API  enum llama_pooling_type   llama_pooling_type(const struct llama_context * ctx); // TODO: rename to llama_get_pooling_type
595-
596-    LLAMA_API const struct llama_vocab * llama_model_get_vocab(const struct llama_model * model);
--
755:    LLAMA_API void llama_memory_clear(
756-            llama_memory_t mem,
757-                      bool data);
758-
--
1211:    LLAMA_API bool llama_vocab_is_eog(const struct llama_vocab * vocab, llama_token token);
1212-
1213-    // Identify if Token Id is a control token or a render-able token
1214-    LLAMA_API bool llama_vocab_is_control(const struct llama_vocab * vocab, llama_token token);
--
1242:    DEPRECATED(LLAMA_API bool llama_token_is_eog(const struct llama_vocab * vocab, llama_token token), "use llama_vocab_is_eog instead");
1243-    DEPRECATED(LLAMA_API bool llama_token_is_control(const struct llama_vocab * vocab, llama_token token), "use llama_vocab_is_control instead");
1244-    DEPRECATED(LLAMA_API llama_token llama_token_bos(const struct llama_vocab * vocab), "use llama_vocab_bos instead");
1245:    DEPRECATED(LLAMA_API llama_token llama_token_eos(const struct llama_vocab * vocab), "use llama_vocab_eos instead");
--
1278:    LLAMA_API int32_t llama_tokenize(
1279-        const struct llama_vocab * vocab,
1280-                      const char * text,
1281-                         int32_t   text_len,
--
1292:    LLAMA_API int32_t llama_token_to_piece(
1293-              const struct llama_vocab * vocab,
1294-                           llama_token   token,
1295-                                  char * buf,
--
1300:    /// @details Convert the provided tokens into text (inverse of llama_tokenize()).
1301-    /// @param text The char pointer must be large enough to hold the resulting text.
1302-    /// @return Returns the number of chars/bytes on success, no more than text_len_max.
1303-    /// @return Returns a negative number on failure - the number of chars/bytes that would have been returned.

=== batch API (grep -rn "batch_add\|batch_clear\|llama_batch_get_one") ===
/data/data/com.termux/files/home/llama.cpp/include/llama.h:965:    LLAMA_API struct llama_batch llama_batch_get_one(
966-                  llama_token * tokens,
967-                      int32_t   n_tokens);
...
976:    LLAMA_API struct llama_batch llama_batch_init(
977-                      int32_t n_tokens,
978-                      int32_t n_seq_max,
979-                      int32_t embd);
...
982:    LLAMA_API void llama_batch_free(struct llama_batch batch);
=== common_batch_add / common_batch_clear ===
**UNVERIFIED** (no matches in llama.cpp/common or llama.cpp/include)

=== common_sampler (grep -n "common_sampler_init\|common_sampler_accept\|common_sampler_sample") ===
40:struct common_sampler * common_sampler_init(
41-        const struct llama_model * model,
42-        struct common_params_sampling & params);
47:void                    common_sampler_accept(struct common_sampler * gsmpl, llama_token token, bool is_generated);
68:llama_token common_sampler_sample(struct common_sampler * gsmpl, struct llama_context * ctx, int idx, bool grammar_first = false);

=== lib/build.gradle.kts === (full content captured in Phase 1)

=== CMakeLists.txt === (full content captured in Phase 1)

=== mtmd search ===
(no matches)

=== v0.8.0 search ===
(no matches in doc or evidence)
```

---

## 4. Gate Criteria (Phase 3)

1. **Diff produced**: `diff -u <prev-rev> <new-rev>` — raw output (this doc is new in Aura repo; diff vs /dev/null)
2. **Branch pushed**: `block6-design-doc-rev3` to `aura-launcher-hermes` origin
3. **CI log tail**: `./gradlew assembleDebug` — raw tail pasted (when implementation exists)
4. **PASS/FAIL** per check with contradictions table
5. **Stop on any CI failure** — do not claim "ready"

---

*End of Rev 3 Design Doc. Awaiting "go" for Phase 3 gate execution.*