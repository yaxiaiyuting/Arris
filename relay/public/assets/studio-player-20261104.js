/* Real audio and an optional analyser. No autoplay, microphone, or network telemetry. */
(() => {
  'use strict';
  const deck = document.querySelector('[data-studio]');
  if (!deck) return;
  const audio = deck.querySelector('audio');
  const play = deck.querySelector('#studio-play');
  const mute = deck.querySelector('#studio-mute');
  const seek = deck.querySelector('#studio-seek');
  const time = deck.querySelector('#studio-time');
  const duration = deck.querySelector('#studio-duration');
  const primary = deck.querySelector('.session-primary');
  const secondary = deck.querySelector('.session-secondary');
  const status = deck.querySelector('#studio-status');
  const waveLabel = deck.querySelector('.session-wavebox > span');
  const canvas = deck.querySelector('canvas');
  const ctx = canvas.getContext('2d');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  // Original demonstration verse. Each phrase lands on a four-bar boundary
  // of the instrumental (106 BPM); the two scripts share that phrase clock.
  const beat = 60 / 106;
  const cues = [
    [0, 'The rain has let the evening in.', '雨停了，傍晚悄悄进来。'],
    [16 * beat, 'Leave a little room for the quiet.', '给安静留一点位置。'],
    [32 * beat, 'The city is still humming.', '城市还在轻轻哼唱。'],
    [48 * beat, 'Take this small light with you.', '带着这点微光，继续往前。'],
  ];
  const previous = deck.querySelector('.session-previous');
  const next = deck.querySelector('.session-next');
  const activeLyric = deck.querySelector('.session-active');
  const coverPane = deck.querySelector('#studio-pane-cover');
  const lyricPane = deck.querySelector('#studio-pane-lyrics');
  const lyricsShortcut = deck.querySelector('#studio-open-lyrics');
  const tabs = [...deck.querySelectorAll('[data-studio-view]')];
  let words = [], freshnessTimer = 0, palette;
  const history = new Float32Array(60);
  let context, analyser, pcm, fill, cueIndex = -1, translationFirst = false;
  let frame = 0, lastSample = -Infinity, width = 0, height = 0, started = false, requestedMetadata = false, pendingSeek = null;
  const hasDuration = () => Number.isFinite(audio.duration) && audio.duration > 0;
  const format = value => {
    const n = Math.max(0, Math.floor(value));
    return Math.floor(n / 60) + ':' + String(n % 60).padStart(2, '0');
  };

  function updateText() {
    const position = audio.currentTime;
    if (hasDuration()) {
      if (Number(seek.max) !== audio.duration) {
        seek.max = audio.duration;
        duration.textContent = format(audio.duration);
      }
      if (Math.abs(Number(seek.value) - position) > .05) seek.value = position;
      seek.style.setProperty('--seek-pct', (100 * position / audio.duration).toFixed(2) + '%');
    }
    const visibleTime = format(position);
    if (time.textContent !== visibleTime) {
      time.textContent = visibleTime;
      const [m, s] = visibleTime.split(':');
      seek.setAttribute('aria-valuetext', Number(m) + ' 分 ' + Number(s) + ' 秒');
    }
    let index = cues.length - 1;
    while (index > 0 && cues[index][0] > position) index--;
    if (cueIndex !== index) {
      const animate = cueIndex !== -1 && deck.dataset.view === 'lyrics' && !audio.seeking && !reduced.matches;
      cueIndex = index;
      const top = translationFirst ? 2 : 1;
      const bottom = translationFirst ? 1 : 2;
      words = [...renderLine(primary, index, top), ...renderLine(secondary, index, bottom)];
      previous.textContent = cues[index - 1]?.[top] || '';
      next.textContent = cues[index + 1]?.[top] || '';
      previous.lang = next.lang = primary.lang;
      if (animate && !activeLyric.hasAttribute('data-fresh')) {
        activeLyric.setAttribute('data-fresh', '');
        clearTimeout(freshnessTimer);
        freshnessTimer = setTimeout(() => activeLyric.removeAttribute('data-fresh'), 340);
      }
    }
    for (const word of words) {
      const amount = Math.round(100 * Math.max(0, Math.min(1, (position - word.start) / (word.end - word.start))));
      if (amount === word.amount) continue;
      word.amount = amount;
      word.el.toggleAttribute('data-lit', amount === 100);
      word.el.toggleAttribute('data-sweeping', amount > 0 && amount < 100);
      if (amount > 0 && amount < 100) word.el.style.setProperty('--word-pct', amount + '%');
    }
  }

  function renderLine(line, index, language) {
    const [start] = cues[index];
    const parts = language === 1 ? cues[index][language].split(/(\s+)/) : Array.from(cues[index][language]);
    const weights = parts.map(part => {
      const letters = part.match(/[\p{L}\p{N}]/gu);
      return letters ? (language === 1 ? Math.max(2, letters.length) : 1) : 0;
    });
    const total = weights.reduce((a, b) => a + b, 0);
    // Leave the final beat and a half lit before moving to the next phrase.
    const span = 14.5 * beat;
    const fragment = document.createDocumentFragment();
    const result = [];
    let elapsed = 0;
    parts.forEach((part, i) => {
      if (!weights[i]) {
        fragment.append(part);
        return;
      }
      const el = document.createElement('span');
      el.className = 'session-word';
      el.textContent = part;
      const length = weights[i] / total * span;
      result.push({ el, start: start + elapsed, end: start + elapsed + length, amount: -1 });
      elapsed += length;
      fragment.append(el);
    });
    line.lang = language === 1 ? 'en' : 'zh-CN';
    line.replaceChildren(fragment);
    return result;
  }

  function selectView(view, focusTab = false) {
    const lyrics = view === 'lyrics';
    deck.dataset.view = view;
    coverPane.inert = lyrics;
    lyricPane.inert = !lyrics;
    coverPane.setAttribute('aria-hidden', String(lyrics));
    lyricPane.setAttribute('aria-hidden', String(!lyrics));
    tabs.forEach(tab => {
      const selected = tab.dataset.studioView === view;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      if (selected && focusTab) tab.focus();
    });
    lyricsShortcut.setAttribute('aria-pressed', String(lyrics));
    lyricsShortcut.setAttribute('aria-label', lyrics ? '返回封面' : '打开歌词');
    lyricsShortcut.lastElementChild.textContent = lyrics ? '封面' : '歌词';
    updateText();
  }

  function draw() {
    if (!ctx || !width || !height) return;
    const middle = height * .58;
    const step = width / (history.length - 1);
    ctx.clearRect(0, 0, width, height);
    ctx.strokeStyle = palette.rule;
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.moveTo(0, middle); ctx.lineTo(width, middle); ctx.stroke();
    ctx.beginPath();
    for (let i = 0; i < history.length; i++) {
      const x = step * i, y = middle - history[i] * height * .36;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.quadraticCurveTo(x - step * .5, middle - history[i - 1] * height * .36, x, y);
    }
    ctx.strokeStyle = palette.accent;
    ctx.lineWidth = 1.5;
    ctx.stroke();
    for (let i = history.length - 1; i >= 0; i--) ctx.lineTo(step * i, middle + history[i] * height * .23);
    ctx.closePath();
    ctx.fillStyle = fill;
    ctx.fill();
  }

  function resize() {
    if (!ctx) return;
    const bounds = canvas.getBoundingClientRect();
    const dpr = Math.min(2, devicePixelRatio || 1);
    width = bounds.width;
    height = bounds.height;
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    fill = ctx.createLinearGradient(0, 0, 0, Math.max(1, height));
    const css = getComputedStyle(deck);
    palette = { accent: css.getPropertyValue('--deck-accent').trim(), rule: css.getPropertyValue('--deck-rule').trim() };
    fill.addColorStop(0, palette.accent + '71');
    fill.addColorStop(1, palette.accent + '09');
    draw();
  }

  function paint(now) {
    frame = 0;
    if (audio.paused || audio.ended || document.hidden || reduced.matches) return;
    if (analyser && now - lastSample >= 1000 / 32) {
      lastSample = now;
      analyser.getFloatTimeDomainData(pcm);
      let energy = 0;
      for (let i = 0; i < pcm.length; i++) energy += pcm[i] * pcm[i];
      history.copyWithin(0, 1);
      history[history.length - 1] = Math.min(1, Math.sqrt(energy / pcm.length) * 4);
      draw();
    }
    updateText();
    frame = requestAnimationFrame(paint);
  }

  function reconcile() {
    const active = !audio.paused && !audio.ended;
    deck.toggleAttribute('data-playing', active);
    play.lastElementChild.textContent = active ? '暂停' : audio.ended ? '重播' : '播放';
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    if (active && !document.hidden && !reduced.matches) frame = requestAnimationFrame(paint);
    updateText();
  }

  function armAnalyser() {
    if (!ctx || context || reduced.matches) return;
    const AudioContext = window.AudioContext || window.webkitAudioContext;
    if (!AudioContext) return;
    try {
      context = new AudioContext();
      const meter = context.createAnalyser();
      meter.fftSize = 1024;
      const source = context.createMediaElementSource(audio);
      source.connect(meter);
      meter.connect(context.destination);
      pcm = new Float32Array(meter.fftSize);
      analyser = meter;
    } catch {}
  }

  play.addEventListener('click', async () => {
    status.textContent = '';
    if (!audio.paused && !audio.ended) { audio.pause(); return; }
    armAnalyser();
    if (context && context.state !== 'running') context.resume().catch(() => {});
    if (audio.ended) audio.currentTime = 0;
    started = true;
    try { await audio.play(); } catch (e) {
      if (e.name !== 'AbortError') status.textContent = '暂时无法播放。请重试，或下载试听曲。';
    }
    if (!analyser && !reduced.matches) waveLabel.textContent = '当前浏览器未启用实时波形';
  });
  mute.addEventListener('click', () => { audio.muted = !audio.muted; });
  function jump(position) {
    if (!hasDuration()) {
      pendingSeek = Math.max(0, Math.min(position, Number(seek.max)));
      if (!started && !requestedMetadata) {
        requestedMetadata = true;
        audio.load();
      }
      return;
    }
    audio.currentTime = Math.max(0, Math.min(position, audio.duration));
    history.fill(0);
    draw();
    updateText();
  }

  seek.addEventListener('input', () => {
    seek.style.setProperty('--seek-pct', (100 * Number(seek.value) / Number(seek.max)).toFixed(2) + '%');
    jump(Number(seek.value));
  });
  deck.querySelectorAll('[data-studio-skip]').forEach(button => button.addEventListener('click', () =>
    jump((pendingSeek ?? audio.currentTime) + Number(button.dataset.studioSkip))));
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', event => {
      event.preventDefault();
      selectView(tab.dataset.studioView);
    });
    tab.addEventListener('keydown', event => {
      if (event.key === ' ') {
        event.preventDefault();
        selectView(tab.dataset.studioView);
        return;
      }
      const to = {ArrowLeft: 1 - index, ArrowRight: 1 - index, Home: 0, End: 1}[event.key];
      if (to === undefined) return;
      event.preventDefault();
      selectView(tabs[to].dataset.studioView, true);
    });
  });
  lyricsShortcut.addEventListener('click', () => selectView(deck.dataset.view === 'cover' ? 'lyrics' : 'cover'));
  audio.addEventListener('loadedmetadata', () => {
    if (pendingSeek !== null && hasDuration()) audio.currentTime = Math.min(pendingSeek, audio.duration);
    pendingSeek = null;
    updateText();
  });
  audio.addEventListener('volumechange', () => {
    mute.setAttribute('aria-pressed', String(audio.muted));
  });
  audio.addEventListener('error', () => { status.textContent = '试听曲加载失败。可重新打开页面，或使用下载链接。'; });
  ['play','pause','ended','seeked','timeupdate'].forEach(event => audio.addEventListener(event,
    event === 'timeupdate' ? updateText : reconcile));
  document.addEventListener('visibilitychange', reconcile);
  const buttons = [...deck.querySelectorAll('[data-studio-line]')];
  buttons.forEach(button => button.addEventListener('click', () => {
    translationFirst = button.dataset.studioLine === 'translation';
    buttons.forEach(item => item.setAttribute('aria-pressed', String(item === button)));
    cueIndex = -1;
    updateText();
  }));

  function applyMotion() {
    if (reduced.matches) {
      history.fill(0);
      draw();
      waveLabel.textContent = '已遵循系统设置：减少动画';
    } else {
      // Reconnecting a media element is restricted. If playback started with reduced motion,
      // leave the audio path alone and offer the full visualizer on the next play.
      waveLabel.textContent = !analyser && started && !audio.paused ? '下次播放时开启实时波形' : '实时波形';
      lastSample = -Infinity;
    }
    reconcile();
  }
  if (reduced.addEventListener) reduced.addEventListener('change', applyMotion);
  else reduced.addListener(applyMotion);
  if ('ResizeObserver' in window) new ResizeObserver(resize).observe(canvas);
  else window.addEventListener('resize', resize);
  audio.controls = false;
  audio.hidden = true;
  deck.querySelector('.session-controls').hidden = false;
  deck.querySelector('.session-tabs').setAttribute('role', 'tablist');
  tabs.forEach(tab => tab.setAttribute('role', 'tab'));
  [coverPane, lyricPane].forEach(pane => pane.setAttribute('role', 'tabpanel'));
  selectView(location.hash === '#studio-pane-lyrics' ? 'lyrics' : 'cover');
  deck.setAttribute('data-ready', '');
  deck.querySelector('.session-line-options').hidden = false;
  deck.querySelector('.session-wavebox').hidden = false;
  new MutationObserver(resize).observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']});
  resize();
  applyMotion();
})();
