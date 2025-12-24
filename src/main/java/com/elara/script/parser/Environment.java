package com.elara.script.parser;

import java.util.LinkedHashMap;
import java.util.Map;

import com.elara.script.parser.Value.ClassInstance;

public class Environment {
	public static final Map<String, Value> global = new LinkedHashMap<>();
	
	public Map<String, Value> _this;
	
    public final Map<String, Value> vars = new LinkedHashMap<>();
   
    public final Environment parent;
    
    public final ClassInstance instance_owner;

    /*
     * This is only called for root, if it is called after root is already created it is a bug.
     * Due to the fact the engine is likely initialized for each event it may be called many times 
     * over the live cycle of the service. But only once per execution bucket.
     */
    public Environment() { 
    	this.parent = null; 
    	this.instance_owner = null; 
    }
    
    /*
     * Generally this should not be called directly this is to be chained from the parent using childScope
     */
    private Environment(Environment parent, ClassInstance instance_owner, Map<String, Value> initial) {
        this.parent = null;
        this.instance_owner = instance_owner;
        
        if (initial != null)
        	this.vars.putAll(initial);
    }
    
    /* 
     * receives the this object saved in the ClASS INSTANCE TYPE
     */
    public void setThis(Map<String, Value> _this) {
    	this._this = _this;
    }

    public void define(String name, Value value) {
        if (vars.containsKey(name)) {
            throw new RuntimeException("Variable already defined: " + name);
        }
        vars.put(name, value);
    }

    public void assign(String name, Value value) {
        if (vars.containsKey(name)) {
            vars.put(name, value);
        } else if (parent != null) {
            parent.assign(name, value);
        } else {
            throw new RuntimeException("Undefined variable: " + name);
        }
    }

    public Value get(String name) {
        if (vars.containsKey(name)) {
            return vars.get(name);
        }
        if (parent != null) return parent.get(name);
        throw new RuntimeException("Undefined variable: " + name);
    }

    public boolean exists(String name) {
        if (vars.containsKey(name)) return true;
        return parent != null && parent.exists(name);
    }

    /*
     * If a call is being made to a function instance_owner much be null.
     * If it is being made to a class method, instance_owner must be set to the receiving class instance
     * This is true for this.method calls as even though it is set it will receive the same instance due 
     * to it receive it from "this"
     */
    public Environment childScope(ClassInstance instance_owner, Map<String, Value> initial) {
        return new Environment(this, instance_owner, initial);
    }

    public Map<String, Value> snapshot() {
        Map<String, Value> out = (parent != null) ? parent.snapshot() : new LinkedHashMap<String, Value>();
        out.putAll(vars);
        return out;
    }
    
    public void remove(String name) {
        if (vars.containsKey(name)) {
            vars.remove(name);
            return;
        }
        if (parent != null) {
            parent.remove(name);
            return;
        }
        // if you prefer: ignore missing vs throw
    }
}