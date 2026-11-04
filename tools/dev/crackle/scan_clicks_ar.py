import numpy as np, sys
from numpy.lib.stride_tricks import sliding_window_view as sw
SR=48000
def ts(i): s=i/SR; return f"{int(s//60)}:{s%60:06.3f}"
P=16
def ar_resid(x):
    # block-wise AR via least squares on 2048 blocks (hop 2048), residual for each sample
    n=len(x); res=np.zeros(n)
    B=2048
    for s in range(P, n-B, B):
        seg=x[s-P-B if s-P-B>0 else 0:s+B]
        X=sw(seg,P)[:-1][:, ::-1]; y=seg[P:]
        if len(y)<P*4: continue
        coef,*_=np.linalg.lstsq(X,y,rcond=None)
        Xb=sw(x[s-P:s+B],P)[:-1][:, ::-1]; yb=x[s:s+B]
        res[s:s+B]=yb-Xb@coef
    return res
for path in sys.argv[1:]:
    a=np.fromfile(path,dtype='<i2').reshape(-1,2).astype(np.float64)
    m=(a[:,0]+a[:,1])/2
    r=ar_resid(m)
    ar=np.abs(r)
    # local robust sigma over 20ms
    W=960; n=len(ar)//W*W
    sig=np.median(ar[:n].reshape(-1,W),axis=1)*1.48+5
    sigx=np.repeat(sig,W)
    z=ar[:n]/sigx
    # isolated: z>12 and the 2 ms after (excluding first 0.3ms) back near normal
    idx=np.where(z>12)[0]
    ev=[];last=-10**9
    for i in idx:
        if i-last>96: ev.append([i,z[i],0])
        else: ev[-1][1]=max(ev[-1][1],z[i])
        last=i
    iso=[]
    for i,zz,_ in ev:
        after=z[i+24:i+240]; before=z[max(0,i-240):i-8]
        if len(after)<10 or len(before)<10: continue
        if np.percentile(after,90)<4 and np.percentile(before,90)<4: iso.append((i,zz))
    print("==",path,"імпульсів (z>12):",len(ev),"ізольованих (клацання):",len(iso))
    for i,zz in sorted(iso,key=lambda t:-t[1])[:20]: print("   ",ts(i),f"z={zz:.0f}")
    # per-second rate of z>8 isolated-ish spikes to find crackly passages
    sec=np.zeros(len(m)//SR+1)
    for i,zz in iso: sec[i//SR]+=1
    top=np.argsort(-sec)[:8]
    print("  секунди з найбільшою кількістю клацань:", ", ".join(f"{s//60}:{s%60:02d}({int(sec[s])})" for s in top if sec[s]>0))
