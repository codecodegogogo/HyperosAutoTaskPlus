import zipfile, sys
from androguard.core.dex import DEX
p = sys.argv[1]
z = zipfile.ZipFile(p)
d = DEX(z.read("classes2.dex"))
print([m for m in dir(d) if "string" in m.lower()])
ss = d.get_strings()
print("strings", len(ss), type(ss[0]))
idx = [i for i,s in enumerate(ss) if "preferenceCardGroupBackground" in s]
print("idx", idx, [ss[i] for i in idx])
