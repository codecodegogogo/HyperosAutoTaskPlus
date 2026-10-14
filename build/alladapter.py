import zipfile, sys, re
p = sys.argv[1]
z = zipfile.ZipFile(p)
pat = re.compile(rb"[A-Za-z0-9_/$]*Adapter[A-Za-z0-9_/$]*")
seen = {}
for n in z.namelist():
    if not n.endswith(".dex"): continue
    d = z.read(n)
    for m in pat.finditer(d):
        s = m.group(0).decode()
        seen.setdefault(s, set()).add(n)
for s in sorted(seen):
    print(s, sorted(seen[s]))
