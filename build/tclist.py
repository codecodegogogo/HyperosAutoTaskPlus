import sys, zipfile
from androguard.core.dex import DEX
p = r"apk/安全服务(手机管家)-13.6.0-260914.0.1(40001360).apk"
z = zipfile.ZipFile(p)
want = sys.argv[1] if len(sys.argv) > 1 else "miuix/preference"
for n in z.namelist():
    if not n.endswith(".dex"):
        continue
    d = DEX(z.read(n))
    for c in d.get_classes():
        name = c.get_name()
        if want in name:
            ifaces = []
            try:
                ifaces = [i for i in c.get_interfaces()]
            except Exception as e:
                ifaces = ["?"]
            methods = []
            try:
                methods = [m.get_name() for m in c.get_methods() if m.get_class_name() == name]
            except Exception:
                pass
            flag = "enabledCardStyle" in methods
            print(n, name, "| super=", c.get_superclassname(), "| ifaces=", ifaces, "| hasEnabledCardStyle=", flag)
