"""CI script: export CLIP vision encoder to ONNX (quantized) + precompute concept text embeddings."""
import json, os
import numpy as np
import torch
from transformers import CLIPProcessor, CLIPTextModelWithProjection, CLIPVisionModelWithProjection
from PIL import Image

OUT = "app/src/main/assets/clip"
os.makedirs(OUT, exist_ok=True)

# (english, hebrew, category-or-empty)
CONCEPTS = [
    ("french food", "אוכל צרפתי", "אוכל"), ("croissant", "קרואסון", "אוכל"),
    ("italian food", "אוכל איטלקי", "אוכל"), ("pizza", "פיצה", "אוכל"),
    ("pasta", "פסטה", "אוכל"), ("sushi", "סושי", "אוכל"),
    ("asian food", "אוכל אסייתי", "אוכל"), ("middle eastern food", "אוכל מזרחי", "אוכל"),
    ("hummus", "חומוס", "אוכל"), ("falafel", "פלאפל", "אוכל"),
    ("shawarma", "שווארמה", "אוכל"), ("salad", "סלט", "אוכל"),
    ("cake", "עוגה", "אוכל"), ("dessert", "קינוח", "אוכל"),
    ("pastry", "מאפה", "אוכל"), ("bread", "לחם", "אוכל"),
    ("cookies", "עוגיות", "אוכל"), ("chocolate", "שוקולד", "אוכל"),
    ("ice cream", "גלידה", "אוכל"), ("coffee", "קפה", "אוכל"),
    ("cocktail drink", "קוקטייל", "אוכל"), ("wine", "יין", "אוכל"),
    ("soup", "מרק", "אוכל"), ("burger", "המבורגר", "אוכל"),
    ("steak meat dish", "בשר", "אוכל"), ("fish seafood dish", "דגים", "אוכל"),
    ("breakfast plate", "ארוחת בוקר", "אוכל"), ("restaurant dishes on a table", "מסעדה", "אוכל"),
    ("dog", "כלב", "טבע וחיות"), ("cat", "חתול", "טבע וחיות"),
    ("bird", "ציפור", "טבע וחיות"), ("horse", "סוס", "טבע וחיות"),
    ("wild animal", "חיית בר", "טבע וחיות"), ("flowers", "פרחים", "טבע וחיות"),
    ("plant", "צמח", "טבע וחיות"), ("tree forest", "יער", "טבע וחיות"),
    ("beach and sea", "חוף ים", "מקומות"), ("mountains landscape", "הרים", "מקומות"),
    ("desert landscape", "מדבר", "מקומות"), ("sunset sky", "שקיעה", "מקומות"),
    ("city street", "רחוב עירוני", "מקומות"), ("old building architecture", "מבנה ישן", "מקומות"),
    ("modern building", "בניין מודרני", "מקומות"), ("interior of a home", "עיצוב פנים", "מקומות"),
    ("hotel room", "חדר מלון", "מקומות"), ("swimming pool", "בריכה", "מקומות"),
    ("map", "מפה", "מסמכים וגרפים"), ("chart or graph", "גרף", "מסמכים וגרפים"),
    ("table of data", "טבלה", "מסמכים וגרפים"), ("document form", "טופס", "מסמכים וגרפים"),
    ("calendar", "לוח שנה", "מסמכים וגרפים"), ("receipt", "קבלה", "קבלות וקניות"),
    ("menu of a restaurant", "תפריט", "אוכל"),
    ("painting artwork", "ציור", "אמנות"), ("sculpture", "פסל", "אמנות"),
    ("street art graffiti", "אמנות רחוב", "אמנות"), ("surreal art", "אמנות סוריאליסטית", "אמנות"),
    ("photography portrait of a person", "פורטרט", "אנשים"),
    ("group of people", "קבוצת אנשים", "אנשים"), ("baby or child", "תינוק", "אנשים"),
    ("wedding event", "חתונה", "אנשים"), ("selfie photo", "סלפי", "אנשים"),
    ("clothing fashion", "בגדים", "קניות"), ("shoes", "נעליים", "קניות"),
    ("furniture", "רהיטים", "קניות"), ("jewelry watch", "תכשיטים", "קניות"),
    ("electronics gadget", "מוצר אלקטרוני", "קניות"), ("product listing in online shop", "מוצר בחנות", "קניות"),
    ("car", "רכב", "רכב"), ("motorcycle", "אופנוע", "רכב"),
    ("bicycle", "אופניים", "רכב"), ("airplane", "מטוס", "רכב"),
    ("boat ship", "סירה", "רכב"),
    ("soccer football game", "כדורגל", "ספורט"), ("basketball game", "כדורסל", "ספורט"),
    ("gym workout", "אימון כושר", "ספורט"), ("running outdoors", "ריצה", "ספורט"),
    ("book cover", "ספר", ""), ("movie poster", "פוסטר סרט", ""),
    ("concert stage", "הופעה", ""), ("video game screenshot", "משחק מחשב", ""),
    ("cartoon or comic", "קומיקס", ""), ("meme funny image", "ממים", ""),
    ("baby animal cute", "חיה חמודה", "טבע וחיות"),
    ("night sky stars", "שמי לילה", "מקומות"), ("snow winter", "שלג", "מקומות"),
    ("rain storm", "גשם", "מקומות"), ("fire flames", "אש", ""),
    ("medical xray or scan", "צילום רפואי", ""), ("pills medicine", "תרופות", ""),
    ("money cash", "כסף מזומן", ""), ("flag of france", "דגל צרפת", ""),
    ("flag of israel", "דגל ישראל", ""), ("national flag", "דגל", ""),
    # french dishes and cheeses
    ("tartiflette potato gratin dish", "טרטיפלט", "אוכל"),
    ("reblochon cheese wheel", "רובלושון", "אוכל"),
    ("raclette melted cheese", "ראקלט", "אוכל"),
    ("cheese fondue pot", "פונדו גבינה", "אוכל"),
    ("quiche lorraine", "קיש", "אוכל"),
    ("croque monsieur sandwich", "קרוק מסייה", "אוכל"),
    ("ratatouille vegetable dish", "רטטוי", "אוכל"),
    ("french crepe", "קרפ", "אוכל"),
    ("macarons", "מקרון", "אוכל"),
    ("eclair pastry", "אקלר", "אוכל"),
    ("baguette bread", "באגט", "אוכל"),
    ("croissant pastry closeup", "מאפה חמאה", "אוכל"),
    ("brie or camembert cheese", "קממבר ברי", "אוכל"),
    ("blue cheese roquefort", "רוקפור", "אוכל"),
    ("goat cheese", "גבינת עיזים", "אוכל"),
    ("cheese platter", "פלטת גבינות", "אוכל"),
    ("onion soup gratinee", "מרק בצל", "אוכל"),
    ("beef bourguignon stew", "בקר בורגיניון", "אוכל"),
    ("creme brulee dessert", "קרם ברולה", "אוכל"),
    ("french toast", "פרנץ טוסט", "אוכל"),
    # israeli / middle eastern dishes
    ("shakshuka in a pan", "שקשוקה", "אוכל"),
    ("sabich or pita sandwich", "סביח פיתה", "אוכל"),
    ("jachnun with tomato", "גחנון", "אוכל"),
    ("bourekas pastry", "בורקס", "אוכל"),
    ("knafeh dessert", "כנאפה", "אוכל"),
    ("baklava dessert", "בקלאווה", "אוכל"),
    ("malabi dessert", "מלבי", "אוכל"),
    ("couscous dish", "קוסקוס", "אוכל"),
    ("stuffed vine leaves", "עלי גפן ממולאים", "אוכל"),
    ("tahini and pita", "טחינה", "אוכל"),
    # world dishes
    ("ramen noodle soup", "ראמן", "אוכל"),
    ("pad thai noodles", "פאד תאי", "אוכל"),
    ("curry with rice", "קארי", "אוכל"),
    ("tacos", "טאקו", "אוכל"),
    ("paella pan", "פאייה", "אוכל"),
    ("risotto dish", "ריזוטו", "אוכל"),
    ("gnocchi dish", "ניוקי", "אוכל"),
    ("lasagna slice", "לזניה", "אוכל"),
    ("tiramisu dessert", "טירמיסו", "אוכל"),
    ("cheesecake slice", "עוגת גבינה", "אוכל"),
    ("pancakes stack", "פנקייק", "אוכל"),
    ("waffles with toppings", "וופל בלגי", "אוכל"),
    ("dumplings or gyoza", "כיסונים", "אוכל"),
    ("poke bowl", "פוקה בול", "אוכל"),
    ("bagel sandwich", "בייגל", "אוכל"),
    ("donuts", "סופגניות דונאטס", "אוכל"),
    ("smoothie bowl", "סמוזי", "אוכל"),
    ("charcuterie board", "פלטת נקניקים", "אוכל"),
    ("grilled skewers kebab", "שיפודים", "אוכל"),
    ("seafood platter", "פירות ים", "אוכל"),
    ("oysters on ice", "צדפות", "אוכל"),
    ("tapas small plates", "טאפאס", "אוכל"),
    ("brunch table spread", "בראנץ", "אוכל"),
    ("cocktail bar drinks", "בר קוקטיילים", "אוכל"),
    ("beer glasses", "בירה", "אוכל"),
    ("espresso and pastry cafe", "בית קפה", "אוכל"),
]

