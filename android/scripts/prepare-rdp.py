#!/usr/bin/env python3
"""Adapt only the official JNI Java bridge; the RDP protocol engine stays upstream."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
source = root/'native/vendor/FreeRDP-FreeRDP-c7e6848/client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java'
t = source.read_text()
t = t.replace('import androidx.collection.LongSparseArray;', 'import android.util.LongSparseArray;')
t = re.sub(r'import com\.freerdp\.freerdpcore\.(application|domain|presentation)\.[^;]+;', '', t)
t = t.replace('private static EventListener listener;', 'private static EventListener listener;\n private static final java.util.concurrent.ConcurrentHashMap<Long, UIEventListener> sessions = new java.util.concurrent.ConcurrentHashMap<>();\n public static void attach(long id, UIEventListener listener) { sessions.put(id, listener); }\n public static void detach(long id) { sessions.remove(id); }')
a=t.index('\tprivate static String addFlag('); b=t.index('\tpublic static boolean updateGraphics(',a)
t=t[:a]+t[b:]
# Mark the native connection thread as alive before it can callback. Without this,
# closing during authentication could free its context while it is still connecting.
t=t.replace('return freerdp_connect(inst);', 'synchronized (mInstanceState) { mInstanceState.put(inst, true); }\n boolean result = freerdp_connect(inst);\n if (!result) { synchronized (mInstanceState) { mInstanceState.remove(inst); mInstanceState.notifyAll(); } }\n return result;')
t=t.replace('private static native boolean freerdp_parse_arguments', 'public static native boolean freerdp_parse_arguments').replace('private static native String freerdp_get_last_error_string', 'public static native String freerdp_get_last_error_string')
t=re.sub(r'\t\tSessionState s = GlobalApp.getSession\(inst\);\n\t\tif \(s == null\)\n\t\t\treturn[^;]*;\n\t\tUIEventListener uiEventListener = s.getUIEventListener\(\);', '\t\tUIEventListener uiEventListener = sessions.get(inst);',t)
assert 'GlobalApp' not in t and 'BookmarkBase' not in t
path=root/'app/src/main/java/com/freerdp/freerdpcore/services'
path.mkdir(parents=True,exist_ok=True);(path/'LibFreeRDP.java').write_text(t)
# Default adapter keeps optional pointer/RemoteApp callbacks out of each desktop screen.
interface=t[t.index('\tpublic interface UIEventListener'):]
methods=re.findall(r'(void|boolean|int)\s+(On\w+)\(([^;]*)\);',interface)
lines=['// Adapted from FreeRDP 3.32.2 LibFreeRDP.java; licensed under MPL 2.0.', 'package com.freerdp.freerdpcore.services;', 'public class RdpListener implements LibFreeRDP.UIEventListener {']
for result,name,args in methods:
    lines.append(' public '+result+' '+name+'('+args+') {'+('' if result=='void' else 'return '+('false' if result=='boolean' else '0')+';')+'}')
lines.append('}');(path/'RdpListener.java').write_text('\n'.join(lines))
