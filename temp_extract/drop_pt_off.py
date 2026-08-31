# -*- coding: utf-8 -*-
import re, sys

p = r"A:\Cus\改\src\main\java\dev\comfyfluffy\caustica\client\CausticaClient.java"
raw = open(p, encoding="utf-8").read()
nl = "\r\n" if "\r\n" in raw else "\n"

# --- 1) remove the 3 PT-off helper fields (wasRtActive, PT_OFF_TERRAIN_INTERVAL, ptOffTickCounter) ---
fields = re.compile(r'\s*/\*\* Tracks the PT-on → PT-off transition.*?ptOffTickCounter;', re.S)
n1 = len(fields.findall(raw))
assert n1 == 1, "field block not found / ambiguous: %d" % n1
raw = fields.sub("", raw, count=1)

# --- 2) replace the PT-off branch (silent terrain sync + wasRtActive reset) with a bare return guard ---
branch = re.compile(r'\s*if \(!VanillaRenderController\.rtRuntimeWorkRequested\(\)\) \{.*?wasRtActive = true;\r?\n', re.S)
n2 = len(branch.findall(raw))
assert n2 == 1, "PT-off branch not found / ambiguous: %d" % n2
indent = "\t\t"
ind1 = indent + "\t"
guard = (
    nl + nl + indent + "if (!VanillaRenderController.rtRuntimeWorkRequested()) {" + nl
    + ind1 + "// PT is disabled: leave the RT device/context allocated so re-enabling PT stays instant," + nl
    + ind1 + "// but run no PT terrain work here (no silent chunk/terrain loading while PT is off)." + nl
    + ind1 + "return;" + nl
    + indent + "}" + nl
)
raw = branch.sub(guard, raw, count=1)

open(p, "w", encoding="utf-8", newline="").write(raw)
print("OK fields_removed=%d branch_replaced=%d" % (n1, n2))