import zipfile, sys, re
p = sys.argv[1]
z = zipfile.ZipFile(p)
needles = [b"preferenceCardGroupBackground", b"card_group", b"mBackgrounds", b"setBackground", b"getPreferenceCardGroup"]
for n in z.namelist():
    if not n.endswith(".dex"): continue
    d = z.read(n)
    hits = [s.decode() for s in needles if d.find(s) >= 0]
    print(n, hits)
