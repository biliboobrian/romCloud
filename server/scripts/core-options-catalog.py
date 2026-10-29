"""Catalogue des options des cœurs libretro livré avec le serveur (src/core-options-builtin.json).

L'administration propose ces options dans les réglages d'un système et la fiche d'un jeu, même
quand aucune application n'a encore lancé le cœur (le catalogue envoyé par les applications,
/api/account/core-options, garde la priorité : il suit la version du cœur installée).

Cœurs : ceux de la table du moteur intégré (desktop-tauri/src-tauri/src/cores.rs) et ceux des
modèles d'émulateurs des plateformes locales (platforms/*.json). Chaque cœur est téléchargé depuis le
buildbot libretro pour Windows, chargé dans un processus à part (un cœur peut planter) et interrogé :
options déclarées dans retro_set_environment (sinon retro_init), libellés anglais et français.

Usage (Windows, Python 64 bits) : python scripts/core-options-catalog.py <dossier des téléchargements>
"""
import ctypes
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
OUTPUT = os.path.join(ROOT, 'src', 'core-options-builtin.json')
CORES_RS = os.path.join(REPO, 'desktop-tauri', 'src-tauri', 'src', 'cores.rs')
BUILDBOT = 'https://buildbot.libretro.com/nightly/windows/x86_64/latest'

# ---- Interrogation d'un cœur (processus à part) ----

SET_VARIABLES = 16
GET_SYSTEM_DIRECTORY = 9
GET_SAVE_DIRECTORY = 31
GET_LANGUAGE = 39
GET_CORE_OPTIONS_VERSION = 52
SET_CORE_OPTIONS = 53
SET_CORE_OPTIONS_INTL = 54
SET_CORE_OPTIONS_DISPLAY = 55
SET_CORE_OPTIONS_V2 = 67
SET_CORE_OPTIONS_V2_INTL = 68
SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK = 69
LANGUAGE_FRENCH = 2
MAX_VALUES = 128

c_str = ctypes.c_char_p


class Variable(ctypes.Structure):
    _fields_ = [('key', c_str), ('value', c_str)]


class Value(ctypes.Structure):
    _fields_ = [('value', c_str), ('label', c_str)]


class Definition(ctypes.Structure):
    _fields_ = [('key', c_str), ('desc', c_str), ('info', c_str), ('values', Value * MAX_VALUES), ('default_value', c_str)]


class Intl(ctypes.Structure):
    _fields_ = [('us', ctypes.POINTER(Definition)), ('local', ctypes.POINTER(Definition))]


class Category(ctypes.Structure):
    _fields_ = [('key', c_str), ('desc', c_str), ('info', c_str)]


class DefinitionV2(ctypes.Structure):
    _fields_ = [
        ('key', c_str), ('desc', c_str), ('desc_categorized', c_str), ('info', c_str), ('info_categorized', c_str),
        ('category_key', c_str), ('values', Value * MAX_VALUES), ('default_value', c_str),
    ]


class OptionsV2(ctypes.Structure):
    _fields_ = [('categories', ctypes.POINTER(Category)), ('definitions', ctypes.POINTER(DefinitionV2))]


class IntlV2(ctypes.Structure):
    _fields_ = [('us', ctypes.POINTER(OptionsV2)), ('local', ctypes.POINTER(OptionsV2))]


def text(raw):
    return raw.decode('utf-8', 'replace') if raw else ''


def each(pointer):
    """Éléments d'un tableau C terminé par une entrée sans clé."""
    i = 0
    while pointer and pointer[i].key:
        yield pointer[i]
        i += 1


def values_of(definition):
    result = []
    for v in definition.values:
        if not v.value:
            break
        result.append({'value': text(v.value), 'label': text(v.label) or text(v.value)})
    return result


def from_v1(us, local):
    fr = {text(d.key): d for d in each(local)}
    options = []
    for d in each(us):
        option = {'key': text(d.key), 'label': text(d.desc), 'default': text(d.default_value), 'values': values_of(d)}
        if d.info:
            option['info'] = text(d.info)
        translate(option, fr.get(option['key']), 'desc', 'info')
        options.append(option)
    return options


