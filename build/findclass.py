import zipfile, sys
from androguard.core.dex import DEX
p = sys.argv[1]
z = zipfile.ZipFile(p)
data = z.read("classes2.dex")
d = DEX(data)
target = "preferenceCardGroupBackground"
for c in d.get_classes():
    for s in c.get_strings():
        if target in s:
            print("CLASS", c.get_name(), "->", s)
            break
