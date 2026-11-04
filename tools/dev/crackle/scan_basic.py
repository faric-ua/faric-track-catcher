import numpy as np, sys
SR=48000
def ts(i): s=i/SR; return f"{int(s//60)}:{s%60:06.3f}"
def runs(mask):
    d=np.diff(np.concatenate(([0],mask.astype(np.int8),[0])))
    st=np.where(d==1)[0]; en=np.where(d==-1)[0]
    return st,en-st
for path in sys.argv[1:]:
    a=np.fromfile(path,dtype='<i2').reshape(-1,2).astype(np.float64)
    L,R=a[:,0],a[:,1]; m=(L+R)/2
    print("==",path,"len",ts(len(a)))
    # 1 zero runs both channels
    z=(a[:,0]==0)&(a[:,1]==0)
    st,ln=runs(z)
    # loudness around
    blk=480
    e=np.sqrt(np.mean(m[:len(m)//blk*blk].reshape(-1,blk)**2,axis=1))/32768
    def lev(i0,i1):
        a0=max(0,i0//blk); a1=min(len(e),max(a0+1,i1//blk))
        v=np.sqrt(np.mean(e[a0:a1]**2)) if a1>a0 else 0
        return 20*np.log10(v+1e-12)
    zs=[(s,l) for s,l in zip(st,ln) if l>=2 and lev(s-4800,s)>-45 and lev(s+l,s+l+4800)>-45]
    big=[x for x in zs if x[1]>=48]; small=[x for x in zs if x[1]<48]
    print(f" нулі ≥1 мс у гучному: {len(big)}; нулі 2–47 семплів: {len(small)}")
    for s,l in big[:15]: print("   ", ts(s), f"{l/48:.1f} мс")
    # 2 clipping
    clip=np.sum(np.abs(a)>=32767); print(" семплів у стелі:",clip)
    # 3 clicks: 2nd difference outliers per channel vs local median
    for ch,x in (("L",L),("R",R)):
        d2=np.abs(x[2:]-2*x[1:-1]+x[:-2])
        n=len(d2)//480*480
        med=np.median(d2[:n].reshape(-1,480),axis=1)  # 10ms median
        loc=np.repeat(np.maximum(med,30),480)
        ratio=d2[:n]/loc
        idx=np.where(ratio>25)[0]
        # cluster
        ev=[]; last=-10**9
        for i in idx:
            if i-last>480: ev.append([i,ratio[i]])
            else: ev[-1][1]=max(ev[-1][1],ratio[i])
            last=i
        print(f" клацання {ch} (стрибок у 25+ раз більший за звичайний): {len(ev)}")
        for i,r in sorted(ev,key=lambda t:-t[1])[:12]: print("   ",ts(i),f"x{r:.0f}")
    # 4 sudden dips: 1ms RMS vs ±20ms
    b=48; n=len(m)//b*b
    r1=np.sqrt(np.mean(m[:n].reshape(-1,b)**2,axis=1))+1
    from numpy.lib.stride_tricks import sliding_window_view as sw
    k=20
    pad=np.pad(r1,(k,k),mode='edge')
    win=sw(pad,2*k+1)
    ctx=np.median(win,axis=1)
    db=20*np.log10(ctx/r1)
    loud=20*np.log10(ctx/32768)>-35
    idx=np.where((db>24)&loud)[0]
    ev=[]; last=-100
    for i in idx:
        if i-last>20: ev.append([i,db[i]])
        else: ev[-1][1]=max(ev[-1][1],db[i])
        last=i
    print(f" провали гучності (>24 дБ на 1 мс): {len(ev)}")
    for i,d in sorted(ev,key=lambda t:-t[1])[:12]: print("   ",ts(i*b),f"-{d:.0f} дБ")
