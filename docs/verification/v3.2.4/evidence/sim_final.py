#!/usr/bin/env python3
"""v3.2.4 · P1 探针：MotionClock 帧闸门的刷新率适配（60/90/120/144Hz）—— 最终口径。

源码依据（v3.2.3，逐字）：
  MotionClock.kt:201  val frameIntervalMs = remember(context) { visualizerFrameIntervalMs(context) }
  MotionClock.kt:208  val budgetNs = frameIntervalMs * 1_000_000L
  MotionClock.kt:212  if (clock[0] == 0L || now - clock[0] >= budgetNs) { ... }
  MotionClock.kt:214  dtMs = ((now - clock[1]) / 1_000_000f).coerceIn(1f, 100f)
  AudioVisualizer.kt:497  VISUALIZER_FRAME_INTERVAL_FAST_MS = 16L
  WaveformRing.kt:206 sinceBarMs += dtForPhase
  WaveformRing.kt:441 phase = clamp(sinceBarMs / barIntervalMs, 0, 1)

量：Δφ = dt / barIntervalMs 就是「这一帧曲线左移了多少格」。Δφ 的离散度 (CV) = 肉眼看到的抖动。
"""
import random, statistics as st
AUDIO_MS=100.0; EMA=0.15; MIN_IV,MAX_IV=20.0,500.0; WARMUP,DURATION=6000.0,30000.0
def sim(hz,budget,jitter=0.0,slow=0.0,seed=7):
    rng=random.Random(seed); vsync=1000.0/hz; t=0.0; la=None; ld=None; na=AUDIO_MS
    sb=0.0; iv=100.0; rows=[]
    while t<WARMUP+DURATION:
        c=0
        while na<=t: na+=AUDIO_MS; c+=1
        if la is None or t-la>=budget:
            dt=budget if ld is None else min(max(t-ld,1.0),100.0); ld=t; la=t
            if c==0: sb+=dt
            if c:
                avg=sb/c
                if MIN_IV<=avg<=MAX_IV: iv+=(avg-iv)*EMA
                sb=0.0
            if t>=WARMUP: rows.append(dt)
        step=vsync+(rng.uniform(-jitter,jitter) if jitter else 0.0)
        if slow and rng.random()<slow: step+=vsync
        t+=step
    dp=[d/iv for d in rows]; q=st.quantiles(rows,n=100)
    return dict(fps=len(rows)/(DURATION/1000.0), dt50=st.median(rows), dt99=q[98], dtmax=max(rows),
                dtcv=st.pstdev(rows)/st.mean(rows), dp50=st.median(dp), dp99=q and st.quantiles(dp,n=100)[98],
                dpcv=st.pstdev(dp)/st.mean(dp))
def tab(title, budget_fn, jitter, slow):
    print(f"\n### {title}")
    print(f"| 刷新率 | 闸门预算 | 实测推进率 | dt_p50 | dt_p99 | dt_max | dt_CV | Δφ_p50 | Δφ_p99 | Δφ_CV |")
    print(f"|---|---|---|---|---|---|---|---|---|---|")
    for hz in (60,90,120,144):
        b=budget_fn(hz); r=sim(hz,b,jitter,slow)
        print(f"| {hz}Hz | {b:.2f}ms | {r['fps']:.1f} fps ({r['fps']/hz*100:.0f}%) | {r['dt50']:.2f} | {r['dt99']:.2f} | {r['dtmax']:.2f} | {r['dtcv']:.3f} | {r['dp50']:.4f} | {r['dp99']:.4f} | {r['dpcv']:.3f} |")
tab("A · 隔离闸门量化：只有 ±0.6ms frame-pacing 抖动（不掉帧）", lambda hz:16.0, 0.6, 0.0)
tab("B · 同上，配对照：修复后（每帧都推进）", lambda hz:0.0, 0.6, 0.0)
tab("C · 加固：±0.6ms 抖动 + 5% 掉帧（真实设备量级）", lambda hz:16.0, 0.6, 0.05)
tab("D · 同上，配对照：修复后（每帧都推进）", lambda hz:0.0, 0.6, 0.05)
