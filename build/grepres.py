import zipfile, sys
p = sys.argv[1]
z = zipfile.ZipFile(p)
data = z.read("resources.arsc")
for needle in [b"auto_task_item_recycler_view", b"auto_task_select_address_item", b"auto_task_select_icon", b"auto_task_task_item", b"miuix_preference_item"]:
    print(needle.decode(), data.find(needle))
