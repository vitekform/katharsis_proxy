#!/usr/bin/env python3
"""Build the complete Katharsis manifest after the parallel uploads finish.

Version lines follow paper/pom.xml and neoforge/build.gradle. The Velocity
proxy has no Minecraft version selector; it is a separate platform.
"""
import json
import re
import sys

TARGETS = {
    ('proxy', None): ('velocity', None),
    ('paper', None): ('paper', '1.21.x'),
    ('paper', '26'): ('paper', '26.x'),
    ('neoforge', None): ('neoforge', '1.21.1-1.21.10'),
    ('neoforge', '1.21.11'): ('neoforge', '1.21.11'),
    ('neoforge', '26'): ('neoforge', '26.x'),
}


def manifest(release, require_complete=True):
    variants = []
    seen = set()
    for file in release['files']:
        if file['status'] != 'READY':
            raise ValueError('Unfinished upload: ' + file['filename'])
        name = file['filename']
        match = re.fullmatch(r'katharsis_(proxy|paper|neoforge)-.+?(?:-mc([0-9.]+))?\.jar', name)
        if not match or match.groups() not in TARGETS:
            raise ValueError('Unknown Katharsis artifact: ' + name)
        platform, version = TARGETS[match.groups()]
        key = platform + ('-' + version if version else '')
        if key in seen:
            raise ValueError('Duplicate Katharsis target: ' + key)
        seen.add(key)
        selection = dict(platform=platform)
        if version:
            selection['minecraft_version'] = version
        variants.append(dict(key=key, selection=selection, files=[name], defaultFile=name))
    if not variants or (require_complete and len(variants) != len(TARGETS)):
        raise ValueError('Missing Katharsis builds; wait for all upload jobs')
    return dict(variants=variants)


if __name__ == '__main__':
    try:
        print(json.dumps(manifest(json.load(sys.stdin)), indent=2))
    except (ValueError, KeyError) as error:
        sys.exit(str(error))
