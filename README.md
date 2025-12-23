# ElaraScript

## 1. Overview

### 1.1 What is ElaraScript?

**ElaraScript** is a deterministic, sandboxed execution language designed for **stateful, event-driven apps** where logic must be portable, safe, replayable, and easy for **AI systems** to generate and reason about.

ElaraScript is **not** a general-purpose language. It’s a **distributed execution substrate**: executable logic that operates on structured data and produces structured state changes.

In practical terms, ElaraScript binds together:

- Structured inputs (events / payloads)
- Retained app state
- Deterministic execution
- Patch-based state sync

### 1.2 Who is it for?

ElaraScript is designed primarily for:

- **AI systems** that need to modify app behavior dynamically and safely
- **Embedded/mobile** runtimes where full JS/Lua/Python are too heavy or risky
- **Event-driven** apps with long-lived state and incremental updates

Humans typically provide templates and constraints; AI does most of the editing and synthesis.

### 1.3 What niche does it fill?

ElaraScript fills the “**Executable JSON**” niche:

- More than JSON Schema (validation only)
- Safer/more deterministic than JS/Lua
- Higher-level and easier to audit than WASM

ElaraScript is optimized for:

- “One backend, many apps”
- AI-driven app logic updates
- Deterministic simulation and replay
- Patch-based state synchronization

---

## 2. Core Syntax, Keywords, and Operators

### 2.1 Design philosophy

ElaraScript syntax is intentionally small and predictable:

- JavaScript-like surface syntax
- No IO, no threads, no host access
- No reflection or dynamic metaprogramming

### 2.2 Data types

ElaraScript supports a closed set of runtime types:

| Type     | Description                     |
| -------- | ------------------------------- |
| `number` | Double-precision floating point |
| `bool`   | Boolean                         |
| `string` | UTF-8 string                    |
| `bytes`  | Binary data                     |
| `array`  | Ordered list                    |
| `matrix` | 2D numeric array                |
| `map`    | String-keyed object             |
| `null`   | Absence of value                |

### 2.3 Keywords

**Variables**

```js
let x = 10;
```

**Control flow**

```js
if (x > 0) { ... }
else { ... }

while (cond) { ... }

for (let i = 0; i < n; i++) { ... }
```

**Functions**

```js
function add(a, b) {
  return a + b;
}
```

**Early exit**

```js
return value;
break;
```

### 2.4 Operators

Arithmetic:

```
+  -  *  /  %
```

Comparison:

```
==  !=  <  <=  >  >=
```

Logical:

```
&&  ||  !
```

---

## 3. Validator & Data Shaping System

### 3.1 Why validation exists

ElaraScript is designed to process **external inputs** (UI payloads, network events, AI-generated parameters). To preserve determinism and safety, inputs and outputs are validated via **Data Shaping**.

The shaper:

1. Coerces raw inputs
2. Validates structure and constraints
3. Produces a safe execution environment
4. Validates outputs after execution

### 3.2 Shapes

A **Shape** declares expected inputs/outputs plus global safety limits.

Examples (conceptual):

- `input("price", NUMBER).min(0)`
- `output("signal", STRING)`

Shapes are intentionally simpler than JSON Schema:

- Deterministic
- Ordered
- Bounded

### 3.3 FieldSpec constraints

A field may define:

- Required/optional
- Default values
- Numeric bounds
- String/bytes length
- Regex constraints
- Array limits + element typing
- Structural MAP schemas (children)
- Structural ARRAY schemas (itemSpec)
- Matrix limits (rows/cols/cells)

### 3.4 Execution pipeline

The shaping pipeline:

1. Raw input → coerced runtime values
2. Input validation
3. Script execution
4. Output validation
5. Result extraction

Failures return:

- Stable field paths
- A list of structured errors
- Optional debug env snapshots

### 3.5 User-defined validators

For advanced invariants (cross-field checks), a field can attach a **named userFunction** registered on the shaper instance.

These validators:
- Run during validation, not during script execution
- Cannot mutate state
- Cannot access host APIs
- Only emit structured validation errors

---

### 3.6 Null-coalescing (`??`) validation operator

ElaraScript supports a **null-coalescing operator**:

```js
exprA ?? exprB
```

#### Semantics

The `??` operator evaluates as follows:

| `exprA` result | Outcome |
|------|--------|
| non-`null` value | result is `exprA` |
| `null` | result is `exprB` |
| missing variable | treated as `null`, result is `exprB` |

Key properties:
- `??` operates **at execution time**, not during validation
- It does **not bypass** input or output validation
- It only controls **runtime value selection**

This makes `??` safe for AI-generated fallback logic without weakening structural guarantees.

---

### 3.7 Validator invocation via `??`

When a field declares a **userFunction validator**, Elara provides a shorthand pattern using `??`:

```js
value ?? validator_name
```

This is interpreted as:

> If `value` is `null` or missing, invoke `validator_name(value, path)` during validation.

This allows AI or human authors to express **conditional validation requirements** without complex branching logic.

---

### 3.8 Automatic validator routing (`type_<validator_name>`)

Validator functions are resolved automatically using a strict naming convention:

```
type_<validator_name>
```

