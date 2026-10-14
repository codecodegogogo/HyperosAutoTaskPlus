import zipfile, sys, collections
p = sys.argv[1]
z = zipfile.ZipFile(p)
names = [n for n in z.namelist() if n.startswith("res/")]
c = collections.Counter(n.split("/")[1] if n.count("/")>1 else n for n in names)
print(c.most_common(20))
print([n for n in names if n.startswith("res/") and n.count("/")==1][:20])
print("--- sample ---")
for n in names[:20]: print(n)
