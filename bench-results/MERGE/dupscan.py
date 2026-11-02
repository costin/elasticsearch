# dupscan.py — usage: python3 -I dupscan.py 6 <files...>
import sys, re, collections
W = int(sys.argv[1]); files = sys.argv[2:]
stmts = []
for f in files:
    for i, l in enumerate(open(f), 1):
        s = l.strip()
        if not s or s.startswith(('//', '*', '/*', 'import ', '@')) or s in ('{', '}', '});', ');'):
            continue
        s = re.sub(r'\b(var|tmp|slot|local|l|i|j|k)\d*\b', 'V', s)
        stmts.append((f.split('/')[-1], i, s))
idx = collections.defaultdict(list)
for n in range(len(stmts) - W + 1):
    win = stmts[n:n+W]
    if len({w[0] for w in win}) > 1: continue
    idx[tuple(w[2] for w in win)].append((win[0][0], win[0][1], win[-1][1]))
pairs = collections.defaultdict(list)
for k, v in idx.items():
    if len(v) > 1:
        a, b = v[0], v[1]; pairs[(a[0], b[0])].append((a[1], a[2], b[1], b[2]))
for (fa, fb), rs in sorted(pairs.items()):
    rs.sort(); merged = []
    for r in rs:
        if merged and r[0] <= merged[-1][1] + 1 and r[2] <= merged[-1][3] + 1:
            m = merged[-1]; merged[-1] = (m[0], max(m[1], r[1]), m[2], max(m[3], r[3]))
        else: merged.append(r)
    for m in merged:
        print(f"{fa}:{m[0]}-{m[1]}  ~  {fb}:{m[2]}-{m[3]}  ({m[1]-m[0]+1} lines)")
