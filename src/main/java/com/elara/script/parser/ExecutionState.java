package com.elara.script.parser;

import java.util.LinkedHashMap;
import java.util.Map;

import com.elara.script.parser.Value.ClassInstance;

public class ExecutionState {
	public LinkedHashMap<String, Value.ClassInstance> liveInstances;
	public Environment env;
	
	public ExecutionState() {
		liveInstances = new LinkedHashMap<>();
		env = new Environment();
	}
	
	public ExecutionState(Map<String, Value.ClassInstance> instances, Map<String, Value> env_state) {
		liveInstances = (LinkedHashMap<String, ClassInstance>) instances;
		env = new Environment(env_state);
	}
	
}
