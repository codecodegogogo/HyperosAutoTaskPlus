import zipfile, sys
from androguard.core.dex import DEX
p = sys.argv[1]
z = zipfile.ZipFile(p)
target = "preferenceCardGroupBackground"
for dn in ["classes.dex","classes2.dex","classes3.dex","classes4.dex"]:
    d = DEX(z.read(dn))
    for c in d.get_classes():
        for m in c.get_methods():
            code = m.get_code()
            if code is None: continue
            try:
                bcs = code.get_bc().get_instructions()
            except Exception:
                continue
            for ins in bcs:
                try:
                    s = str(ins.get_output())
                except Exception:
                    continue
                if target in s or "setBackground" in s and "PreferenceGroup" in c.get_name():
                    print(dn, c.get_name(), m.get_name(), m.get_descriptor(), "|", s[:200])
                    break
