import numpy as np, sys, matplotlib
matplotlib.use('Agg'); import matplotlib.pyplot as plt
SR=48000
path,out=sys.argv[1],sys.argv[2]; times=[float(x) for x in sys.argv[3:]]
a=np.fromfile(path,dtype='<i2').reshape(-1,2).astype(float)
fig,ax=plt.subplots(len(times),2,figsize=(14,2.2*len(times)))
for k,t in enumerate(times):
    c=int(t*SR)
    for j,(w,lab) in enumerate(((240,'±5 мс'),(4800,'±100 мс'))):
        s=a[c-w:c+w]; x=(np.arange(len(s))-w)/SR*1000
        ax[k][j].plot(x,s[:,0],lw=.6); ax[k][j].plot(x,s[:,1],lw=.6,alpha=.7)
        ax[k][j].set_title(f"{t:.3f} с  {lab}",fontsize=8); ax[k][j].tick_params(labelsize=6)
plt.tight_layout(); plt.savefig(out,dpi=80)
