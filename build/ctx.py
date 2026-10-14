import zipfile, sys
p = sys.argv[1]
z = zipfile.ZipFile(p)
d = z.read("classes2.dex")
for off in [6732209, 6820631, 6090493]:
    s = d[off-80:off+120]
    print(off, s)
    print("---")