Example:
```js
function type_positive_number(value, path) {
    if (value < 0) error(path, "must be >= 0");
}
```

Routing rules:
- Validators are invoked **only during the validation phase**
- They are never callable from script execution
- They cannot return values, only emit errors
- Absence of a matching `type_` function is a validation error

This routing model ensures:
- Deterministic validator discovery
- Zero reflection or dynamic lookup
- Predictable behavior for AI-generated code


---

## 4. Event Routing and Execution Model

### 4.1 Event-driven by construction

ElaraScript applications do not have a `main()` function. Execution is always driven by **events**.

An event is a structured payload containing:

- `type` – high-level category (e.g. `system`, `ui`, `net`)
- `target` – specific action or route (e.g. `ready`, `click`, `submit`)
- `value` – arbitrary structured payload

Events are injected by the host runtime, not created by scripts.

---

### 4.2 Event handlers

Scripts define event handlers using a strict naming convention:

```
event_<type>_<target>
```

Example:

```js
function event_ui_click(type, target, payload) {
    // handle UI click
}
```

If no exact handler exists, execution falls back to:

```js
function event_router(type, target, payload) { ... }
```

This guarantees that **every event has a deterministic entry point**.

---

### 4.3 The \_\_event globals

Before execution, the runtime injects read-only globals:

- `__event_type`
- `__event_target`
- `__event_value`
- `__event` → `[type, target, value]`

These are provided for convenience and debugging; the canonical inputs are still the handler arguments.

---

### 4.4 Execution environment

Each event execution receives:

- A snapshot of the retained application state
- The injected event globals
- No access to previous call stacks or side effects

Execution is:

- Single-threaded
- Deterministic
- Fully isolated per session

---

## 5. State Capture, Diffing, and Fingerprinting

### 5.1 Retained state model

ElaraScript maintains state as a **flat map of key → value**.

Rules:

- Keys starting with `__` are **ephemeral** (not persisted)
- All values must be JSON-safe
- State is replaced atomically after execution

---

### 5.2 State capture

After script execution, the runtime captures the final environment and extracts a JSON-safe state snapshot.

This snapshot becomes the authoritative state for the next event.

---

### 5.3 Patch-based diffs

Rather than returning full state, Elara computes a **patch**:

```json
{
  "set": [[key, value], ...],
  "remove": [key, ...]
}
```

Rules:

- Keys removed or set to `null` are emitted as `remove`
- Only changed values appear in `set`
- Ephemeral keys (`__*`) are ignored

This enables efficient UI and network synchronization.

---

### 5.4 Fingerprints

Every state snapshot is fingerprinted using a deterministic hash.

Properties:

- Order-independent
- Stable across platforms
- Sensitive to deep structural changes

Fingerprints allow:

- State integrity verification
- Client/server desync detection
- Replay validation

---

## 6. Session Isolation and Security Model

### 6.1 Sessions

Each app instance runs inside a **session** identified by:

- `sessionId`
- `sessionKey`

A session owns:

- Its retained state
- Its include cache
- Its fingerprint history

---

### 6.2 system.ready bootstrap

A new session is created only by the event:

```
{ type: "system", target: "ready" }
```

The response returns:

- `sessionId`
- `sessionKey`

All subsequent events must present both.

---

### 6.3 Isolation guarantees

- Sessions cannot access each other’s state
- Invalid `sessionKey` immediately aborts execution
- Scripts cannot enumerate sessions

This allows multiple apps to safely coexist in one runtime.

---

## 7. Include Preloading and Deployment

### 7.1 Why includes are preloaded

Scripts cannot access the filesystem.

Instead, all includes are:

- Resolved by the host
- Loaded into memory
- Sent once during `system.ready`

---

### 7.2 Include syntax

```text
#include "path/to/script.es"
```

Rules:

- One include per line
- No inline includes
- Cycles are detected and rejected

---

### 7.3 Deterministic expansion

Before parsing:

- Includes are expanded recursively
- The engine never sees `#include` directives
- Expansion order is deterministic

This guarantees identical behavior across platforms.

---

## 8. AI-Specific Design Considerations

### 8.1 Machine-first language

ElaraScript is intentionally designed so that:

- AI systems can generate it safely
- Behavior can be constrained structurally
- Scripts are easy to diff, rewrite, and audit

Human ergonomics are secondary.

---

### 8.2 Why minimalism matters

Every feature increases the search space for AI.

ElaraScript avoids:

- Metaprogramming
- Dynamic scope tricks
- Implicit side effects

This dramatically improves AI reliability.

---

### 8.3 Executable constraints

The validator system allows AI to:

- Explore behavior safely
- Fail early with structured errors
- Learn boundaries without runtime crashes

This is critical for autonomous systems.

---

### 8.4 Determinism as a learning primitive

Because execution is deterministic:

- AI can replay decisions
- Compare fingerprints
- Reason about cause and effect

This makes ElaraScript suitable as a **learning substrate**, not just an execution engine.

---

## Closing Notes

ElaraScript is intentionally narrow.

That narrowness is what makes it:

- Safe
- Portable
- Auditable
- AI-compatible

It is not meant to replace general-purpose languages. It is meant to **bind intelligence to execution without losing control**.
