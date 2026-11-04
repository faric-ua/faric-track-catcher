import numpy as np, sys, matplotlib
matplotlib.use('Agg'); import matplotlib.pyplot as plt
SR=48000
p,out,t0,t1=sys.argv[1],sys.argv[2],float(sys.argv[3]),float(sys.argv[4])
a=np.fromfile(p,dtype='<i2').reshape(-1,2).astype(float); m=(a[:,0]+a[:,1])/2
W=960; step=240; maxlag=int(0.6*SR); minlag=480
ts=[];best=[];bl=[]
for s in range(int(t0*SR), int(t1*SR), step):
    w=m[s:s+W]; w=w-w.mean(); nw=np.linalg.norm(w)+1e-9
    seg=m[s-maxlag:s+W-minlag]
    # correlation of w against all earlier positions with lag in [minlag,maxlag]
    c=np.correlate(seg,w,mode='valid')  # positions s-maxlag .. s-minlag
    # energies
    cs=np.cumsum(np.concatenate(([0],seg**2))); e=np.sqrt(cs[W:]-cs[:-W])+1e-9
    cs1=np.cumsum(np.concatenate(([0],seg))); mu=(cs1[W:]-cs1[:-W])/W
    r=(c- mu*w.sum())/(e*nw)  # approx normalized
    k=np.argmax(r); ts.append(s/SR); best.append(r[k]); bl.append((maxlag-k)/SR*1000)
ts=np.array(ts);best=np.array(best);bl=np.array(bl)
for t,b,l in zip(ts,best,bl):
    if b>0.97: print(f"{int(t//60)}:{t%60:06.3f}  схожість {b:.3f}  з тим, що було {l:.0f} мс тому")
fig,ax=plt.subplots(3,1,figsize=(14,8))
x=np.arange(int(t0*SR),int(t1*SR))/SR
ax[0].plot(x,m[int(t0*SR):int(t1*SR)],lw=.3); ax[0].set_title('хвиля')
ax[1].specgram(m[int(t0*SR):int(t1*SR)],NFFT=1024,Fs=SR,noverlap=768,xextent=(t0,t1)); ax[1].set_ylim(0,16000)
ax[2].plot(ts,best); ax[2].set_ylim(0.5,1.01); ax[2].set_title('найбільша схожість з попереднім (повтор → ~1)')
plt.tight_layout(); plt.savefig(out,dpi=70)
