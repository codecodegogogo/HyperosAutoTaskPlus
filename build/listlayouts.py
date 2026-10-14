import zipfile, sys, re
p = sys.argv[1]
z = zipfile.ZipFile(p)
names = [n for n in z.namelist() if re.match(r"res/layout[^/]*/", n)]
print("layout count", len(names))
for n in sorted(names):
    print(n)
