"""Download freely licensed landscape photos from Wikimedia Commons into mytv/theme/wallpapers.

Runs in GitHub Actions (the Commons API needs no key). Only CC0, public domain, CC BY and CC BY-SA files
are kept; each one's author and licence go to CREDITS.md, and its place to captions.txt.
"""
import json, os, re, sys, urllib.parse, urllib.request
from io import BytesIO
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), 'theme', 'wallpapers')
# (search query on Commons, caption shown on the TV, how many)
TOPICS = [
    ('"Sheikh Zayed Grand Mosque" incategory:"Quality images"', 'جامع الشيخ زايد الكبير، أبوظبي', 4),
    ('"Masjid al-Haram" incategory:"Quality images"', 'المسجد الحرام، مكة المكرمة', 3),
    ('Kaaba Mecca incategory:"Quality images"', 'الكعبة المشرفة، مكة المكرمة', 2),
    ('"Prophet\'s Mosque" incategory:"Quality images"', 'المسجد النبوي، المدينة المنورة', 3),
    ('"Masjid an-Nabawi" incategory:"Quality images"', 'المسجد النبوي، المدينة المنورة', 2),
    ('mosque sunset incategory:"Quality images"', 'مسجد عند الغروب', 2),
    ('Liwa Oasis desert dunes incategory:"Quality images"', 'صحراء ليوا، أبوظبي', 2),
    ('Jebel Hafeet incategory:"Quality images"', 'جبل حفيت، العين', 1),
    ('mountain lake landscape incategory:"Featured pictures on Wikimedia Commons"', 'منظر طبيعي', 4),
    ('waterfall forest incategory:"Featured pictures on Wikimedia Commons"', 'منظر طبيعي', 2),
    ('sunset sea coast incategory:"Featured pictures on Wikimedia Commons"', 'منظر طبيعي', 2),
]
OK_LICENCE = re.compile(r'^(CC0|Public domain|PD|CC BY(-SA)? [0-9.]+)', re.I)
UA = {'User-Agent': 'cleanyourtv-personal-theme/1.0 (github.com/Gathin23/cleanyourtv)'}


def api(params):
    url = 'https://commons.wikimedia.org/w/api.php?' + urllib.parse.urlencode(dict(params, format='json'))
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return json.load(r)


def strip_html(s):
    return re.sub(r'<[^>]+>', '', s or '').strip()


def main():
    os.makedirs(OUT, exist_ok=True)
    captions, credits, seen, n = [], [], set(), 0
    for query, caption, want in TOPICS:
        res = api({'action': 'query', 'generator': 'search', 'gsrsearch': 'filetype:bitmap ' + query,
                   'gsrnamespace': 6, 'gsrlimit': 40, 'prop': 'imageinfo',
                   'iiprop': 'url|size|extmetadata', 'iiurlwidth': 1920})
        pages = sorted(res.get('query', {}).get('pages', {}).values(), key=lambda p: p.get('index', 0))
        got = 0
        for p in pages:
            if got >= want:
                break
            title = p['title']
            if title in seen or not p.get('imageinfo'):
                continue
            ii = p['imageinfo'][0]
            w, h = ii.get('width', 0), ii.get('height', 0)
            if w < 1600 or h < 900 or w / h < 1.3 or w / h > 2.2:
                continue   # landscape only, close to the TV's 16:9
            meta = ii.get('extmetadata', {})
            licence = strip_html(meta.get('LicenseShortName', {}).get('value', ''))
            if not OK_LICENCE.match(licence):
                continue
            artist = strip_html(meta.get('Artist', {}).get('value', 'unknown'))[:120]
            try:
                req = urllib.request.Request(ii.get('thumburl') or ii['url'], headers=UA)
                with urllib.request.urlopen(req, timeout=120) as r:
                    img = Image.open(BytesIO(r.read())).convert('RGB')
            except Exception as e:
                print('skip', title, e, file=sys.stderr)
                continue
            # Cover-crop to 1920x1080.
            scale = max(1920 / img.width, 1080 / img.height)
            img = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
            left, top = (img.width - 1920) // 2, (img.height - 1080) // 2
            img = img.crop((left, top, left + 1920, top + 1080))
            n += 1
            name = f'{n:03d}.jpg'
            img.save(os.path.join(OUT, name), 'JPEG', quality=82, optimize=True, progressive=True)
            seen.add(title)
            got += 1
            captions.append(f'{name}\t{caption}')
            credits.append(f'| {name} | [{title}]({ii["descriptionurl"]}) | {artist} | {licence} |')
            print(name, title, licence)
        print(f'{query}: {got}/{want}')
    if n == 0:
        sys.exit('no wallpapers downloaded')
    with open(os.path.join(OUT, 'captions.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(captions) + '\n')
    with open(os.path.join(os.path.dirname(OUT), 'CREDITS.md'), 'w', encoding='utf-8') as f:
        f.write('# Wallpaper credits\n\nAll from Wikimedia Commons, under the licence listed.\n\n'
                '| File | Source | Author | Licence |\n|---|---|---|---|\n' + '\n'.join(credits) + '\n')


if __name__ == '__main__':
    main()
