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

# export vision encoder with projection to onnx
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

from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic("vision_f32.onnx", f"{OUT}/vision.onnx", weight_type=QuantType.QUInt8)

# verify quantized model matches torch
import onnxruntime as ort
s = ort.InferenceSession(f"{OUT}/vision.onnx")
out = s.run(None, {s.get_inputs()[0].name: pix.numpy()})[0][0]
out = out / np.linalg.norm(out)
cos = float(np.dot(out, ref))
print("quantized-vs-torch cosine:", cos)
assert cos > 0.92, f"quantized model diverged: {cos}"

json.dump(
    {"concepts": [
        {"en": e, "he": h, "cat": c, "v": [round(float(x), 5) for x in tfeat[i].tolist()]}
        for i, (e, h, c) in enumerate(CONCEPTS)
    ]},
    open(f"{OUT}/concepts.json", "w", encoding="utf-8"),
    ensure_ascii=False,
)
print("done:", os.path.getsize(f"{OUT}/vision.onnx") // 1024 // 1024, "MB,", len(CONCEPTS), "concepts")
