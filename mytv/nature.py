"""24 nature photos from Wikimedia Commons' featured pictures (free licences only), 1920 px, metadata removed,
plus a numbered contact sheet to choose from. Runs in GitHub Actions; output in nature/."""
import json, os, re, sys, time, urllib.error, urllib.parse, urllib.request
from io import BytesIO
from PIL import Image, ImageDraw, ImageFont, ImageOps

OUT = 'nature'
FP = ' incategory:"Featured_pictures_on_Wikimedia_Commons"'
TOPICS = [
    ('جبال وبحيرات', ['mountain lake' + FP, 'alpine lake reflection' + FP, 'mountains valley' + FP], 8),
    ('غابات وشلالات', ['incategory:"Featured_pictures_of_waterfalls"', 'forest' + FP, 'waterfall forest' + FP], 8),
    ('بحر وشواطئ', ['beach sunset' + FP, 'coast cliffs sea' + FP, 'seascape' + FP], 8),
]
SKIP = re.compile(r'church|kirche|kerk|cathedral|chapel|temple|kapelle|église|iglesia|chiesa|people|portrait|statue|'
                  r'castle|ship|boat|train|car\b|bridge|nude|woman|man\b|girl|boy|cross|monastery|abbey', re.I)
OK_LICENCE = re.compile(r'^(CC0|Public domain|PD|CC BY(-SA)? [0-9.]+)', re.I)
UA = {'User-Agent': 'cleanyourtv-personal-theme/1.1 (https://github.com/Gathin23/cleanyourtv) python-urllib'}


def get(url):
    for attempt in range(6):
        time.sleep(1.5)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 and e.code < 500:
                raise
            time.sleep(min(int(e.headers.get('Retry-After') or 0) or 10 * (attempt + 1), 90))
    raise RuntimeError('gave up on ' + url)


def api(params):
    return json.loads(get('https://commons.wikimedia.org/w/api.php?' + urllib.parse.urlencode(dict(params, format='json'))))


def text(s):
    return re.sub(r'<[^>]+>', '', s or '').strip()


def main():
    os.makedirs(OUT, exist_ok=True)
    seen, n, credits, thumbs = set(), 0, [], []
    for label, queries, want in TOPICS:
        got = 0
        for q in queries:
            if got >= want:
                break
            res = api({'action': 'query', 'generator': 'search', 'gsrsearch': 'filetype:bitmap ' + q, 'gsrnamespace': 6,
                       'gsrlimit': 40, 'prop': 'imageinfo', 'iiprop': 'url|size|extmetadata', 'iiurlwidth': 1920})
            pages = sorted(res.get('query', {}).get('pages', {}).values(), key=lambda p: p.get('index', 0))
            for p in pages:
                if got >= want:
                    break
                title = p['title']
                if title in seen or SKIP.search(title) or not p.get('imageinfo'):
                    continue
                ii = p['imageinfo'][0]
                w, h = ii.get('width', 0), ii.get('height', 0)
                if w < 1900 or h < 1000 or not 1.4 <= w / h <= 2.0:
                    continue
                lic = text(ii.get('extmetadata', {}).get('LicenseShortName', {}).get('value', ''))
                if not OK_LICENCE.match(lic):
                    continue
                try:
                    img = ImageOps.exif_transpose(Image.open(BytesIO(get(ii.get('thumburl') or ii['url'])))).convert('RGB')
                except Exception as e:
                    print('skip', title, e, file=sys.stderr)
                    continue
                img.thumbnail((1920, 1080), Image.LANCZOS)
                n += 1
                got += 1
                seen.add(title)
                name = f'{n:02d}.jpg'
                img.save(os.path.join(OUT, name), 'JPEG', quality=85, optimize=True)   # no exif: metadata dropped
                t = img.copy(); t.thumbnail((480, 270)); thumbs.append((n, label, t))
                artist = text(ii.get('extmetadata', {}).get('Artist', {}).get('value', ''))[:100]
                credits.append(f'{name}\t{label}\t{title}\t{artist}\t{lic}\t{ii["descriptionurl"]}')
                print(name, label, title, lic)
        print(label, got)
    # contact sheet: 4 columns, numbered
    cols, tw, th = 4, 480, 270
    rows = (len(thumbs) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * (tw + 10) + 10, rows * (th + 10) + 10), (16, 16, 16))
    d = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 44)
    except Exception:
        font = ImageFont.load_default()
    for i, (num, _, t) in enumerate(thumbs):
        x, y = 10 + (i % cols) * (tw + 10), 10 + (i // cols) * (th + 10)
        sheet.paste(t, (x + (tw - t.width) // 2, y + (th - t.height) // 2))
        d.rectangle([x + 8, y + 8, x + 78, y + 64], fill=(0, 0, 0))
        d.text((x + 16, y + 10), str(num), fill=(255, 255, 255), font=font)
    sheet.save(os.path.join(OUT, 'choose.jpg'), 'JPEG', quality=82)
    with open(os.path.join(OUT, 'credits.tsv'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(credits) + '\n')


if __name__ == '__main__':
    main()
