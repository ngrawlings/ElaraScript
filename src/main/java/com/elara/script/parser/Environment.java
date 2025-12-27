package com.elara.script.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Environment {
	// The ExecutionState must be state in order to have access to the globals (Map<String, Value> global;)
    public ExecutionState exec_state;

    public final Environment parent;
    public final Value instance_owner;

    // LIFO of local scopes for THIS environment frame
    public final Deque<Map<String, Value>> scopes = new ArrayDeque<>();

    /*
     * This is only called for root, if it is called after root is already created it is a bug.
     * Due to the fact the engine is likely initialised for each event it may be called many times
     * over the live cycle of the service. But only once per execution bucket.
     */
    public Environment(ExecutionState exec_state) {
    	this.exec_state = exec_state;
        this.parent = null;
        this.instance_owner = null;
        scopes.push(new LinkedHashMap<>()); // root scope
    }
    
    public Environment(ExecutionState exec_state, Map<String, Value> initial) {
    	this.exec_state = exec_state;
        this.parent = null;
        this.instance_owner = null;
        scopes.push(new LinkedHashMap<>()); // root scope

        if (initial != null) {
            Map<String, Value> top = scopes.peek();
            top.putAll(initial); // NO copying here (your new design)
        }
    }

    private Environment(Environment parent, Value instance_owner) {
    	this.exec_state = parent.exec_state;
        this.parent = parent;
        this.instance_owner = instance_owner;
        scopes.push(new LinkedHashMap<>()); // root scope for this frame
    }
    
    public Environment(ExecutionState exec_state, Map<String, Value> initial, java.util.Set<String> copyParams) {
    	this.exec_state = exec_state;
        this.parent = null;
        this.instance_owner = null;
        scopes.push(new LinkedHashMap<>());

        if (initial != null) {
            Map<String, Value> top = scopes.peek();
            top.putAll(bindForChildFrame(initial, copyParams)); // uses your helper
        }
    }
    
    public void setGlobal(String name, Value val) {
    	this.exec_state.global.put(name, val);
    }
    
    public void putAllGlobals(String name, Map<String, Value> map) {
    	this.exec_state.global.putAll(map);
    }

    public Value getGlobal(String name) {
    	return this.exec_state.global.get(name);
    }

    // -------------------------
    // Block-scoping (LIFO)
    // -------------------------
    public void pushBlock() {
        scopes.push(new LinkedHashMap<>());
    }

    public void popBlock() {
        if (scopes.size() <= 1) {
            throw new RuntimeException("Cannot pop root scope of environment frame");
        }
        scopes.pop();
    }

    // -------------------------
    // Optional helper (kept for later: name-based copy binding)
    // We'll use/replace this with copyParams logic above it.
    // -------------------------
    private static Map<String, Value> bindForChildFrame(
            Map<String, Value> initial,
            java.util.Set<String> copyParams
    ) {
        Map<String, Value> out = new LinkedHashMap<>();
        if (initial == null) return out;

        for (Entry<String, Value> e : initial.entrySet()) {
            String k = e.getKey();
            Value v = e.getValue();

            boolean doCopy = (copyParams != null && copyParams.contains(k));
            out.put(k, v == null ? null : v.getForChildStackFrame(doCopy));
        }
        return out;
    }

    private Map<String, Value> thisMap() {
        return (instance_owner == null) ? null : instance_owner.asClassInstance()._this;
    }

    private Map<String, Value> topScope() {
        Map<String, Value> top = scopes.peek();
        if (top == null) throw new RuntimeException("Environment scope stack is empty (bug)");
        return top;
    }

    // -------------------------
    // Vars API
    // -------------------------
    public void define(String name, Value value) {
        Map<String, Value> top = topScope();
        if (top.containsKey(name)) {
            throw new RuntimeException("Variable already defined: " + name);
        }
        top.put(name, value);
    }

    public Value get(String name) {
        // nearest scope in this env frame
        for (Map<String, Value> s : scopes) {
            if (s.containsKey(name)) return s.get(name);
        }

        // parent chain (function closures etc)
        if (parent != null && parent.exists(name)) {
            return parent.get(name);
        }
        
        //Check if it is the local class instance
        if (instance_owner != null) {
        	String[] parts = name.split("\\.");
        	if (parts.length == 2) {
        		if (instance_owner.asClassInstance().className.equals(parts[0]) && instance_owner.asClassInstance().uuid.equals(parts[1])) {
        			return new Value(Value.Type.MAP, instance_owner.asClassInstance()._this);
        		}
        	}
        }

        // this
        Map<String, Value> t = thisMap();
        if (t != null && t.containsKey(name)) {
            return t.get(name);
        }

        // global
        if (exec_state.global.containsKey(name)) return exec_state.global.get(name);

        throw new RuntimeException("Undefined variable: " + name);
    }

    public boolean exists(String name) {
        for (Map<String, Value> s : scopes) {
            if (s.containsKey(name)) return true;
        }
        if (parent != null && parent.exists(name)) return true;

        Map<String, Value> t = thisMap();
        if (t != null && t.containsKey(name)) return true;

        return exec_state.global.containsKey(name);
    }

    public void assign(String name, Value value) {
        for (Map<String, Value> s : scopes) {
            if (s.containsKey(name)) {
                s.put(name, value);
                return;
            }
        }

        if (parent != null && parent.exists(name)) {
            parent.assign(name, value);
            return;
        }

        Map<String, Value> t = thisMap();
        if (t != null && t.containsKey(name)) {
            t.put(name, value);
            return;
        }

        if (exec_state.global.containsKey(name)) {
        	exec_state.global.put(name, value);
            return;
        }

        throw new RuntimeException("Undefined variable: " + name);
    }

    public void remove(String name) {
        for (Map<String, Value> s : scopes) {
            if (s.containsKey(name)) {
                s.remove(name);
                return;
            }
        }

        if (parent != null && parent.exists(name)) {
            parent.remove(name);
            return;
        }

        Map<String, Value> t = thisMap();
        if (t != null && t.containsKey(name)) {
            t.remove(name);
            return;
        }

        if (exec_state.global.containsKey(name)) {
        	exec_state.global.remove(name);
        }
    }

    /**
     * Snapshot the chained environments as an ordered list of frames (outer -> inner).
     *
     * Each frame is a MAP with:
     *  - vars: MAP (merged scopes for this frame only)
     *  - this_ref: STRING (optional) => "<ClassName>.<uuid>"
     *
     * Also prepends a synthetic "global" frame that contains Environment.global.
     */
    public List<Map<String, Value>> snapshotFrames() {
        List<Map<String, Value>> framesInnerToOuter = new ArrayList<>();
        Environment cur = this;

        while (cur != null) {
            Map<String, Value> frame = new LinkedHashMap<>();

            // merge THIS frame's scopes only (oldest -> newest so "nearest wins")
            Map<String, Value> merged = new LinkedHashMap<>();
            for (java.util.Iterator<Map<String, Value>> it = cur.scopes.descendingIterator(); it.hasNext();) {
                merged.putAll(it.next());
            }

            frame.put("vars", Value.map(merged));

            if (cur.instance_owner != null && cur.instance_owner.getType() == Value.Type.CLASS_INSTANCE) {
                Value.ClassInstance inst = cur.instance_owner.asClassInstance();
                frame.put("this_ref", Value.string(inst.stateKey())); // "Class.uuid"
            }

            framesInnerToOuter.add(frame);
            cur = cur.parent;
        }

        // Reverse to outer -> inner
        Collections.reverse(framesInnerToOuter);

        // Synthetic global frame so export/import doesn't special-case statics
        Map<String, Value> globalFrame = new LinkedHashMap<>();
        globalFrame.put("vars", Value.map(new LinkedHashMap<>(exec_state.global)));
        framesInnerToOuter.add(0, globalFrame);

        return framesInnerToOuter;
    }

    /*
     * If a call is being made to a function instance_owner must be null.
     * If it is being made to a class method, instance_owner must be set to the receiving class instance.
     */
    public Environment childScope(Value instance_owner) {
        return new Environment(this, instance_owner);
    }
    
    public boolean existsInCurrentScope(String name) {
        Map<String, Value> top = scopes.peek();
        return top != null && top.containsKey(name);
    }
    
    /** DEBUG: returns scopes from bottom->top (root block first, then nested blocks). */
    public List<Map<String, Value>> debugScopesBottomToTop() {
        // ArrayDeque iterator goes from head->tail, and you push() onto head,
        // so iteration is TOP->BOTTOM. We want BOTTOM->TOP.
        List<Map<String, Value>> topToBottom = new ArrayList<>();
        for (Map<String, Value> s : scopes) topToBottom.add(s);

        Collections.reverse(topToBottom); // now bottom->top
        return topToBottom;
    }

    /** DEBUG: merged view of this frame (bottom->top so top shadows). */
    public Map<String, Value> debugFrameMerged() {
        Map<String, Value> out = new LinkedHashMap<>();
        for (Map<String, Value> s : debugScopesBottomToTop()) {
            out.putAll(s);
        }
        return out;
    }

    /** DEBUG: walk to root env frame. */
    public Environment root() {
        Environment e = this;
        while (e.parent != null) e = e.parent;
        return e;
    }
}
