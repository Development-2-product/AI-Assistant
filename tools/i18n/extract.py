"""Kotlin string-literal scanner + i18n transformer for IA Mode.
Finds user-visible "..." literals (with ${} templates) and rewrites them to tr("...", args)."""
import re, sys, json, os, hashlib

import pathlib
ROOT = str(pathlib.Path(__file__).resolve().parents[2] / 'frontend/app/src/main/java/com/iamode/app') + '/'
TARGETS = ['ui/', 'core/notifications/AppNotifier.kt', 'data/jobs/JobReminderWorker.kt', 'data/mail/MailboxActions.kt']
SKIP_FILES = ['ui/navigation/AppNavHost.kt', 'ui/theme/', 'ui/components/SharedTransitions.kt', 'ui/celebration/ParticleEngine3D.kt']

# contexts (text right before the literal) where strings are technical, never shown
SKIP_BEFORE = re.compile(r'(ofPattern|SimpleDateFormat|Regex|Uri\.parse|parse|putExtra|getStringExtra|get<String>|navArgument|log\.record|record|testTag|'
                         r'rememberSharedContentState|sharedTransition|Entrance|stringPreferencesKey|booleanPreferencesKey|'
                         r'@Suppress|@OptIn|OptIn|Intent|IntentSenderRequest|getLaunchIntentForPackage|setType|ACTION|label =|'
                         r'key =|hashCode|contentType|format|startsWith|endsWith|contains|replace|split|substringAfter|'
                         r'substringBefore|equals|\.name ==|saved\[|savedStateHandle|require|check|error|TODO|println|Log\.[dewi])\s*\(?\s*$')

def scan(src):
    """Yield (start, end, parts) for each non-raw string literal. parts = list of ('t', text) | ('e', expr) | ('v', ident)."""
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3
            while i < n and src[i] == '"': i += 1
            continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1; continue
        if c == '"':
            start = i; i += 1; parts = []; buf = []; ok = True
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    buf.append(src[i:i+2]); i += 2; continue
                if src[i] == '\n': ok = False; break
                if src[i] == '$' and i + 1 < n and src[i+1] == '{':
                    if buf: parts.append(('t', ''.join(buf))); buf = []
                    depth = 1; j = i + 2
                    while j < n and depth:
                        if src[j] == '{': depth += 1
                        elif src[j] == '}': depth -= 1
                        elif src[j] == '"': parts.append(('nested', '')); 
                        j += 1
                    parts.append(('e', src[i+2:j-1])); i = j; continue
                if src[i] == '$' and i + 1 < n and (src[i+1].isalpha() or src[i+1] == '_'):
                    if buf: parts.append(('t', ''.join(buf))); buf = []
                    j = i + 1
                    while j < n and (src[j].isalnum() or src[j] == '_'): j += 1
                    parts.append(('v', src[i+1:j])); i = j; continue
                buf.append(src[i]); i += 1
            if buf: parts.append(('t', ''.join(buf)))
            end = i + 1
            if ok: yield start, end, parts
            i = end; continue
        i += 1

def visible_text(parts):
    return ''.join(p[1] if p[0] == 't' else ' X ' for p in parts)

def is_user_facing(parts, before):
    if any(p[0] == 'nested' for p in parts): return False
    text = visible_text(parts)
    letters = re.findall(r'[A-Za-z]{2,}', text)
    if not letters: return False
    t = ''.join(p[1] for p in parts if p[0] == 't')
    if re.fullmatch(r'[A-Z0-9_]+', t.strip()): return False            # ALL_CAPS identifiers / statuses
    if re.fullmatch(r'[a-z0-9_.\-/{}:?=&%]+', t.strip()): return False   # keys, routes, ids, mime-ish
    if any(x in t for x in ['://', 'http', '@', '\\', 'application/', 'image/', 'text/', 'content:', 'file:', '#']): return False
    if '%' in t: return False
    if t.startswith('IA Mode/'): return False  # Gmail label / Outlook category names must stay fixed
    if not (' ' in t.strip() or t.strip()[:1].isupper() or re.search(r'[^\x00-\x7F]', t)): return False
    if SKIP_BEFORE.search(before[-60:]): return False
    return True

