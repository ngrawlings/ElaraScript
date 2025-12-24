function event_system_ready(type, target, payload) {
  native_log("EVENT", "SYSTEM READY");
}

function event_system_test(type, target, payload) {
  let ts = getglobal("ts");
  if (ts == null) {
      native_log("TS", "ts is null");
      ts = 0;
  }
  native_log("TS", ""+ts);
  setglobal("ts", ts+1);
}
