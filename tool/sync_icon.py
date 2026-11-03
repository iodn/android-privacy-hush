"""Convert the bundled SVG paths to Android vectors; preserve geometry and fills."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'android/app/src/main/res'
ANDROID = 'http://schemas.android.com/apk/res/android'
ICON_BACKGROUND = '#000000'
ET.register_namespace('android', ANDROID)

def attr(name):
    return f'{{{ANDROID}}}{name}'

paths = []

def walk(node, fill='#000000'):
    fill = node.get('fill', fill)
    kind = node.tag.split('}')[-1]
    if kind == 'path':
        paths.append((node.attrib['d'], fill))
    elif kind in ('circle', 'ellipse'):
        x, y = float(node.get('cx')), float(node.get('cy'))
        rx = float(node.get('r') or node.get('rx'))
        ry = float(node.get('r') or node.get('ry'))
        paths.append((f'M{x-rx},{y} a{rx},{ry} 0,1 0 {2*rx},0 a{rx},{ry} 0,1 0 {-2*rx},0 Z', fill))
    for child in node:
        walk(child, fill)

walk(ET.parse(ROOT / 'assets/monkey.svg').getroot())

def vector(padded=False, monochrome=False, background=False):
    size = '108' if padded else '64'
    root = ET.Element('vector', {attr('width'): '108dp' if padded else '24dp', attr('height'): '108dp' if padded else '24dp', attr('viewportWidth'): size, attr('viewportHeight'): size})
    if background:
        ET.SubElement(root, 'path', {attr('fillColor'): ICON_BACKGROUND, attr('pathData'): 'M0,0 H108 V108 H0 Z'})
    group = ET.SubElement(root, 'group', {attr('translateX'): '27.12', attr('translateY'): '27.12', attr('scaleX'): '0.84', attr('scaleY'): '0.84'}) if padded else root
    for data, fill in paths:
        ET.SubElement(group, 'path', {attr('fillColor'): '#FFFFFF' if monochrome else fill, attr('pathData'): data})
    ET.indent(root)
    return ET.tostring(root, encoding='unicode')

(RES / 'drawable/hush_mark.xml').write_text(vector(padded=True))
(RES / 'drawable/hush_monkey.xml').write_text(vector())
(RES / 'drawable/ic_privacy_shield.xml').write_text(vector(monochrome=True))
(RES / 'mipmap-anydpi/ic_launcher_foreground.xml').write_text(vector(padded=True))
for name in ['ic_launcher', 'ic_launcher_round']:
    (RES / f'mipmap-anydpi/{name}.xml').write_text(vector(padded=True, background=True))
    (RES / f'mipmap-anydpi-v26/{name}.xml').write_text('<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/icon_background"/><foreground android:drawable="@drawable/hush_mark"/></adaptive-icon>')
(RES / 'values/ic_launcher_background.xml').write_text(f'<resources><color name="icon_background">{ICON_BACKGROUND}</color></resources>')
