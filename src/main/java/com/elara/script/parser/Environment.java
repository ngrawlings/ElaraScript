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
    public static final Map<String, Value> global = new LinkedHashMap<>();

    public final Environment parent;
    public final Value instance_owner;

    // LIFO of local scopes for THIS environment frame
    private final Deque<Map<String, Value>> scopes = new ArrayDeque<>();

    /*
     * This is only called for root, if it is called after root is already created it is a bug.
     * Due to the fact the engine is likely initialised for each event it may be called many times
     * over the live cycle of the service. But only once per execution bucket.
     */
    public Environment() {
        this.parent = null;
        this.instance_owner = null;
        scopes.push(new LinkedHashMap<>()); // root scope
    }
    
    public Environment(Map<String, Value> initial) {
        this.parent = null;
        this.instance_owner = null;
        scopes.push(new LinkedHashMap<>()); // root scope

        if (initial != null) {
            Map<String, Value> top = scopes.peek();
            top.putAll(initial); // NO copying here (your new design)
        }
    }

    private Environment(Environment parent, Value instance_owner) {
        this.parent = parent;
        this.instance_owner = instance_owner;
        scopes.push(new LinkedHashMap<>()); // root scope for this frame
    }
    
    public Environment(Map<String, Value> initial, java.util.Set<String> copyParams) {
        this.parent = null;
        this.instance_owner = null;
        scopes.push(new LinkedHashMap<>());

        if (initial != null) {
            Map<String, Value> top = scopes.peek();
            top.putAll(bindForChildFrame(initial, copyParams)); // uses your helper
        }
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
        if (global.containsKey(name)) return global.get(name);

        throw new RuntimeException("Undefined variable: " + name);
    }

    public boolean exists(String name) {
        for (Map<String, Value> s : scopes) {
            if (s.containsKey(name)) return true;
        }
        if (parent != null && parent.exists(name)) return true;

        Map<String, Value> t = thisMap();
        if (t != null && t.containsKey(name)) return true;

        return global.containsKey(name);
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

        if (global.containsKey(name)) {
            global.put(name, value);
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

        if (global.containsKey(name)) {
            global.remove(name);
        }
    }

    public Map<String, Value> snapshot() {
        Map<String, Value> out = (parent != null) ? parent.snapshot() : new LinkedHashMap<>();

        // only root includes global in snapshot
        if (parent == null) out.putAll(global);

        Map<String, Value> t = thisMap();
        if (t != null) out.putAll(t);

        // apply scopes from oldest -> newest so "nearest" wins
        // (descendingIterator gives bottom->top for ArrayDeque)
        for (java.util.Iterator<Map<String, Value>> it = scopes.descendingIterator(); it.hasNext(); ) {
            out.putAll(it.next());
        }

        return out;
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
