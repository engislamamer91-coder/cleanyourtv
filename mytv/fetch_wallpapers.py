"""Download freely licensed landscape photos from Wikimedia Commons into mytv/theme/wallpapers.

Runs in GitHub Actions (the Commons API needs no key). Only CC0, public domain, CC BY and CC BY-SA files
are kept; each one's author and licence go to CREDITS.md, and its place to captions.txt.
"""
import json, os, re, sys, time, urllib.error, urllib.parse, urllib.request
from io import BytesIO
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), 'theme', 'wallpapers')
# (Commons categories to try, caption shown on the TV, how many). Only files directly in one of the
# categories are used, so a caption always matches its picture. Quality/featured files come first.
TOPICS = [
    (['Sheikh_Zayed_Grand_Mosque', 'Sheikh_Zayed_Grand_Mosque_Center', 'Sheikh_Zayed_Mosque'], 'جامع الشيخ زايد الكبير، أبوظبي', 5),
    (['Masjid_al-Haram', 'Al-Masjid_al-Haram', 'Exterior_of_Masjid_al-Haram'], 'المسجد الحرام، مكة المكرمة', 4),
    (['Kaaba'], 'الكعبة المشرفة، مكة المكرمة', 2),
    (['Al-Masjid_an-Nabawi', 'Masjid_an-Nabawi', 'Exterior_of_Al-Masjid_an-Nabawi', 'Green_Dome'], 'المسجد النبوي، المدينة المنورة', 5),
    (['Liwa_Oasis', 'Rub_al_Khali'], 'صحراء الربع الخالي', 2),
    (['Jebel_Hafeet'], 'جبل حفيت، العين', 1),
    (['Featured_pictures_of_landscapes'], 'منظر طبيعي', 6),
    (['Featured_pictures_of_waterfalls'], 'شلال', 2),
]
OK_LICENCE = re.compile(r'^(CC0|Public domain|PD|CC BY(-SA)? [0-9.]+)', re.I)
UA = {'User-Agent': 'cleanyourtv-personal-theme/1.1 (https://github.com/Gathin23/cleanyourtv; personal TV wallpapers) python-urllib'}


def get(url):
    """GET with a pause before every request and a backoff on 429/5xx (Wikimedia rate-limits CI runners)."""
    for attempt in range(6):
        time.sleep(1.5)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 and e.code < 500:
                raise
            wait = int(e.headers.get('Retry-After') or 0) or 10 * (attempt + 1)
            print(f'HTTP {e.code}, waiting {wait}s', file=sys.stderr)
            time.sleep(min(wait, 90))
    raise RuntimeError('gave up on ' + url)


def api(params):
    url = 'https://commons.wikimedia.org/w/api.php?' + urllib.parse.urlencode(dict(params, format='json'))
    return json.loads(get(url))


def strip_html(s):
    return re.sub(r'<[^>]+>', '', s or '').strip()


def main():
    os.makedirs(OUT, exist_ok=True)
    captions, credits, seen, n = [], [], set(), 0
    for cats, caption, want in TOPICS:
        pages = []
        for cat in cats:
            for extra in (' incategory:"Quality_images"', ' incategory:"Featured_pictures_on_Wikimedia_Commons"', ''):
                try:
                    res = api({'action': 'query', 'generator': 'search',
                               'gsrsearch': f'filetype:bitmap incategory:"{cat}"' + extra,
                               'gsrnamespace': 6, 'gsrlimit': 30, 'prop': 'imageinfo',
                               'iiprop': 'url|size|extmetadata', 'iiurlwidth': 1920})
                except Exception as e:
                    print('query failed', cat, extra, e, file=sys.stderr)
                    continue
                found = sorted(res.get('query', {}).get('pages', {}).values(), key=lambda p: p.get('index', 0))
                print(f'  {cat}{extra}: {len(found)} files')
                pages += found
        query = '/'.join(cats)
        got = 0
        for p in pages:
            if got >= want:
                break
            title = p['title']
            if title in seen or not p.get('imageinfo'):
                continue
            if re.search(r'church|kirche|kerk|cathedral|chapel|temple|kapelle|église|iglesia|chiesa|people|selfie', title, re.I):
                continue   # keep the collection to mosques and nature
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
                img = Image.open(BytesIO(get(ii.get('thumburl') or ii['url']))).convert('RGB')
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
    print(f'{n} wallpapers')
    with open(os.path.join(OUT, 'captions.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(captions) + '\n')
    with open(os.path.join(os.path.dirname(OUT), 'CREDITS.md'), 'w', encoding='utf-8') as f:
        f.write('# Wallpaper credits\n\nAll from Wikimedia Commons, under the licence listed.\n\n'
                '| File | Source | Author | Licence |\n|---|---|---|---|\n' + '\n'.join(credits) + '\n')


if __name__ == '__main__':
    main()
