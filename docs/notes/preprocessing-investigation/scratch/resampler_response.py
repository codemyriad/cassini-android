# Port of core/audio/Resampler.kt resampleTo16k; measures gain of sine tones after resampling.
import numpy as np
ZC=12; STEPS=1024; CUT=0.4375
z=np.arange(ZC*STEPS+2)/STEPS
k=np.where(z>=ZC,0.0,np.sinc(z)*0.5*(1+np.cos(np.pi*z/ZC))).astype(np.float32)
def resample(x,rate):
    n=int((len(x)*16000+rate//2)//rate); out=np.zeros(n); step=rate/16000
    cps=2*CUT*min(rate,16000)/rate; hw=ZC/cps
    for j in range(n):
        t=j*step; lo=max(0,int(np.ceil(t-hw))); hi=min(len(x)-1,int(np.floor(t+hw)))
        ks=np.arange(lo,hi+1); pos=np.abs(t-ks)*cps*STEPS; c=pos.astype(int); f=(pos-c).astype(np.float32)
        tap=k[c]+(k[c+1]-k[c])*f; out[j]=(tap*x[ks]).sum()/tap.sum()
    return out
for rate in (48000,44100):
    print("rate",rate)
    for hz in (1000,4000,6000,6500,7000,7500,7800,8000,9000,10000,12000,15000,20000):
        if hz>=rate/2: continue
        t=np.arange(int(rate*0.25))/rate; x=np.sin(2*np.pi*hz*t)
        y=resample(x,rate)[400:-400]
        print(f"  {hz:6d} Hz -> {20*np.log10(np.sqrt(np.mean(y**2))/np.sqrt(0.5)+1e-12):7.2f} dB")
