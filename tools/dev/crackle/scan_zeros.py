import numpy as np, sys
SR=48000
def ts(i): s=i/SR; return f"{int(s//60)}:{s%60:06.3f}"
for p in sys.argv[1:]:
    a=np.fromfile(p,dtype='<i2').reshape(-1,2).astype(float)
    z=(a[:,0]==0)&(a[:,1]==0)
    d=np.diff(np.concatenate(([0],z.astype(np.int8),[0]))); st=np.where(d==1)[0]; ln=np.where(d==-1)[0]-st
    m=(a[:,0]+a[:,1])/2
    def lev(i0,i1):
        s=m[max(0,i0):max(0,i1)]; r=np.sqrt(np.mean(s**2))/32768 if len(s) else 0; return 20*np.log10(r+1e-12)
    print("==",p)
    for s,l in zip(st,ln):
        if l>=48 and lev(s-4800,s)>-45 and lev(s+l,s+l+4800)>-45:
            print(f"   {ts(s)}  {l} семплів = {l/48:.2f} мс   гучність до {lev(s-4800,s):.0f} дБ, після {lev(s+l,s+l+4800):.0f} дБ")