def to_format(parts):
    fmt, args, k = [], [], 0
    for kind, v in parts:
        if kind == 't': fmt.append(v)
        else:
            k += 1; fmt.append('%' + str(k) + '$s'); args.append(v)
    return ''.join(fmt), args

def key_for(fmt):
    words = re.findall(r'[A-Za-z]+', fmt.lower())[:6]
    base = '_'.join(words) or 'text'
    return 's_' + base[:48] + '_' + hashlib.md5(fmt.encode()).hexdigest()[:5]

def frozen_spans(src):
    """Enum bodies and top-level vals are evaluated once; their texts are translated where they're displayed."""
    spans = []
    for m in re.finditer(r'^(?:private |internal )?enum class [^{]*\{', src, re.M):
        depth, j = 1, m.end()
        while j < len(src) and depth:
            if src[j] == '{': depth += 1
            elif src[j] == '}': depth -= 1
            j += 1
        spans.append((m.start(), j))
    for m in re.finditer(r'^(?:private |internal )?val \w+(?:: [^=]+)? = (?:listOf|mapOf|setOf)\(', src, re.M):
        depth, j = 1, m.end()
        while j < len(src) and depth:
            if src[j] == '(': depth += 1
            elif src[j] == ')': depth -= 1
            j += 1
        spans.append((m.start(), j))
    return spans

def process(path, apply=False, table=None):
    src = open(path).read()
    frozen = frozen_spans(src)
    head, sep, body = src.partition('\n\n')  # package line
    out, last, count, skipped_nested = [], 0, 0, 0
    for s, e, parts in scan(src):
        before = src[max(0, s-80):s]
        # skip import/package lines and annotation args
        line_start = src.rfind('\n', 0, s) + 1
        line = src[line_start:s]
        if line.lstrip().startswith(('import ', 'package ', '@')): continue
        if any(a <= s < b for a, b in frozen): continue
        if before.rstrip().endswith('tr('): continue
        if not is_user_facing(parts, before):
            if any(p[0]=='nested' for p in parts): skipped_nested += 1
            continue
        fmt, args = to_format(parts)
        fmt_xml = fmt
        if table is not None: table.setdefault(fmt, key_for(fmt))
        if apply:
            lit = '"' + fmt.replace('$s', '\\$s') + '"' if args else src[s:e]
            # Kotlin literal for the format: "%1\$s" must escape $ in Kotlin source
            if args:
                lit = '"' + ''.join(p[1] if p[0]=='t' else '' for p in []) + '"'
                pieces, k = [], 0
                for kind, v in parts:
                    if kind == 't': pieces.append(v)
                    else: k += 1; pieces.append('%' + str(k) + '\\$s')
                lit = '"' + ''.join(pieces) + '"'
                call = 'tr(' + lit + ', ' + ', '.join('(' + a + ')' if not re.fullmatch(r'\w+', a) else a for a in args) + ')'
            else:
                call = 'tr(' + src[s:e] + ')'
            out.append(src[last:s]); out.append(call); last = e
        count += 1
    if apply:
        out.append(src[last:])
        new = ''.join(out)
        if count and 'import com.iamode.app.core.i18n.tr' not in new:
            new = re.sub(r'^(package [\w.]+\n)', r'\1\nimport com.iamode.app.core.i18n.tr\n', new, count=1, flags=re.M)
        open(path, 'w').write(new)
    return count, skipped_nested

def files():
    for t in TARGETS:
        p = ROOT + t
        if os.path.isfile(p): yield p
        else:
            for d, _, fs in os.walk(p):
                for f in fs:
                    fp = os.path.join(d, f)
                    if f.endswith('.kt') and not any(x in fp for x in SKIP_FILES): yield fp

if __name__ == '__main__':
    apply = '--apply' in sys.argv
    table = {}
    tot = nested = 0
    for f in files():
        c, sn = process(f, apply, table); tot += c; nested += sn
    json.dump(table, open('' + str(pathlib.Path(__file__).with_name('table.json')) + '', 'w'), ensure_ascii=False, indent=0)
    print('literals', tot, 'unique', len(table), 'skipped-with-nested-quotes', nested)
