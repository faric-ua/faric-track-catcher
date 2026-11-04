import numpy as np, sys, matplotlib
matplotlib.use('Agg'); import matplotlib.pyplot as plt
SR=48000
p,out=sys.argv[1],sys.argv[2]; spans=[(float(sys.argv[i]),float(sys.argv[i+1])) for i in range(3,len(sys.argv),2)]
a=np.fromfile(p,dtype='<i2').reshape(-1,2).astype(float)
fig,ax=plt.subplots(len(spans),1,figsize=(14,2.6*len(spans)))
ax=np.atleast_1d(ax)
for k,(t0,t1) in enumerate(spans):
    s=a[int(t0*SR):int(t1*SR)]; x=np.arange(len(s))/SR+t0
    ax[k].plot(x,s[:,0],lw=.4); ax[k].plot(x,s[:,1],lw=.4,alpha=.6)
    m=(s[:,0]+s[:,1])/2; B=240; n=len(m)//B*B
    env=np.sqrt(np.mean(m[:n].reshape(-1,B)**2,axis=1))
    ax[k].plot(np.arange(len(env))*B/SR+t0+B/SR/2,env*2,'k',lw=1)
    ax[k].set_title(f"{t0:.2f}–{t1:.2f} с (чорне — гучність кожні 5 мс)",fontsize=9)
plt.tight_layout(); plt.savefig(out,dpi=70)
