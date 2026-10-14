import zipfile, sys, re
p = sys.argv[1]
z = zipfile.ZipFile(p)
pat = re.compile(rb"L[A-Za-z0-9_$/]+;")
names = set()
for n in z.namelist():
    if not n.endswith(".dex"): continue
    d = z.read(n)
    s = str(d)
    for m in pat.finditer(d):
        nm = m.group(0).decode()
        if "Preference" in nm or "miuix/internal" in nm:
            names.add(nm)
for nm in sorted(names):
    print(nm)
