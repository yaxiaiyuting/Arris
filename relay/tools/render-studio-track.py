#!/usr/bin/env python3
"""Render the original Arris web demo instrumental. No source recordings or samples."""
import argparse
import subprocess
import numpy as np

RATE = 44100
BEAT = 60 / 106
BARS = 16
DURATION = BARS * 4 * BEAT + 1.25
RNG = np.random.default_rng(2214)
TAU = 2 * np.pi


def render():
    mix = np.zeros((round(DURATION * RATE), 2))
    chords = [(50, 57, 60, 64, 65), (46, 53, 57, 60, 64),
              (41, 53, 57, 60, 67), (48, 55, 58, 62, 65)]
    motifs = [(0.5, 69), (1.25, 72), (2.25, 76), (3.0, 74),
              (4.5, 72), (5.5, 69), (6.25, 67), (7.25, 65),
              (8.5, 67), (9.25, 69), (10.25, 72), (11.0, 76),
              (12.5, 74), (13.25, 72), (14.0, 67), (15.0, 69)]

    def time(length):
        return np.arange(round(length * RATE)) / RATE

    def add(at, signal, gain, pan=0):
        start = round(at * RATE)
        end = min(len(mix), start + len(signal))
        if end <= start:
            return
        mix[start:end] += (signal[:end-start] * gain)[:, None] * np.sqrt([(1-pan)/2, (1+pan)/2])

    def tone(midi, length, kind):
        t = time(length)
        f = 440 * 2 ** ((midi - 69) / 12)
        phase = TAU * f * t
        if kind == 'pad':
            y = np.sin(phase) + .25*np.sin(phase*2.002) + .09*np.sin(phase*3.005)
            envelope = np.minimum(1, t/.22) * np.minimum(1, (length-t)/.52)
        elif kind == 'bass':
            y = np.sin(phase) + .18*np.sin(phase*2)
            envelope = np.minimum(1, t/.016) * np.exp(-3.3*t)
        else:
            y = np.sin(phase + .45*np.sin(phase*2)*np.exp(-8*t)) + .16*np.sin(phase*3.003)*np.exp(-4*t)
            envelope = np.minimum(1, t/.012) * np.exp(-3.7*t)
        return y * envelope * np.minimum(1, (length-t)/.045)

    for bar in range(BARS):
        chord = chords[bar % len(chords)]
        start = bar * 4 * BEAT
        for i, note in enumerate(chord[1:]):
            add(start + .034*i, tone(note, 4.6*BEAT, 'pad'), .041, (i-1.5)*.36)
        for beat in (0, 1.5, 2.5):
            add(start + beat*BEAT, tone(chord[0]-12+(7 if beat==2.5 else 0), .56, 'bass'), .18)
        for i, offset in enumerate((.75, 1.75, 2.75, 3.5)):
            add(start+offset*BEAT, tone(chord[1+i]+12, .65, 'lead'), .045, (-1)**i*.42)
        if 1 <= bar < 15:
            t = time(.27)
            kick = np.sin(TAU*(46*t + 52*(1-np.exp(-37*t))/37))*np.exp(-17*t)*np.minimum(1,t/.004)
            for beat in (0, 2, 3.5) if bar%4==2 else (0, 2):
                add(start+beat*BEAT, kick, .29 if beat in (0,2) else .13)
            t = time(.15)
            noise = RNG.standard_normal(len(t))
            filtered = np.diff(noise, prepend=noise[0])
            snare = (.17*filtered + .35*np.sin(TAU*172*t))*np.exp(-27*t)*np.minimum(1,t/.004)
            for beat in (1, 3):
                add(start+beat*BEAT, snare, .12, -.12)
            for step in range(8):
                t = time(.07)
                n = RNG.standard_normal(len(t))
                hat = np.diff(n, prepend=n[0])*np.exp(-62*t)*np.minimum(1,t/.002)
                add(start+(step*.5+(.045 if step%2 else 0))*BEAT, hat, .019 if step%2 else .01, .4)
    for cycle in (1, 2, 3):
        for i, (offset, note) in enumerate(motifs):
            if cycle==3 and offset>13.25:
                continue
            start = (16*cycle+offset)*BEAT
            pan = .24 * (-1)**i
            y = tone(note, .96, 'lead')
            add(start,y,.104,pan)
            add(start+.75*BEAT,y,.025,-pan)
            add(start+1.5*BEAT,y,.012,pan)
    t = np.arange(len(mix))/RATE
    mix *= (np.minimum(1, t/.65) * np.clip((DURATION-t)/2.2,0,1))[:,None]
    return np.tanh(mix * 2.05) * .94


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output')
    args = parser.parse_args()
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-f','f32le','-ar',str(RATE),
                    '-ac','2','-i','pipe:0','-c:a','libmp3lame','-q:a','4',
                    '-metadata','title=After the Rain','-metadata','artist=Arris Studio',
                    '-metadata','copyright=CC0 1.0','-metadata','comment=Original synthesized instrumental; no external samples.',
                    args.output],input=render().astype('<f4').tobytes(),check=True)
