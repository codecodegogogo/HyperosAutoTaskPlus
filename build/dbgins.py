import zipfile, sys
from androguard.core.dex import DEX
p = sys.argv[1]
z = zipfile.ZipFile(p)
for dn in ["classes.dex","classes2.dex","classes3.dex","classes4.dex"]:
    d = DEX(z.read(dn))
    for c in d.get_classes():
        if c.get_name() != "Lcom/miui/autotask/fragment/NewTaskFragment;": continue
        for m in c.get_methods():
            if m.get_name() != "onCreatePreferences": continue
            for ins in m.get_code().get_bc().get_instructions():
                o = ins.get_output()
                if "const" in o or "string" in o:
                    print(repr(o)[:160])
