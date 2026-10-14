# Approximate numpy port of sherpa ParakeetReferenceFrames (offline-stream.cc:361-420, dbbeef9c) to measure how
# 0.5 s of synthetic zeros (DecodePolicy.WHOLE_SPANS tailPaddingMs=500) shifts the per_feature-normalised features
# of the REAL frames. Mel: slaney scale + slaney norm (librosa default), 0..8 kHz, 128 bins, n_fft 512.
import wave,sys,numpy as np
def hz2mel(f):
    f=np.asarray(f,float); m=f/(200/3); lo=1000; ml=lo/(200/3); ls=np.log(6.4)/27
    return np.where(f>=lo, ml+np.log(np.maximum(f,1e-9)/lo)/ls, m)
def mel2hz(m):
    m=np.asarray(m,float); lo=1000; ml=lo/(200/3); ls=np.log(6.4)/27
    return np.where(m>=ml, lo*np.exp(ls*(m-ml)), m*200/3)
def melfb(sr=16000,n_fft=512,n=128):
    fft=np.linspace(0,sr/2,n_fft//2+1); mp=mel2hz(np.linspace(hz2mel(0),hz2mel(sr/2),n+2))
    fd=np.diff(mp); r=mp[:,None]-fft[None,:]; W=np.zeros((n,len(fft)))
    for i in range(n): W[i]=np.maximum(0,np.minimum(-r[i]/fd[i], r[i+2]/fd[i+1]))
    W*= (2.0/(mp[2:n+2]-mp[:n]))[:,None]; return W
FB=melfb()
def logmel(x):
    n=len(x)//160
    pe=np.concatenate([[x[0]], x[1:]-0.97*x[:-1]]); p=np.concatenate([np.zeros(200),pe,np.zeros(200)])
    win=np.hanning(400)  # symmetric
    fr=np.stack([p[i*160:i*160+400]*win for i in range(n)])
    spec=np.abs(np.fft.rfft(fr,512))**2
    return np.log(spec@FB.T+2**-24)
def norm(L):
    return (L-L.mean(0))/(L.std(0,ddof=1)+1e-5)
w=wave.open(sys.argv[1]); x=np.frombuffer(w.readframes(w.getnframes()),'<i2').astype(np.float32)/32768
for dur in (1.0,2.0,4.0,8.0,len(x)/16000):
    s=x[:int(dur*16000)]
    a=norm(logmel(s)); n=len(a)
    for pad in (0.5,):
        b=norm(logmel(np.concatenate([s,np.zeros(int(pad*16000),np.float32)])))[:n]
        d=np.abs(a-b)
        L=logmel(np.concatenate([s,np.zeros(8000,np.float32)]))
        print(f"span {dur:5.2f}s frames {n:4d} +{pad}s zeros: mean|dz| over real frames {d.mean():.3f}, max {d.max():.2f}; "
              f"zero-frame log-mel {L[-10:].mean():.2f} vs real-frame mean {L[:n].mean():.2f}")
