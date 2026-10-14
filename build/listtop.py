import zipfile, sys, collections
p = sys.argv[1]
z = zipfile.ZipFile(p)
names = z.namelist()
print("total", len(names))
tops = collections.Counter(n.split("/")[0] for n in names)
print(tops)
for n in names[:40]:
    print(n)
