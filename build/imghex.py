from PIL import Image
import sys
p = sys.argv[1]
im = Image.open(p).convert("RGB")
w,h = im.size
print(p, w, h)
cols, rows = 8, 24
im2 = im.resize((cols, rows))
for y in range(rows):
    print(" ".join("%02x%02x%02x" % im2.getpixel((x,y)) for x in range(cols)))