def from_v2(us, local):
    categories = {text(c.key): c for c in each(us.contents.categories)} if us.contents.categories else {}
    categories_fr = {text(c.key): c for c in each(local.contents.categories)} if local and local.contents.categories else {}
    fr = {text(d.key): d for d in each(local.contents.definitions)} if local else {}
    options = []
    for d in each(us.contents.definitions):
        # Libellé « catégorisé » (court, sous le nom de la catégorie) quand la catégorie est affichée.
        category = categories.get(text(d.category_key))
        option = {'key': text(d.key), 'label': text(d.desc_categorized if category and d.desc_categorized else d.desc),
                  'default': text(d.default_value), 'values': values_of(d)}
        info = d.info_categorized if category and d.info_categorized else d.info
        if info:
            option['info'] = text(info)
        if category:
            option['category'] = text(category.desc)
            other = categories_fr.get(text(d.category_key))
            if other and other.desc and text(other.desc) != option['category']:
                option['categoryFr'] = text(other.desc)
        local_def = fr.get(option['key'])
        if local_def:
            translate(option, local_def, 'desc_categorized' if category and local_def.desc_categorized else 'desc',
                      'info_categorized' if category and local_def.info_categorized else 'info')
        options.append(option)
    return options


def translate(option, local, desc, info):
    """Libellés français donnés par le cœur (différents des anglais seulement)."""
    if not local:
        return
    label = text(getattr(local, desc))
    if label and label != option['label']:
        option['labelFr'] = label
    information = text(getattr(local, info))
    if information and information != option.get('info'):
        option['infoFr'] = information
    labels = {v['value']: text(v.label) for v in values_of_raw(local)}
    for v in option['values']:
        other = labels.get(v['value'])
        if other and other != v['label']:
            v['labelFr'] = other


def values_of_raw(definition):
    for v in definition.values:
        if not v.value:
            break
        yield {'value': text(v.value), 'label': v.label}


def from_variables(pointer):
    options = []
    for v in each(pointer):
        raw = text(v.value)
        desc, _, choices = raw.partition('; ')
        values = [{'value': c, 'label': c} for c in choices.split('|') if c]
        options.append({'key': text(v.key), 'label': desc, 'default': values[0]['value'] if values else '', 'values': values})
    return options


def dump(dll, out):
    found = []
    work = tempfile.mkdtemp().encode()
    work_ptr = c_str(work)

    @ctypes.CFUNCTYPE(ctypes.c_bool, ctypes.c_uint, ctypes.c_void_p)
    def environment(cmd, data):
        cmd &= 0xFFFF  # RETRO_ENVIRONMENT_EXPERIMENTAL
        try:
            if cmd == GET_CORE_OPTIONS_VERSION:
                ctypes.cast(data, ctypes.POINTER(ctypes.c_uint))[0] = 2
                return True
            if cmd == GET_LANGUAGE:
                ctypes.cast(data, ctypes.POINTER(ctypes.c_uint))[0] = LANGUAGE_FRENCH
                return True
            if cmd in (GET_SYSTEM_DIRECTORY, GET_SAVE_DIRECTORY):
                ctypes.cast(data, ctypes.POINTER(ctypes.c_void_p))[0] = ctypes.cast(work_ptr, ctypes.c_void_p).value
                return True
            if cmd == SET_CORE_OPTIONS_V2_INTL:
                intl = ctypes.cast(data, ctypes.POINTER(IntlV2)).contents
                found[:] = from_v2(intl.us, intl.local if intl.local else None)
                return True
            if cmd == SET_CORE_OPTIONS_V2:
                found[:] = from_v2(ctypes.cast(data, ctypes.POINTER(OptionsV2)), None)
                return True
            if cmd == SET_CORE_OPTIONS_INTL:
                intl = ctypes.cast(data, ctypes.POINTER(Intl)).contents
                found[:] = from_v1(intl.us, intl.local)
                return True
            if cmd == SET_CORE_OPTIONS:
                found[:] = from_v1(ctypes.cast(data, ctypes.POINTER(Definition)), None)
                return True
            if cmd == SET_VARIABLES:
                found[:] = from_variables(ctypes.cast(data, ctypes.POINTER(Variable)))
                return True
            if cmd in (SET_CORE_OPTIONS_DISPLAY, SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK):
                return True
        except Exception as error:  # noqa: BLE001 — un cœur ne doit pas arrêter l'interrogation
            print(f'environment {cmd}: {error}', file=sys.stderr)
        return False

    os.add_dll_directory(os.path.dirname(os.path.abspath(dll)))
    core = ctypes.CDLL(os.path.abspath(dll))
    core.retro_set_environment(environment)
    if not found:
        core.retro_init()
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(found, f, ensure_ascii=False)
    sys.stdout.flush()
    os._exit(0)  # sans retro_deinit ni déchargement : certains cœurs plantent en quittant


