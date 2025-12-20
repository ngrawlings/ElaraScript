# ElaraScript

ElaraScript is a **lightweight, deterministic scripting language** written in Java and designed specifically for **safe, embeddable, event-driven application logic**.

It is **not** a general-purpose language and **not** a JavaScript replacement.  
Instead, it acts as a **pure computation and dispatch engine** for transforming structured inputs into derived results — ideal for **dynamic UI logic, event handling, rules engines, and calculations** without recompiling applications.

---

## Core Design Goals

- **Deterministic** — same inputs always produce the same outputs
- **Explicit execution** — no hidden lifecycle or implicit entry points
- **No hidden state** — no persistence, no ambient globals
- **Sandbox-safe** — no file, network, reflection, or OS access
- **Schema-driven** — inputs and outputs validated via `DataShape`
- **Embeddable** — mobile, desktop, backend
- **AI-maintainable** — small surface area, predictable semantics

ElaraScript is intentionally **minimal, opinionated, and stable**.

---

## What ElaraScript Is (and Is Not)

### ✅ It *is*
- A **deterministic computation engine**
- A **clean event-dispatch language**
- A **rules and calculation language**
- A **safe alternative to embedded JavaScript**
- A **UI / app logic backend**

### ❌ It is *not*
- A scripting shell
- A general-purpose programming language
- A stateful runtime
- A plugin VM with escape hatches
- A replacement for application code

---

## Language Overview

### Types

- `number` (double)
- `bool`
- `string`
- `array`
- `null`

(Matrices are represented as arrays of arrays.)

---

### Variables

```js
let x = 10;
let y = x * 2;
```

---

### Control Flow

```js
if (x > 5) {
    y = y + 1;
} else {
    y = y - 1;
}
```

```js
while (i < n) {
    sum = sum + values[i];
    i = i + 1;
}
```

```js
for (i = 0; i < n; i = i + 1) {
    sum = sum + values[i];
}
```

---

### User-Defined Functions

ElaraScript supports **pure, user-defined functions**.

```js
function add(a, b) {
    return a + b;
}

let r = add(2, 3);
```

Functions:
- Are deterministic
- Have no side effects
- Cannot access host state
- May be called from entry points or other functions

---

### Spread Operator (`**`)

Arrays can be expanded into function arguments using `**`.

```js
let args = [2, 3];
let r = add(1, **args);   // add(1, 2, 3)
```

Rules:
- `**` is only valid inside function calls
- The expanded value must be an array
- Expansion is positional and in-place

---

## Execution Model

ElaraScript supports **two explicit execution modes**.

### 1) Program Mode (Global Context)

Runs the script top-to-bottom and returns the final environment snapshot.

```java
Map<String, Value> env = engine.run(script);
```

Use cases:
- Data transforms
- One-off computations
- Initialization logic

---

### 2) Entry-Function Mode (Recommended)

Loads the script, then calls a named entry function.

```java
Value result = engine.run(
    script,
    "dispatch",
    List.of(state, event)
);
```

Or, with environment snapshot:

```java
EntryRunResult rr = engine.runWithEntryResult(
    script,
    "dispatch",
    args,
    initialEnv
);
```

Use cases:
- Event systems
- UI logic
- Application dispatch
- Deterministic app engines

This mode provides a **single, stable execution choke point**.

---

## Event-Driven Design Pattern

A common pattern is:

```js
function dispatch(state, event) {
    return event_router(state, event);
}
```

With handlers named by convention:

```js
function event_click_button(state, id) { ... }
function event_change_input(state, value) { ... }
```

The host:
- Resolves the handler
- Calls it
- Applies the returned state / commands

No large routers. No condition forests.

---

## Strict vs Inference Mode

ElaraScript supports **parser execution modes**:

### Strict Mode
- Fully explicit syntax
- No inferred calls
- Maximum safety

### Inference Mode
- Allows dynamic invocation patterns
- Enables flexible dispatch systems
- Used intentionally by the app engine

The mode is selected by the host.

---

## DataShape (Schema Validation)

`DataShape` defines the **contract** between your app and a script.

- Required inputs
- Optional inputs with defaults
- Required outputs

```java
DataShape shape = DataShape.builder()
    .input("weightKg", NUMBER, true)
    .input("durationMin", NUMBER, true)
    .input("intensity", NUMBER, false, 5)
    .output("calories", NUMBER)
    .build();
```

Validation occurs at runtime before and after execution.

---

## Persistence (External)

ElaraScript has **no internal persistence**.

Persistence is handled externally:

- Capture outputs or environment
- Serialize to JSON
- Restore as raw inputs later

```java
ElaraStateStore store = new ElaraStateStore();
store.captureOutputs(result.outputs());
String json = store.toJson();
```

---

## Plugin System

ElaraScript supports **host-registered pure function plugins**.

Plugins:
- Add deterministic functions
- Cannot modify engine state
- Cannot access host environment

```java
ElaraMathPlugin.register(engine);
```

---

## Standard Plugins

### Math Plugin
- `pow`, `sqrt`, `log`, `exp`
- `abs`, `min`, `max`, `clamp`
- Trigonometric functions

### Matrix Plugin
- `mat_add`, `mat_mul`
- `mat_scalar_mul`
- `mat_transpose`, `mat_shape`

### Finite Field Plugin
- `mod`, `gcd`, `egcd`
- `inv_mod`, `pow_mod`
- Modular arithmetic helpers

---

## Safety Guarantees

ElaraScript guarantees:

- No filesystem access
- No networking
- No reflection
- No threads
- No native calls
- No persistent memory

All computation is bounded and explicit.

---

## Typical Use Cases

- Event-driven UI logic
- Mobile app rule engines
- Financial indicators
- Health & tracking calculations
- Configurable workflows
- AI-generated but **human-verifiable** logic

---

## Versioning Philosophy

- **Core language is stable**
- **Breaking changes are rare**
- **Plugins evolve independently**
- **Scripts are versioned by the host app**

---

## Summary

ElaraScript is a **small, sharp, finished tool**:

- Deterministic
- Explicit
- Event-driven
- Safe
- Maintainable

If you know *what* should happen —  
ElaraScript lets you express it cleanly, safely, and without recompiling your app.
