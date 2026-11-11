from pathlib import Path
from xml.etree import ElementTree
import resvg_py

folder = Path('docs/design/bottom-navigation')
icons = {
    'browse': '<circle cx="12" cy="12" r="8.75"/><ellipse cx="12" cy="12" rx="4" ry="8.75"/><path d="M3.25 12h17.5"/>',
    'downloading': '<circle cx="12" cy="12" r="8.75"/><path d="M12 7.5v9m-3.5-3.5 3.5 3.5 3.5-3.5"/>',
    'downloaded': '<path d="M3.25 8V6.75a2 2 0 0 1 2-2h3.3a2 2 0 0 1 1.42.59l1.07 1.07a2 2 0 0 0 1.42.59h6.29a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5.25a2 2 0 0 1-2-2V8Z"/><path d="m8.25 13.4 2.6 2.6 4.9-4.9"/>',
}
for name, body in icons.items():
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><g fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{body}</g></svg>\n'
    (folder / f'{name}.svg').write_text(svg, encoding='utf-8')
    ElementTree.fromstring(svg)

parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="1050" viewBox="0 0 1200 1050">', '<rect width="1200" height="1050" fill="#F5F5F3"/>']
def text(x, y, value, size=18, color='#25262A', weight=400, anchor='start'):
    parts.append(f'<text x="{x}" y="{y}" font-family="Microsoft YaHei, sans-serif" font-size="{size}" fill="{color}" font-weight="{weight}" text-anchor="{anchor}">{value}</text>')
def icon(name, x, y, size, color):
    parts.append(f'<g transform="translate({x} {y}) scale({size/24})" fill="none" color="{color}" stroke="{color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{icons[name]}</g>')
text(56, 75, 'PDownloader', 38, weight=600)
text(56, 115, '底部导航 · SVG 图标方案', 21, '#707176')
text(1144, 76, '设计预览  /  02', 15, '#818286', anchor='end')
parts.append('<path d="M56 149H1144" stroke="#DDDEDA"/>')
for x, name, label, meaning in [(238,'browse','浏览','地球 · 经纬线'),(600,'downloading','下载中','闭合圆环 · 下载箭头'),(962,'downloaded','已下载','文件夹 · 完成勾选')]:
    icon(name, x-60, 211, 120, '#34363A')
    text(x, 379, label, 24, weight=500, anchor='middle')
    text(x, 411, meaning, 16, '#808186', anchor='middle')
text(56, 479, '底栏实际效果', 23, weight=500)
text(56, 511, '每行演示一个选中状态；应用底栏不显示文字。', 16, '#797A80')
text(56, 559, '浅色模式', 15, '#797A80')
text(632, 559, '深色模式', 15, '#797A80')
for row in range(3):
    for dark, x in [(False,56),(True,632)]:
        y = 579 + row * 126
        bg, line, muted, selected, pill = ('#1E2025','#303238','#A6A9B1','#FFB4A7','#4E2E2B') if dark else ('#FFFFFF','#E6E7E4','#73767F','#C33425','#FBE5E0')
        parts.append(f'<rect x="{x}" y="{y}" width="512" height="106" rx="18" fill="{bg}" stroke="{line}"/>')
        for i, name in enumerate(icons):
            cx, cy = x + 512*(i+.5)/3, y+44
            if i == row:
                parts.append(f'<rect x="{cx-33}" y="{cy-22}" width="66" height="44" rx="22" fill="{pill}"/>')
            icon(name,cx-16,cy-16,32,selected if i==row else muted)
        parts.append(f'<rect x="{x+206}" y="{y+89}" width="100" height="4" rx="2" fill="{muted}" opacity=".45"/>')
text(56, 1018, '24 × 24 网格   ·   1.8px 圆角描边   ·   统一视觉大小', 15, '#808186')
text(1144, 1018, '已确认 · 应用于 0.3.9', 15, '#808186', anchor='end')
parts.append('</svg>')
svg = '\n'.join(parts)
ElementTree.fromstring(svg)
(folder / 'preview.svg').write_text(svg, encoding='utf-8')
(folder / 'preview.png').write_bytes(resvg_py.svg_to_bytes(svg_string=svg, font_files=['C:/Windows/Fonts/msyh.ttc'], skip_system_fonts=True))
print('Created 3 SVG icons and light/dark preview.')
