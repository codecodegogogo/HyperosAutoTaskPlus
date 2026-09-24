import os, sys
from pathlib import Path
os.environ.setdefault('LOGURU_LEVEL', 'ERROR')
from loguru import logger
logger.remove()
from androguard.core.dex import DEX

VERSIONS = [
    ('13.5.9', Path(r'build\verify_1359\dex'), 'Lm6/h2;', 'Lm6/f2;', 'Lh6/m;', 'Le6/v;'),
    ('13.6.0', Path(r'build\verify_1360\dex'), 'Lm6/h2;', 'Lm6/f2;', 'Lh6/m;', 'Le6/v;'),
]

def load(root):
    result = {}
    for p in sorted(root.glob('classes*.dex')):
        d = DEX(p.read_bytes())
        for c in d.get_classes():
            result[c.get_name()] = c
    return result

def sigs(c):
    return {m.get_name() + m.get_descriptor().replace(' ', '') for m in c.get_methods()}

def fields(c):
    return {f.get_name() + ':' + f.get_descriptor() for f in c.get_fields()}

def require(ok, label, detail=''):
    print(('PASS ' if ok else 'FAIL ') + label + ((' :: ' + detail) if detail else ''))
    return bool(ok)

all_ok = True
for ver, root, factory_name, editor_name, engine_name, adapter_name in VERSIONS:
    print(f'=== {ver} ===')
    classes = load(root)
    f, e, g, a = (classes.get(x) for x in (factory_name, editor_name, engine_name, adapter_name))
    all_ok &= require(f is not None, f'factory {factory_name}')
    all_ok &= require(e is not None, f'editor {editor_name}')
    all_ok &= require(g is not None, f'engine {engine_name}')
    all_ok &= require(a is not None, f'adapter {adapter_name}')
    if None in (f, e, g, a):
        continue
    fs, es, gs, a_s = sigs(f), sigs(e), sigs(g), sigs(a)
    all_ok &= require('h(Ljava/lang/String;)Ljava/lang/Class;' in fs, 'factory.h(String)->Class')
    all_ok &= require('B(Ljava/lang/String;)Lcom/miui/autotask/taskitem/TaskItem;' in fs, 'factory.B(String)->TaskItem')
    all_ok &= require('j()Ljava/util/Map;' in fs, 'factory.j()->Map')
    all_ok &= require('s(Ljava/lang/String;)Ljava/lang/String;' in fs, 'factory.s(String)->String')
    all_ok &= require('t(Lcom/miui/autotask/taskitem/TaskItem;)Lcom/miui/autotask/taskitem/TaskItem;' in fs, 'factory.t(TaskItem)->TaskItem')
    all_ok &= require('e(Lcom/miui/autotask/taskitem/TaskItem;Ljava/util/List;)I' in fs, 'factory.e(TaskItem,List)->int')
    all_ok &= require(any(s.startswith('F0(Landroid/content/Context;Lcom/miui/autotask/taskitem/TaskItem;') and s.endswith(';I)V') for s in es), 'editor.F0')
    all_ok &= require(any(s.startswith('G0(Landroid/content/Context;Lcom/miui/autotask/taskitem/TaskItem;') and s.endswith(';I)V') for s in es), 'editor.G0')
    all_ok &= require('t(Lcom/miui/autotask/taskitem/TaskItem;)V' in gs, 'engine.t(TaskItem)')
    all_ok &= require('o1(Ljava/lang/String;Ljava/util/List;)V' in gs, 'engine.o1(String,List)')
    all_ok &= require('O(Ljava/util/concurrent/ConcurrentHashMap;)V' in gs, 'engine.O(ConcurrentHashMap)')
    all_ok &= require('F0()' + engine_name in gs, 'engine.F0()->engine')
    all_ok &= require('q0(Ljava/lang/String;)Lcom/miui/autotask/taskitem/AddressTaskItem;' in gs, 'engine.q0(String)->AddressTaskItem')
    af = fields(a)
    all_ok &= require('a:Ljava/util/List;' in af and 'e:Z' in af, 'adapter fields a:List,e:boolean', ','.join(sorted(af)))
    holder = classes.get(adapter_name[:-1] + '$c;')
    all_ok &= require(holder is not None, 'adapter holder $c')
    if holder:
        all_ok &= require('m(' + holder.get_name() + 'I)V' in a_s, 'adapter.m(holder,int)')

    checks = {
        'Lcom/miui/autotask/fragment/AddConditionFragment;': {'n1(Lcom/miui/autotask/taskitem/TaskItem;)V'},
        'Lcom/miui/autotask/fragment/AddResultFragment;': {
            'r2(Lcom/miui/autotask/taskitem/TaskItem;)V', 's1()V'},
        'Lcom/miui/autotask/activity/SelectAppActivity;': {
            'S0(Landroid/app/Activity;Lcom/miui/autotask/taskitem/LunchAppItem;I)V',
            'y0()Ljava/lang/String;'},
        'Lcom/miui/autotask/activity/AddressSelectActivity;': {
            'X0(Landroid/app/Activity;Lcom/miui/autotask/taskitem/AddressTaskItem;I)V'},
        'Lcom/miui/autotask/activity/AddBaseActivity;': {
            'z0(Landroid/app/Activity;Ljava/util/ArrayList;ILjava/lang/Class;)V'},
        'Lcom/miui/autotask/fragment/NewTaskFragment;': {'J0(I)V', 'Q0(I)V'},
        'Lcom/miui/autotask/view/RecyclerViewPreference;': {
            'G(Z)V', 'H(Z)V', 'u()Ljava/util/ArrayList;',
            'B(IILandroid/content/Intent;)V', 'I(Z)V', 'D()V'},
        'Lcom/miui/autotask/fragment/AddBaseFragment;': {
            't0(Lcom/miui/autotask/taskitem/TaskItem;)V'},
    }
    for name, expected in checks.items():
        c = classes.get(name)
        all_ok &= require(c is not None, name)
        if c:
            actual = sigs(c)
            missing = sorted(expected - actual)
            all_ok &= require(not missing, name + ' methods', ','.join(missing))

    for subclass in ('Lcom/miui/autotask/fragment/AddConditionFragment;',
                     'Lcom/miui/autotask/fragment/AddResultFragment;'):
        c = classes.get(subclass)
        all_ok &= require(c is not None and c.get_superclassname() == 'Lcom/miui/autotask/fragment/AddBaseFragment;',
                         subclass + ' extends AddBaseFragment')

print('RESULT', 'PASS' if all_ok else 'FAIL')
sys.exit(0 if all_ok else 1)