proc = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")
tm = CLIPTextModelWithProjection.from_pretrained("openai/clip-vit-base-patch32")
tm.eval()
vm = CLIPVisionModelWithProjection.from_pretrained("openai/clip-vit-base-patch32")
vm.eval()

prompts = ["a photo of " + en for en, _, _ in CONCEPTS]
tin = proc(text=prompts, return_tensors="pt", padding=True)
with torch.no_grad():
    tfeat = tm(**tin).text_embeds
tfeat = tfeat / tfeat.norm(dim=-1, keepdim=True)

# reference image embedding via torch (natural-ish gradient image, fairer quantization check)
grad = np.zeros((224, 224, 3), dtype=np.uint8)
for y in range(224):
    for x in range(224):
        grad[y, x] = (x, y, (x + y) // 2)
img = Image.fromarray(grad)
pix = proc(images=img, return_tensors="pt")["pixel_values"]
with torch.no_grad():
    ref = vm(pix).image_embeds
ref = (ref / ref.norm(dim=-1, keepdim=True)).numpy()[0]

# a few varied probe images so quality is judged on more than one picture
def probe(kind):
    a = np.zeros((224, 224, 3), dtype=np.uint8)
    if kind == "gradient":
        for y in range(224):
            for x in range(224):
                a[y, x] = (x, y, (x + y) // 2)
    elif kind == "checker":
        for y in range(224):
            for x in range(224):
                a[y, x] = (255, 255, 255) if ((x // 16) + (y // 16)) % 2 else (20, 30, 60)
    elif kind == "blob":
        yy, xx = np.mgrid[0:224, 0:224]
        d = np.sqrt((xx - 112) ** 2 + (yy - 112) ** 2)
        a[..., 0] = np.clip(255 - d * 2, 0, 255)
        a[..., 1] = np.clip(d * 1.5, 0, 255)
        a[..., 2] = 128
    else:
        rng = np.random.RandomState(7)
        a = rng.randint(0, 255, (224, 224, 3), dtype=np.uint8)
    return proc(images=Image.fromarray(a), return_tensors="pt")["pixel_values"]

probes = [probe(k) for k in ("gradient", "checker", "blob", "noise")]
refs = []
for p in probes:
    with torch.no_grad():
        v = vm(p).image_embeds
    refs.append((v / v.norm(dim=-1, keepdim=True)).numpy()[0])


class VisionWrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, pixel_values):
        return self.m(pixel_values).image_embeds


wrap = VisionWrap(vm)
wrap.eval()
try:
    torch.onnx.export(
        wrap, (pix,), "vision_f32.onnx",
        input_names=["pixel_values"], output_names=["image_embeds"],
        opset_version=14, dynamo=False,
    )
except TypeError:
    torch.onnx.export(
        wrap, (pix,), "vision_f32.onnx",
        input_names=["pixel_values"], output_names=["image_embeds"],
        opset_version=14,
    )

import onnx
import onnxruntime as ort


def quality(path):
    s = ort.InferenceSession(path)
    name = s.get_inputs()[0].name
    scores = []
    for p, r in zip(probes, refs):
        o = s.run(None, {name: p.numpy()})[0][0]
        o = o / np.linalg.norm(o)
        scores.append(float(np.dot(o, r)))
    return min(scores), sum(scores) / len(scores)


# try the small one first: int8 per channel is four times lighter than fp16
from onnxruntime.quantization import quantize_dynamic, QuantType

quantize_dynamic(
    "vision_f32.onnx", "vision_int8.onnx",
    weight_type=QuantType.QInt8, per_channel=True, reduce_range=True,
)
lo8, avg8 = quality("vision_int8.onnx")
print(f"int8 quality: worst {lo8:.4f} average {avg8:.4f}")

if lo8 > 0.97:
    os.replace("vision_int8.onnx", f"{OUT}/vision.onnx")
    print("using int8 vision model")
else:
    from onnxconverter_common import float16
    m16 = float16.convert_float_to_float16(onnx.load("vision_f32.onnx"), keep_io_types=True)
    onnx.save(m16, f"{OUT}/vision.onnx")
    lo16, avg16 = quality(f"{OUT}/vision.onnx")
    print(f"fp16 quality: worst {lo16:.4f} average {avg16:.4f}")
    assert lo16 > 0.97, f"fp16 model diverged: {lo16}"
    print("using fp16 vision model")

print("vision model:", os.path.getsize(f"{OUT}/vision.onnx") // 1024 // 1024, "MB")

# ---- open vocabulary: precompute a fingerprint for every common english word ----
from wordfreq import top_n_list

phrases = [en for en, _, _ in CONCEPTS]
common = [w for w in top_n_list("en", 45000) if w.isalpha() and len(w) >= 3][:30000]
vocab, seen = [], set()
for w in phrases + common:
    if w not in seen:
        seen.add(w)
        vocab.append(w)

chunks = []
for i in range(0, len(vocab), 256):
    batch = ["a photo of " + w for w in vocab[i:i + 256]]
    bin_ = proc(text=batch, return_tensors="pt", padding=True)
    with torch.no_grad():
        bv = tm(**bin_).text_embeds
    bv = bv / bv.norm(dim=-1, keepdim=True)
    chunks.append((bv.numpy() * 127).round().clip(-127, 127).astype("int8"))
    if i % 5120 == 0:
        print("embedded", i, "/", len(vocab))

words = np.concatenate(chunks)
words.tofile(f"{OUT}/words.bin")
open(f"{OUT}/words.txt", "w", encoding="utf-8").write("\n".join(vocab))
print("vocabulary:", words.shape, os.path.getsize(f"{OUT}/words.bin") // 1024 // 1024, "MB")

json.dump(
    {"concepts": [
        {"en": e, "he": h, "cat": c, "v": [round(float(x), 5) for x in tfeat[i].tolist()]}
        for i, (e, h, c) in enumerate(CONCEPTS)
    ]},
    open(f"{OUT}/concepts.json", "w", encoding="utf-8"),
    ensure_ascii=False,
)
print("done:", os.path.getsize(f"{OUT}/vision.onnx") // 1024 // 1024, "MB,", len(CONCEPTS), "concepts")
