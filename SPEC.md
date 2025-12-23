# ElaraScript Specification (SPEC)

> This document is the **normative specification** for ElaraScript.
> It defines required behavior for parsers, validators, runtimes, and tooling.
>
> Anything not explicitly specified here is **undefined behavior**.

---

## 0. Scope and Conformance

This specification defines:
- The ElaraScript language surface
- Runtime execution semantics
- Validation and data shaping semantics
- Event routing rules
- State, diffing, and fingerprint guarantees

An implementation is **ElaraScript-conformant** if it satisfies all **MUST** and **MUST NOT** requirements in this document.

---

## 1. Terminology and Normative Language

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** are to be interpreted as described in RFC 2119.

---

## 2. Execution Model

### 2.1 Event-driven execution

ElaraScript programs **MUST NOT** define a global entry point.

Execution **MUST** begin at an event handler selected by the runtime based on the incoming event.

Execution is:
- Single-threaded
- Deterministic
- Isolated per session

---

### 2.2 Determinism

Given identical:
- Script source (after include expansion)
- Initial state snapshot
- Event payload

An ElaraScript execution **MUST** produce identical:
- Final state
- Patch output
- Fingerprint

No source of entropy (time, randomness, IO) **MAY** be visible to scripts.

---

## 3. Language Surface

### 3.1 Data types

The runtime **MUST** support exactly the following value types:

- `number` (double precision floating point)
- `bool`
- `string` (UTF-8)
- `bytes`
- `array`
- `matrix`
- `map`
- `null`

No other runtime-visible types are permitted.

---

### 3.2 Variables and scope

Variables **MUST** be declared using `let`.

All variables are:
- Function-scoped
- Non-hoisted
- Shadowing is permitted

Closures **MUST NOT** exist.

---

### 3.3 Expressions and operators

Supported operators **MUST** include:

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

Null-coalescing:
```
??
```

Operator precedence **MUST** be deterministic and implementation-defined but consistent.

---

## 4. Null and Missing Value Semantics

### 4.1 Null vs missing

A value is **missing** if no variable or map key exists.

A value is **null** if explicitly set to `null`.

For the purposes of execution:
- Missing values **MUST** be treated as `null`
- Accessing a missing variable **MUST NOT** throw

---

### 4.2 Null-coalescing operator (`??`)

The expression:
```
A ?? B
```
**MUST** evaluate as:

- Result = `A` if `A` is not `null`
- Result = `B` if `A` is `null` or missing

The `??` operator:
- **MUST NOT** short-circuit validation
- **MUST** operate only at execution time

---

## 5. Validation and Data Shaping

### 5.1 Validation phases

Validation **MUST** occur in three phases:

1. Input coercion and validation
2. Runtime execution
3. Output validation

If phase (1) fails, phases (2) and (3) **MUST NOT** run.

---

### 5.2 Shapes

A Shape **MUST** define:
- Zero or more inputs
- Zero or more outputs

Shapes **MUST** be evaluated deterministically and in declaration order.

---

### 5.3 Field constraints

Field specifications **MAY** define:
- Required or optional
- Default values
- Numeric bounds
- Length bounds
- Regex constraints
- Element typing for arrays
- Structural schemas for arrays and maps

Violations **MUST** emit structured validation errors.

---

### 5.4 User-defined validators

User-defined validators:
- **MUST** be pure
- **MUST NOT** mutate state
- **MUST NOT** access runtime APIs
- **MUST** only emit validation errors

---

### 5.5 Automatic validator routing

A validator named `X` **MUST** be resolved to a function named:

```
type_X
```

If no such function exists, validation **MUST** fail.

Validators **MUST NOT** be callable during execution.

---

## 6. Event Routing

### 6.1 Handler resolution

Given an event `{type, target, value}` the runtime **MUST** attempt to invoke:

```
event_<type>_<target>
```

If not present, the runtime **MUST** invoke:

```
event_router
```

Failure to find either **MUST** be a runtime error.

---

## 7. State Model

### 7.1 Retained state

State **MUST** be represented as a flat map of string keys to JSON-safe values.

Keys beginning with `__`:
- **MUST NOT** be persisted
- **MUST NOT** be included in diffs

---

### 7.2 State replacement

After execution, the retained state **MUST** be replaced atomically with the captured result.

Partial updates **MUST NOT** be visible.

---

## 8. Patch Generation

### 8.1 Patch format

A patch **MUST** have the form:

```json
{ "set": [[key, value]], "remove": [key] }
```

---

### 8.2 Patch rules

- Changed non-null values → `set`
- Removed or null values → `remove`
- Ephemeral keys **MUST** be ignored

---

## 9. Fingerprinting

### 9.1 Fingerprint guarantees

A fingerprint **MUST**:
- Be order-independent
- Be deterministic
- Change on any semantic state change

Fingerprints **MUST** be stable across platforms.

---

## 10. Sessions and Security

### 10.1 Sessions

Each execution **MUST** occur within a session identified by:
- `sessionId`
- `sessionKey`

---

### 10.2 Isolation

A session:
- **MUST NOT** access another session’s state
- **MUST** reject invalid session keys

---

## 11. Include Processing

### 11.1 Include resolution

Includes **MUST** be resolved by the host before execution.

Scripts **MUST NOT** access the filesystem.

---

### 11.2 Deterministic expansion

Include expansion:
- **MUST** be recursive
- **MUST** be cycle-safe
- **MUST** be deterministic

---

## 12. Error Handling

Errors **MUST** be:
- Deterministic
- Structured
- Non-fatal to the runtime

Undefined behavior **MUST NOT** be relied upon.

---

## 13. Compliance

Any implementation claiming ElaraScript compatibility **MUST** conform to this specification in full.

Partial implementations **MUST** clearly declare non-conformance.

---

## Status

This specification is **complete for ElaraScript v1** and may be extended in future revisions without breaking backward compat
