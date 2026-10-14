import zipfile, sys, re
p = sys.argv[1]
z = zipfile.ZipFile(p)
def strlist(data):
    # extract L...; class descriptors
    return set(m.group(0) for m in re.finditer(rb"Lmiuix/preference/[A-Za-z0-9_$/]+;", data))
allc = set()
for n in z.namelist():
    if n.endswith(".dex"):
        d = z.read(n)
        found = strlist(d)
        print(n, len(found))
        allc |= found
for c in sorted(allc):
    print(c.decode())
