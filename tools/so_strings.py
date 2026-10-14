#!/usr/bin/env python3
"""Pull diagnostic strings out of a compiled Python extension.

Rust extension modules embed a lot of useful provenance: source paths, crate
names and versions, panic messages, and the constants they were built with.
This prints the interesting subsets so we can tell which upstream a binary came
from - and, for the sanma module, whether the obs-size constants are visible.

Usage: python3 so_strings.py <file.so>
"""
import re
import sys


def main():
    path = sys.argv[1]
    data = open(path, 'rb').read()
    runs = re.findall(rb'[\x20-\x7e]{5,}', data)
    seen = set()
    uniq = []
    for r in runs:
        t = r.decode('ascii', 'replace')
        if t not in seen:
            seen.add(t)
            uniq.append(t)

    print(f'{path}: {len(data)} bytes, {len(uniq)} unique strings')

    def show(title, pat, limit=30):
        print(f'\n=== {title} ===')
        n = 0
        for t in uniq:
            if re.search(pat, t, re.I):
                print(f'  {t[:170]}')
                n += 1
                if n >= limit:
                    print('  ...')
                    break
        if n == 0:
            print('  (none)')

    show('source paths', r'\.rs\b|/src/|src\\')
    show('crate / package names', r'^[a-z0-9_]+(-[a-z0-9_]+)+$|libriichi')
    show('obs / action constants', r'obs_shape|encode_obs|oracle_obs|ACTION_SPACE|GRP_SIZE')
    show('sanma markers', r'sanma|nukidora|3p|775')
    show('panic / assert', r'assertion|unreachable|panic|index out of')
    show('python module metadata', r'\.so$|PyInit|__version__|[0-9]+\.[0-9]+\.[0-9]+')


if __name__ == '__main__':
    main()