# ---- Liste des cœurs, téléchargements, catalogue ----

def ranking():
    """Systèmes de chaque cœur dans la table du moteur intégré (cores.rs) : { système: rang }."""
    source = open(CORES_RS, encoding='utf-8').read()
    constants = {m[1]: re.findall(r'"([^"]+)"', m[2]) for m in re.finditer(r'const (\w+): &\[&str\] = &\[([^\]]*)\]', source)}
    body = source[source.index('fn ranking'):source.index('_ => &[]')]
    systems = {}
    for m in re.finditer(r'((?:"[\w-]+"\s*\|?\s*)+)=>\s*(?:&\[([^\]]*)\]|(\w+))', body):
        ids = re.findall(r'"([\w-]+)"', m[1])
        cores = re.findall(r'"([^"]+)"', m[2]) if m[2] is not None else constants[m[3]]
        for rank, core in enumerate(cores):
            for system in ids:
                systems.setdefault(core, {})[system] = rank
    return systems


def platform_cores():
    """Cœurs des modèles d'émulateurs des plateformes locales (platforms/*.json) : { système }."""
    systems = {}
    folder = os.path.join(ROOT, 'platforms')
    for name in os.listdir(folder):
        data = json.load(open(os.path.join(folder, name), encoding='utf-8'))
        system = (data.get('platform') or {}).get('uniqueId', '').lower()
        for player in data.get('playerList') or []:
            for core in re.findall(r'([\w.+-]+)_libretro_android\.so', player.get('amStartArguments') or ''):
                if system:
                    systems.setdefault(core, set()).add(system)
    return systems


def download(core, folder):
    # Cœurs propres à Android (« mupen64plus_next_gles3 ») : mêmes options que le cœur de Windows.
    name = re.sub(r'_gles[23]$', '', core)
    dll = os.path.join(folder, f'{name}_libretro.dll')
    if os.path.isfile(dll):
        return dll
    try:
        with urllib.request.urlopen(f'{BUILDBOT}/{name}_libretro.dll.zip', timeout=600) as response:
            archive = zipfile.ZipFile(io.BytesIO(response.read()))
    except Exception as error:  # noqa: BLE001
        print(f'  {name} : téléchargement impossible ({error})')
        return None
    archive.extract(f'{name}_libretro.dll', folder)
    return dll


def main(folder):
    os.makedirs(folder, exist_ok=True)
    systems = ranking()
    for core, ids in platform_cores().items():
        for system in ids:
            systems.setdefault(core, {}).setdefault(system, 99)
    previous = json.load(open(OUTPUT, encoding='utf-8')) if os.path.isfile(OUTPUT) else {}
    cores = {}
    for core in sorted(systems):
        print(core)
        dll = download(core, folder)
        options = None
        if dll:
            out = os.path.join(folder, f'{core}.json')
            if os.path.exists(out):
                os.remove(out)
            try:
                subprocess.run([sys.executable, '-I', os.path.abspath(__file__), '--dump', dll, out], cwd=folder,
                               timeout=120, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            except subprocess.TimeoutExpired:
                print('  délai dépassé')
            if os.path.isfile(out):
                options = json.load(open(out, encoding='utf-8'))
        if options is None and core in previous.get('cores', {}):
            print('  options précédentes gardées')
            options = previous['cores'][core]['options']
        if not options:
            print('  aucune option')
            continue
        print(f'  {len(options)} options')
        cores[core] = {'systems': dict(sorted(systems[core].items())), 'options': options}
    with open(OUTPUT, 'w', encoding='utf-8', newline='\n') as f:
        json.dump({'source': BUILDBOT, 'cores': cores}, f, ensure_ascii=False, indent=1)
        f.write('\n')
    print(f'{len(cores)} cœurs -> {OUTPUT}')


if __name__ == '__main__':
    if len(sys.argv) == 4 and sys.argv[1] == '--dump':
        dump(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 2:
        main(sys.argv[1])
    else:
        print(__doc__)
        sys.exit(2)
