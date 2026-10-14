import zipfile, sys
p = sys.argv[1]
z = zipfile.ZipFile(p)
for n in z.namelist():
    if not n.endswith(".dex"): continue
    d = z.read(n)
    for needle in [b"PreferenceGroupAdapter", b"card_group", b"preferenceCardGroup", b"CardGroup", b"isCardGroup"]:
        i = d.find(needle)
        print(n, needle.decode(), i)
