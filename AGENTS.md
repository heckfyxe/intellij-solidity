# IntelliJ Solidity Agent Contributor Guide

The project aims to create the best Solidity language plugin for JetBrains IDEs.

## Lexer & Parser

The flex lexer is in `src/main/grammars/_SolidityLexer.flex`
The parser grammar is in `src/main/grammars/solidity.bnf`
The g4 grammars are `src/main/grammars/reference` are the reference grammars that are checked in for convenience. They 
never need to be updated. 

Every time you change the BNF grammar or the flex lexer, you'll need to run the following for the changes to take
effect:

```bash
./gradlew generateLexer generateParser
```

For the parser tests, the tests correspond to sol (input) and txt (output) files in src/test/resources/fixtures/parser.
Each test will read the input sol file, parse it, and compare the resulting PSI tree against the expected output in the
corresponding txt file.
The test will fail if the actual PSI tree does not match the expected output, or if there is PsiErrorElement in the
tree.
If there is a mismatch, the test will fail. If the test fails, it doesn't mean the parser is broken, it just means the
expected output has changed.
You can regenerate the expected output by removing the txt file, then running the test again to regenerate it, and then
re-running the test to ensure the output doesn't contain any errors.

**Tip:** Instead of manually updating the .txt files for the tests, you can delete the .txt file and re-run the
test. The test framework will automatically regenerate the .txt file with the correct output.

### Advanced Lexer and Parser Guidelines

These guidelines are crucial for maintaining a robust and adaptable lexer/parser.

#### 1. Prioritize Lexer Rule Order and Specificity

* **Context:** JFlex (the lexer generator) matches the longest possible sequence of input. If multiple rules match the
  same longest sequence, the rule defined *first* in the `.flex` file takes precedence.
* **Guidance:** For overlapping patterns, especially delimiters with optional parts (like whitespace control hyphens),
  place the most specific (longest) rule *before* more general ones within the same lexer state.

#### 2. Ensure BNF Syntax for Optionality is Correct (Grammar-Kit)

* **Context:** Grammar-Kit's BNF dialect uses `[element_name_or_literal]` for optional elements.
* **Guidance:**
    * Use `[my_rule_or_token]` to make it optional.
    * Do not use regex-style `element?` or `[element]?` with a question mark after the brackets.
    * For an optional sequence: `[ (rule_A rule_B) ]`.
    * Repetition: `rule*` (zero or more), `rule+` (one or more).

#### 3. Debugging with PSI Output and Iterative Changes

* **Context:** Parser errors can be tricky. The generated `.txt` files (PSI trees) are key.
* **Guidance:**
    * Look for `PsiErrorElement`. The error message often lists expected tokens versus the actual token that caused the
      failure.
    * Make small, incremental changes to `.flex` or `.bnf` files. After each change, regenerate and run relevant tests
      to isolate errors quickly.

## Formatter and Typing

* The plugin has no formatter of its own: Reformat Code runs `forge fmt` (`SolidityExternalFormatter`), only when
  forge is installed (automatic mode) or configured; format on save is the platform's Actions on Save.
* Enter/`}` indentation comes from the formatting model (`SolFormattingBlock.getChildAttributes`), which only computes
  indents: `SolidityFormattingModelBuilder` returns a no-op model for `FormattingMode.REFORMAT` and blocks have no
  spacing. Code that edits PSI must therefore produce well-laid-out text itself (e.g. `layoutImports` after adding
  imports); nothing reformats it afterwards.
* Tests run with the formatter set to Disabled (`SolLightPlatformCodeInsightFixtureTestCase.setUp`), so they don't
  depend on forge being installed; formatter tests select it explicitly.
* Typing behaviour is covered by `SolAutoIdentationTest` (fixtures in `fixtures/auto_ident/`),
  `SolExternalFormatterTypingTest`, `SolQuoteTypingTest`, `SolBraceTypingTest`, `SolSmartEnterTest` and
  `SolLineDocCommentEnterHandlerTest` (`///` continuation).
* Complete Current Statement (`SolSmartEnterProcessor`) works on lexer tokens, not PSI: incomplete statements parse
  into bare tokens (a state variable without `;` even escapes its contract). It inserts only `)`/`;`/` {}` on one line
  and lets a real Enter lay out the block; `SmartEnterProcessorWithFixers.plainEnter` is Start New Line, not Enter,
  and its `reformat` must stay a no-op so forge fmt never runs on half-written code. Inside assembly and in `.yul`
  files only `SolYulFixer` (`SolStatementAnalyzer.yulFix`) changes the text: Yul statements have no `;`.

## Types

* Number constants fold like solc's rational constants: integral values are `SolInteger` with `literalValue`
  (assignable to every integer type that holds them), fractional ones are `SolRationalConstant` (assignable to
  nothing). Literal units (`ether`, `days`) are applied. Messages name constants with `SolType.displayName()`
  (`int_const 300`, `rational_const 1 / 2`), never with `toString()`.
* A typed operand combined with a constant takes solc's common type: `uint8 x; x + 300` is `uint16`, `x + -1` is
  unknown (solc rejects it). Keep inference unknown rather than guessing wherever solc would reject.
* `bytes.concat`/`string.concat` exist only on the type names (`elementaryTypeNameMembers`), not on values.
* `using L for T`/`*` attaches only library functions whose first parameter accepts the receiver
  (`SolMemberAccessElement.collectUsingForLibraryFunctions`); unknown parameter types keep the function.

