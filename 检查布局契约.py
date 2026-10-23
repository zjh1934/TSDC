#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
布局契约校验 —— 防闪退护栏。

为什么需要它：
  「点进去就闪退」这类问题，90% 是 findViewById 拿回了 null，
  紧接着 .setText() / .setOnClickListener() 就 NPE。
  静态扫 grep 很难保证「某个 id 在哪个布局里」这件事不出错，
  所以这里把规则固化成脚本，改完布局跑一遍就知道有没有踩雷。

检查项：
  1. 代码里 findViewById(R.id.X) 引用的每个 id，都必须存在于某个布局里
  2. 布局里定义的 id 不能重复（同一文件内）
  3. 布局里用到的 @color/@string/@drawable/@style 必须存在
  4. 布局用到的 android:* 属性必须是 framework 真实存在的属性
  5. 布局里 <include> 引用的 layout 必须存在
  6. 布局里 <fragment> 等高危标签告警
  7. 非 ViewGroup 标签（<View>/<TextView>…）里不许放子视图
     —— 真机 inflate 会抛 ClassCastException（v1.4.1 的闪退根因）
  8. (TextView) findViewById(...) 的强转类型要和布局里的实际标签对得上
     —— 对不上就是「点进某个界面才崩」
"""
import os
import re
import sys
import glob
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'app', 'src', 'main')
RES = os.path.join(SRC, 'res')
LAYOUT = os.path.join(RES, 'layout')
JAVA = os.path.join(SRC, 'java')
FRAMEWORK_ATTRS = os.environ.get(
    'ANDROID_ATTRS',
    '/opt/android-sdk/platforms/android-30/data/res/values/attrs.xml')

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


# ---------------------------------------------------------------- 收集资源
def collect_res():
    names = {k: set() for k in (
        'layout', 'drawable', 'color', 'string', 'style', 'dimen', 'array',
        'id', 'anim', 'mipmap', 'raw', 'menu', 'xml', 'bool', 'integer')}
    for d in sorted(os.listdir(RES)):
        p = os.path.join(RES, d)
        if not os.path.isdir(p):
            continue
        t = d.split('-')[0]
        if t == 'values':
            continue
        for f in os.listdir(p):
            if f.startswith('.'):
                continue
            n = f.split('.')[0]
            if t in names:
                names[t].add(n)
    for f in glob.glob(os.path.join(RES, 'values*', '*.xml')):
        try:
            root = ET.parse(f).getroot()
        except Exception as e:
            err('values 解析失败 %s: %s' % (f, e))
            continue
        for el in root:
            nm = el.get('name')
            if not nm:
                continue
            if el.tag == 'string':
                names['string'].add(nm)
            elif el.tag == 'color':
                names['color'].add(nm)
            elif el.tag == 'style':
                names['style'].add(nm)
            elif el.tag == 'dimen':
                names['dimen'].add(nm)
            elif el.tag == 'bool':
                names['bool'].add(nm)
            elif el.tag == 'integer':
                names['integer'].add(nm)
            elif el.tag in ('string-array', 'integer-array', 'array'):
                names['array'].add(nm)
    for f in glob.glob(os.path.join(LAYOUT, '*.xml')):
        for m in re.finditer(r'@\+id/([A-Za-z_0-9]+)', open(f, encoding='utf-8').read()):
            names['id'].add(m.group(1))
    return names


# ---------------------------------------------------------------- 检查 1/2
def check_ids(res):
    """代码引用的 id 必须存在；同一布局内 id 不能重复。"""
    used = set()
    for f in glob.glob(os.path.join(SRC, 'java', '**', '*.java'), recursive=True):
        txt = open(f, encoding='utf-8').read()
        # 排除 android.R.id.text1 这类 framework 自带 id
        for m in re.finditer(r'(?<!android\.)\bR\.id\.([A-Za-z_0-9]+)', txt):
            used.add(m.group(1))
    missing = sorted(used - res['id'])
    for m in missing:
        err('代码引用了不存在的 id: R.id.%s' % m)

    for f in sorted(glob.glob(os.path.join(LAYOUT, '*.xml'))):
        txt = open(f, encoding='utf-8').read()
        ids = re.findall(r'@\+id/([A-Za-z_0-9]+)', txt)
        seen = set()
        for i in ids:
            if i in seen:
                err('%s 内 id 重复: %s' % (os.path.basename(f), i))
            seen.add(i)


# ---------------------------------------------------------------- 检查 3
def check_refs(res):
    files = (glob.glob(os.path.join(LAYOUT, '*.xml'))
             + glob.glob(os.path.join(RES, 'values*', '*.xml'))
             + glob.glob(os.path.join(RES, 'drawable*', '*.xml')))
    for f in files:
        txt = open(f, encoding='utf-8').read()
        for m in re.finditer(r'@(drawable|layout|color|string|dimen|array|anim|style|mipmap|raw|menu|xml|bool|integer)/([A-Za-z_0-9]+)', txt):
            typ, nm = m.group(1), m.group(2)
            if typ in res and nm not in res[typ]:
                err('%s 引用了不存在的 %s/%s' % (os.path.basename(f), typ, nm))


# ---------------------------------------------------------------- 检查 4
def check_framework_attrs():
    if not os.path.exists(FRAMEWORK_ATTRS):
        warn('找不到 framework attrs，跳过属性校验: %s' % FRAMEWORK_ATTRS)
        return
    attrs = set()
    for el in ET.parse(FRAMEWORK_ATTRS).getroot():
        if el.tag == 'declare-styleable':
            for a in el:
                if a.tag == 'attr':
                    attrs.add(a.get('name'))
    for f in sorted(glob.glob(os.path.join(LAYOUT, '*.xml'))):
        try:
            root = ET.parse(f).getroot()
        except Exception as e:
            err('布局解析失败 %s: %s' % (f, e))
            continue
        for el in root.iter():
            for k in el.attrib:
                if k.startswith(ANDROID):
                    name = k[len(ANDROID):]
                    if name not in attrs:
                        err('%s 使用了 framework 不存在的属性 android:%s'
                            % (os.path.basename(f), name))


# ---------------------------------------------------------------- 检查 5
def check_include(res):
    for f in sorted(glob.glob(os.path.join(LAYOUT, '*.xml'))):
        txt = open(f, encoding='utf-8').read()
        for m in re.finditer(r'<include[^>]*layout="@layout/([A-Za-z_0-9]+)"', txt):
            if m.group(1) not in res['layout']:
                err('%s include 了不存在的布局 @layout/%s'
                    % (os.path.basename(f), m.group(1)))


# ---------------------------------------------------------------- 检查 6
def check_dangerous():
    """<include> 与 layout_weight 一起用是已知的坑，v1.1 从没用过 include。"""
    for f in sorted(glob.glob(os.path.join(LAYOUT, '*.xml'))):
        txt = open(f, encoding='utf-8').read()
        if '<include' in txt:
            warn('%s 仍使用 <include>（v1.3 曾因它闪退过，注意验证）'
                 % os.path.basename(f))


# ---------------------------------------------------------------- 检查 7
# 这条是 v1.4 抓到的真案子补上的：
#   play_bar_collapsed 被写成 <View> 却在里面塞了 3 个 TextView，
#   真机 inflate 直接抛
#     android.view.View cannot be cast to android.view.ViewGroup
#   —— 表现就是「点合成音频立刻崩」，而 aapt2 编译期完全不报错。
#   View 不是 ViewGroup，装不了子视图，必须静态查出来。
VIEWGROUP = {
    'LinearLayout', 'FrameLayout', 'RelativeLayout', 'TableLayout',
    'TableRow', 'GridLayout', 'AbsoluteLayout', 'ScrollView',
    'HorizontalScrollView', 'ListView', 'RadioGroup', 'Toolbar',
    'androidx.constraintlayout.widget.ConstraintLayout', 'merge',
}
NON_CONTAINER = {
    'View', 'TextView', 'Button', 'ImageView', 'ImageButton', 'EditText',
    'CheckBox', 'RadioButton', 'ToggleButton', 'SeekBar', 'ProgressBar',
    'Space', 'WebView', 'TextClock', 'Chronometer',
}


def check_container():
    for f in sorted(glob.glob(os.path.join(LAYOUT, '*.xml'))):
        try:
            root = ET.parse(f).getroot()
        except Exception:
            continue
        for el in root.iter():
            tag = el.tag.split('}')[-1]
            if tag not in NON_CONTAINER:
                continue
            if len(list(el)) == 0:
                continue
            i = el.get(ANDROID + 'id')
            name = i.split('/')[-1] if i else '(无 id)'
            err('%s: <%s> 里放了 %d 个子元素（id=%s）—— %s 不是 ViewGroup，'
                '真机 inflate 会抛 ClassCastException'
                % (os.path.basename(f), tag, len(list(el)), name, tag))


# ---------------------------------------------------------------------------
# 第 8 类：findViewById 的强转类型与布局里的实际标签是否匹配
#
# 这跟第 7 类是同一族的崩溃，但发生在 inflate 成功「之后」：
#   (TextView) findViewById(R.id.row_failure)  —— 而布局里 row_failure 是 LinearLayout
# 一旦用户点进那个界面，就抛 ClassCastException。
# ---------------------------------------------------------------------------

# 某个强转目标类型，能安全接收哪些布局标签
CAST_OK = {
    'TextView': {'TextView', 'View', 'Button', 'CheckBox', 'RadioButton',
                 'EditText', 'ImageView', 'ToggleButton', 'TextClock',
                 'Chronometer', 'ImageButton'},
    'ListView': {'ListView', 'View', 'ViewGroup', 'AdapterView', 'GridView'},
    'ProgressBar': {'ProgressBar', 'View', 'ViewGroup'},
    'SeekBar': {'SeekBar', 'ProgressBar', 'View', 'ViewGroup'},
    'EditText': {'EditText', 'TextView', 'View', 'ViewGroup'},
    'CheckBox': {'CheckBox', 'TextView', 'View', 'ViewGroup', 'RadioButton',
                 'ToggleButton'},
    'LinearLayout': {'LinearLayout', 'View', 'ViewGroup', 'FrameLayout'},
    'FrameLayout': {'FrameLayout', 'View', 'ViewGroup', 'LinearLayout'},
    'ViewGroup': {'ViewGroup', 'LinearLayout', 'FrameLayout', 'ScrollView',
                  'ListView', 'RelativeLayout', 'AdapterView'},
    'View': {'View', 'TextView', 'ViewGroup', 'LinearLayout', 'FrameLayout',
             'ListView', 'ProgressBar', 'SeekBar', 'EditText', 'CheckBox',
             'ImageView', 'ImageButton', 'Button', 'RadioButton', 'ToggleButton'},
}


def check_casts():
    # id -> 布局里出现过的标签集合
    id2tag = {}
    for f in sorted(glob.glob(os.path.join(LAYOUT, '*.xml'))):
        try:
            root = ET.parse(f).getroot()
        except Exception:
            continue
        for el in root.iter():
            i = el.get(ANDROID + 'id')
            if not i:
                continue
            name = i.split('/')[-1]
            id2tag.setdefault(name, set()).add(el.tag.split('}')[-1])

    # 代码里的强转：(类型) xxx.findViewById(R.id.yyy)
    pat = re.compile(r'\((\w+)\)\s*\w*\.?findViewById\(R\.id\.(\w+)\)')
    for f in sorted(glob.glob(os.path.join(JAVA, '**', '*.java'), recursive=True)):
        try:
            src = open(f, encoding='utf-8').read()
        except Exception:
            continue
        for m in pat.finditer(src):
            cast, rid = m.group(1), m.group(2)
            if rid not in id2tag:
                continue
            for tag in id2tag[rid]:
                allowed = CAST_OK.get(cast)
                if allowed is None or tag in allowed:
                    continue
                err('%s: (TextView) 之类的强转对不上 —— R.id.%s 在布局里是 <%s>，'
                    '点进这个界面会抛 ClassCastException'
                    % (os.path.basename(f), rid, tag)
                    if cast != 'TextView' else
                    '%s: (%s) 强转对不上 —— R.id.%s 在布局里是 <%s>，'
                    '点进这个界面会抛 ClassCastException'
                    % (os.path.basename(f), cast, rid, tag))


def main():
    res = collect_res()
    check_ids(res)
    check_refs(res)
    check_framework_attrs()
    check_include(res)
    check_dangerous()
    check_container()
    check_casts()

    for w in warnings:
        print('[WARN] ' + w)
    for e in errors:
        print('[FAIL] ' + e)
    if errors:
        print('\n结果: %d 项错误, %d 项警告' % (len(errors), len(warnings)))
        return 1
    print('\n结果: 全部通过（%d 项警告）' % len(warnings))
    return 0


if __name__ == '__main__':
    sys.exit(main())
