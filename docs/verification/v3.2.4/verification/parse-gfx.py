import glob, statistics as st
def parse(p):
    rows=[]; cols=None; inblock=False
    for line in open(p, errors='ignore'):
        s=line.rstrip('\n')
        if s.startswith('---PROFILEDATA---'):
            if not inblock:
                inblock=True; cols=None
            else:
                inblock=False
            continue
        if not inblock: continue
        if cols is None:
            cols = s.split(','); continue
        parts=s.split(',')
        if len(parts) < len(cols)-1: continue
        rows.append(parts)
    return cols, rows
res=[]
for p in sorted(glob.glob('gfx-*.txt')):
    cols, rows = parse(p)
    if not cols: print(p, 'NO FRAMESTATS'); continue
    ix = {n:i for i,n in enumerate(cols)}
    iv, fc = ix.get('IntendedVsync'), ix.get('FrameCompleted')
    durs=[]
    for r in rows:
        try: a=int(r[iv]); b=int(r[fc])
        except (ValueError, TypeError, IndexError): continue
        d=(b-a)/1e6
        if 0 < d < 2000: durs.append(d)
    durs.sort()
    if not durs: print(p,'no durations', len(rows),'rows', cols[:6]); continue
    q=lambda f: durs[min(len(durs)-1,int(len(durs)*f))]
    print(f"{p}: n={len(durs)} p50={q(.5):.1f} p90={q(.9):.1f} p95={q(.95):.1f} p99={q(.99):.1f} max={durs[-1]:.1f}")
    res.append((len(durs),q(.5),q(.9),q(.95),q(.99)))
if res:
    print("\n=== 中位数（跨采样）===")
    m=[st.median([r[i] for r in res]) for i in range(5)]
    print("总帧中位=%d  p50=%.1fms  p90=%.1fms  p95=%.1fms  p99=%.1fms" % tuple(m))
    print("\n注：窗口 30s，采样 4 次；设备 = emulator-5554（API 33 / 60.000004Hz / 软件 GPU），")
    print("    配置 = 大屏模式横屏 + 三条泳道波形 + B站歌曲播放中；包 = release（versionCode 59）。")
