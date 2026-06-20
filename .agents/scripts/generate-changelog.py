#!/usr/bin/env python3
"""
Regenerates CHANGELOG.md from the full git history.

Format: each commit gets its own ### [hash] - date section.
Files are grouped by Added/Changed/Removed.

Usage:
  python3 .agents/scripts/generate-changelog.py
  python3 .agents/scripts/generate-changelog.py --dry-run
  python3 .agents/scripts/generate-changelog.py --output path/CHANGELOG.md
"""
import re, os, subprocess, argparse, sys
from collections import OrderedDict

ROOT      = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
CHANGELOG = os.path.join(ROOT, 'CHANGELOG.md')
SKIP_TYPES = {'chore', 'ci', 'test', 'docs', 'style', 'build', 'wip', 'merge', 'bump'}
MAX_FILES = 20


def git(*args):
    return subprocess.run(list(args), capture_output=True, text=True, cwd=ROOT).stdout


def parse_subject(subject):
    m = re.match(r'^(\w+)(?:\(([^)]+)\))?(!)?:\s*(.+)$', subject.strip())
    if m:
        ctype, scope, bang, desc = m.groups()
        return ctype.lower(), (scope or ''), desc.strip(), bool(bang)
    return None, '', subject.strip(), False


def commits_from_git():
    raw = git('git', 'log', '--reverse',
              '--format=<COMMIT>%H%x1f%h%x1f%s%x1f%ad%x1f%b',
              '--date=format:%Y-%m-%d')
    for block in raw.split('<COMMIT>'):
        block = block.strip()
        if not block:
            continue
        parts = block.split('\x1f', 4)
        if len(parts) < 4:
            continue
        full_hash  = parts[0].strip()
        short_hash = parts[1].strip()
        subject    = parts[2].strip()
        date       = parts[3].strip()
        body       = parts[4].strip() if len(parts) == 5 else ''
        body       = re.sub(r'<COMMIT>.*', '', body, flags=re.DOTALL).strip()
        body_lines = [
            ln for ln in body.splitlines()
            if ln.strip() and not re.match(r'^(Co-Authored-By|Signed-off-by):', ln, re.I)
        ]
        if full_hash and short_hash and subject:
            yield (full_hash, short_hash, subject, date, '\n'.join(body_lines))


def get_file_changes(full_hash):
    """Get Added/Changed/Removed files directly from git."""
    changes = {'Added': [], 'Changed': [], 'Removed': []}
    raw = git('git', 'diff-tree', '--no-commit-id', '-r', '--name-status', full_hash)
    for line in raw.strip().splitlines():
        if not line.strip():
            continue
        parts = line.split('\t', 1)
        if len(parts) < 2:
            continue
        status, fpath = parts[0].strip(), parts[1].strip()
        if status.startswith('A'):
            changes['Added'].append(fpath)
        elif status.startswith('D'):
            changes['Removed'].append(fpath)
        else:
            changes['Changed'].append(fpath)
    return changes


def tags_by_hash():
    raw = git('git', 'tag', '-l', '--sort=version:refname').strip()
    result = {}
    for tag in raw.splitlines():
        tag = tag.strip()
        if not tag:
            continue
        h = git('git', 'rev-list', '-n', '1', tag).strip()
        if h:
            result[h] = tag
    return result


def bucket_commits(commits_iterable, tag_map):
    commits = list(commits_iterable)
    tag_at = {}
    for i, c in enumerate(commits):
        if c[0] in tag_map:
            tag_at[i] = (tag_map[c[0]], c[3])
    tag_indices = sorted(tag_at.keys())

    def version_for(i):
        for ti in tag_indices:
            if i <= ti:
                return tag_at[ti]
        return ('Unreleased', '')

    raw_buckets = {}
    for i, (full, short, subj, date, body) in enumerate(commits):
        version, vdate = version_for(i)
        if version not in raw_buckets:
            raw_buckets[version] = {'date': vdate, 'commits': []}
        raw_buckets[version]['commits'].append(
            {'full_hash': full, 'short_hash': short, 'subject': subj, 'date': date, 'body': body}
        )

    ordered = OrderedDict()
    if 'Unreleased' in raw_buckets:
        ordered['Unreleased'] = raw_buckets.pop('Unreleased')
    else:
        ordered['Unreleased'] = {'date': '', 'commits': []}
    for v in reversed(list(raw_buckets.keys())):
        ordered[v] = raw_buckets[v]
    return ordered


def render_commit(commit):
    short = commit['short_hash']
    lines = [f'### [{short}] — {commit["date"]}', '', f'**{commit["subject"]}**', '']

    if commit['body']:
        for ln in commit['body'].splitlines()[:4]:
            if ln.strip():
                lines.append(f'> {ln.strip()}')
        lines.append('')

    changes = get_file_changes(commit['full_hash'])
    has_files = False
    for section in ['Added', 'Changed', 'Removed']:
        file_list = changes.get(section, [])
        if not file_list:
            continue
        has_files = True
        lines.extend([f'#### {section}', ''])
        for fpath in file_list[:MAX_FILES]:
            lines.append(f'- `{fpath}`')
        if len(file_list) > MAX_FILES:
            lines.append(f'- _…and {len(file_list) - MAX_FILES} more_')
        lines.append('')

    if not has_files:
        lines.extend(['_No file changes detected._', ''])
    return lines


def render(buckets):
    out = [
        '# Changelog', '',
        'All notable changes to this project will be documented in this file.', '',
        'The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).', '',
    ]
    for version, data in buckets.items():
        out.append(f'## [{version}]' if version == 'Unreleased' else f'## [{version}] - {data["date"]}')
        out.append('')
        if not data['commits']:
            out.extend(['_No commits yet._', '', '---', ''])
            continue
        for commit in reversed(data['commits']):
            ctype, _, _, _ = parse_subject(commit['subject'])
            if ctype in SKIP_TYPES:
                continue
            out.extend(render_commit(commit))
            out.extend(['---', ''])
    return '\n'.join(out).rstrip() + '\n'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--output',  default=CHANGELOG)
    p.add_argument('--dry-run', action='store_true')
    args = p.parse_args()

    commits = commits_from_git()
    tag_map = tags_by_hash() if not None else {}
    buckets = bucket_commits(commits, tag_map)
    content = render(buckets)

    if args.dry_run:
        print(content)
        return

    with open(args.output, 'w') as f:
        f.write(content)
    print(f'[changelog] Written: {args.output}')


if __name__ == '__main__':
    main()