## Inline Assembly (Yul)

* Yul declarations are `YulVariable` (`let` names, function parameters/returns) and `YulFunctionDefinition`; uses are
  `YulPath` (variables, `x.slot`) and `YulFunctionCall` (callee). Both resolve through `SolYulResolver`, which applies
  Yul scoping and falls back to Solidity variables visible at the assembly statement.
* Built-ins have no declaration: `YulBuiltins` lists them (with parameter names for completion) and
  `ResolveNameInspection` skips them, as well as `verbatim_<n>i_<m>o`.
* A bare identifier statement parses as `YulExpressionStatement` (reported by the annotator) so a name being typed stays
  inside the Yul block; completion there comes from `SolYulCompletionContributor`, which stops other contributors.
* The lexer has no `->` token: a Yul function's arrow is `-` followed by `>` (see `SolYulFunctionDefMixin`).
* `YulBuiltins` also holds each built-in's description and EVM version (`EvmVersion`), used by Quick Documentation
  and `YulEvmVersionInspection`. That inspection reads forge's `evm_version` from `FoundryProject.cachedConfig()` only
  (never runs forge under a read action); tests set `YulEvmVersionInspection.evmVersionForTests`.
* Inspections and annotators registered for `Solidity` also run in `.yul` files (`YulLanguage` is a dialect of it).

## Chain Failures

* `ChainExecutor` explains failures with `RevertTracer` (`debug_traceCall`/`debug_traceTransaction`) and maps the
  failing pc to source with forge artifacts' source maps (`CompiledSources`). anvil returns no `structLogs` for mined
  transactions unless started with `--steps-tracing`, so those are replayed with `debug_traceCall` on the parent block.

## Foundry Integration

* `FoundryProject.config()` reads `forge config --json` (`FoundryProject.parseConfig`). The live compile
  (`SolcExternalAnnotator`, request built by `SolcRequest`) passes forge's `evm_version`, `via_ir` and optimizer
  settings; forge lowers `evm_version` to what the chosen solc supports (default `osaka` becomes `cancun` for 0.8.24),
  and solc rejects unsupported names with a `JSONError`, so `SolcRequest.normalizeEvmVersion` does the same.
* `.gas-snapshot` hints (`ForgeGasSnapshotInlayProvider`) are cached on the snapshot file by modification stamp.
  `forge snapshot --check` exits 1 and prints `Diff in "C::test()": consumed "(gas: N)" gas, expected "(gas: M)" gas`
  per changed test (`ForgeSnapshotOutput`).
* `forge coverage --report lcov` writes `lcov.info` in the Foundry root, with `SF:` paths relative to it, even when a
  test fails (exit code 1); `ForgeCoverageService` keeps the result and paints it in editors. A file's markers
  are dropped once it is edited (document listener) or its timestamp is newer than the run's start, since its lines no
  longer match the report.

## Common Mistakes and How to Avoid Them

* Never edit files manually with the "This is a generated file" header, they're generated by the BNF grammar or the flex
  lexer.

## Project Structure

- `src/` – Plugin sources and tests
    - `src/main/grammars/` – JFlex lexer (`_SolidityLexer.flex`), Grammar-Kit parser (`solidity.bnf`), and reference grammars
        - `reference/` – ANTLR4 reference grammars (`Solidity.g4`, `SolidityLexer.g4`)
    - `src/main/kotlin/` – Kotlin production sources under `me.serce.solidity`
        - `ide/` – IDE-specific functionality
            - `actions/` – IDE actions
            - `annotation/` – code annotation functionality
            - `colors/` – color scheme definitions
            - `formatting/` – code formatting functionality
            - `hints/` – code hints and documentation providers
            - `inspections/` – code inspection functionality
            - `liveTemplates/` – live template definitions
            - `navigation/` – code navigation functionality
            - `refactoring/` – refactoring functionality
        - `lang/` – Solidity language support
            - `completion/` – code completion functionality
            - `core/` – core language functionality (lexer, parser, tokens, file type)
            - `psi/` – PSI (Program Structure Interface) definitions
            - `resolve/` – reference resolution functionality
            - `stubs/` – stub index functionality
            - `types/` – type system functionality
    - `src/main/resources/` – Plugin resources
        - `META-INF/plugin.xml` – plugin descriptor
        - `fileTemplates/` – file templates
        - `icons/` – plugin icons
        - `inspectionDescriptions/` – descriptions for code inspections
        - `liveTemplates/` – live template definitions
    - `src/test/` – Kotlin tests
        - `kotlin/me/serce/solidity/` – Test sources mirroring production code structure
            - `ide/` – tests for IDE-specific functionality
            - `lang/` – tests for language-specific functionality
            - `utils/` – test utilities
        - `resources/fixtures/` – test fixtures
            - `parser/` – parser test fixtures
            - `formatter/` – formatter test fixtures
            - `folding/` – folding test fixtures
            - `import/` – import test fixtures
            - `refactoring/` – refactoring test fixtures
    - `src/jmh/` – JMH benchmarks
        - `README.md` – how to run benchmarks
        - `kotlin/me/serce/solidity/lang/benchmark/` – benchmark sources

## Knowledge Accumulation

If you are corrected, or you discover a significant and important piece of information that is missing from AGENTS.md (this file), ensure you add it.

### Codex-specific instructions

When running gradle tasks, use the `--no-daemon` flag to avoid issues with the Gradle daemon inside the container.
