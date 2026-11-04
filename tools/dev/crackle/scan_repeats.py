import numpy as np, sys
SR=48000
def ts(i): s=i/SR; return f"{int(s//60)}:{s%60:06.3f}"
p=sys.argv[1]; t0=float(sys.argv[2]) if len(sys.argv)>2 else None; t1=float(sys.argv[3]) if len(sys.argv)>3 else None
a=np.fromfile(p,dtype='<i2').reshape(-1,2)
lo=int(t0*SR) if t0 is not None else 0; hi=int(t1*SR) if t1 is not None else len(a)
# exact duplicate blocks of 32 frames (aligned to every frame offset via rolling hash is heavy; use blocks starting at every 16 frames)
B=32
seen={}; hits=[]
for s in range(max(0,lo-SR*2), min(len(a)-B,hi), 1 if t0 is not None else 16):
    blk=a[s:s+B]
    if np.abs(blk).max()<300: continue
    k=blk.tobytes()
    if k in seen and s-seen[k]>=B: hits.append((seen[k],s))
    else: seen.setdefault(k,s)
print("точних повторів блоків по",B,"кадрів:",len(hits))
# group by lag
from collections import Counter
lags=Counter((s-f) for f,s in hits)
for lag,c in lags.most_common(10):
    ex=[s for f,s in hits if s-f==lag]
    print(f"  повтор через {lag} кадрів ({lag/48:.1f} мс): {c} блоків, від {ts(min(ex))} до {ts(max(ex)+B)}")
