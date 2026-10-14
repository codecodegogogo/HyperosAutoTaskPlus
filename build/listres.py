import zipfile, sys
p = sys.argv[1]
z = zipfile.ZipFile(p)
names = [n for n in z.namelist() if n.startswith("res/") and ("auto_task" in n or "task_item" in n)]
for n in sorted(names):
    print(n)
