#!/usr/bin/env python3
"""Conservative Simplified Chinese UI source patch for AndroidHarness.

Run from an AndroidHarness source checkout: python3 zhcn/apply_zhcn.py --root .
No external packages or network requests. Source modifications are restricted to
app/src/main/java/com/androidharness/app/ui/**/*.kt. This is NOT a model/API patch.
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sys
from pathlib import Path

# Ordinary Kotlin double-quoted strings. Do not touch triple-quoted strings,
# escaped content, @Strings, or generated/compiled resources.
KOTLIN_STRING = re.compile(r'(?<!")"(?!")((?:\\.|[^"\\\r\n])*)"(?!")')
INTERPOLATION = re.compile(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*')
LOCALIZATION_CONTEXT = re.compile(
    r'(?:\bText\s*\(\s*(?:text\s*=\s*)?|'
    r'\b(?:contentDescription|placeholder)\s*=\s*)$',
    re.DOTALL,
)

def load_dictionary(filename: Path):
    d = {}
    duplicate_warnings = []
    for no, line in enumerate(filename.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip() or line.startswith('#'):
            continue
        if '\t' not in line:
            raise ValueError(f'{filename.name}:{no}: expected <english>\\t<Chinese>')
        old, new = line.split('\t', 1)
        if not old.strip() or not new.strip():
            raise ValueError(f'{filename.name}:{no}: empty translation')
        if old in d and d[old] != new:
            duplicate_warnings.append(f'{filename.name}:{no}: duplicate: {old!r}')
        if sorted(INTERPOLATION.findall(old)) != sorted(INTERPOLATION.findall(new)):
            raise ValueError(f'{filename.name}:{no}: interpolation mismatch for {old!r}')
        d[old] = new
    return d, duplicate_warnings

def should_translate(src: str, match: re.Match, old: str) -> bool:
    # Long human-facing labels/sentences, including spaces, are low-risk UI
    # literals. Isolated words might be IDs / enum discriminators: only edit
    # them in explicit text-display contexts, not all occurrences.
    if ' ' in old or '　' in old or len(old) >= 15 or any(c in old for c in '…？！?。.：:·'):
        return True
    preceding = src[max(0, match.start() - 240):match.start()]
    # Keep the last comma/braces from influencing a Text argument inside a
    # multiline call. The final portion must be a display argument.
    return bool(LOCALIZATION_CONTEXT.search(preceding))

def kotlin_string(new: str) -> str:
    # The dictionary contains Kotlin-source placeholders (e.g. $providerName).
    # Preserve these, escape literal double quotes, and never introduce newlines.
    if '\n' in new or '\r' in new:
        raise ValueError(f'newline in dictionary value: {new!r}')
    return '"' + new.replace('\\', '\\\\').replace('"', '\\"') + '"'

def translate_file(source: str, dictionary: dict[str, str]):
    stats = collections.Counter()
    translated_values = set(dictionary.values())
    def sub(match: re.Match):
        old = match.group(1)
        if old not in dictionary or old in translated_values:
            return match.group(0)
        if not should_translate(source, match, old):
            return match.group(0)
        stats[old] += 1
        return kotlin_string(dictionary[old])
    new_source = KOTLIN_STRING.sub(sub, source)
    return new_source, stats

def main():
    parser = argparse.ArgumentParser(description='Apply AndroidHarness Simplified Chinese UI translation')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent,
                        help='AndroidHarness Git checkout root')
    parser.add_argument('--dry-run', action='store_true', help='Report without changing files')
    parser.add_argument('--report', type=Path, default=None, help='Optional JSON report')
    parser.add_argument('--min-hits', type=int, default=20,
                        help='Fail if fewer than this many UI strings matched (upstream drift guard)')
    args = parser.parse_args()
    root = args.root.resolve()
    ui_dir = root / 'app/src/main/java/com/androidharness/app/ui'
    if not ui_dir.is_dir() or not (root / 'settings.gradle.kts').is_file():
        parser.error(f'Not an AndroidHarness source root: {root}')
    dic, warnings = load_dictionary(Path(__file__).with_name('translations.tsv'))
    for warning in warnings:
        print('WARNING:', warning, file=sys.stderr)
    all_stats = collections.Counter()
    changed = []
    already_localized = 0
    for path in sorted(ui_dir.rglob('*.kt')):
        original = path.read_text(encoding='utf-8')
        already_localized += sum(1 for translated in dic.values() if '"' + translated + '"' in original)
        updated, stats = translate_file(original, dic)
        if updated != original:
            changed.append({'file': str(path.relative_to(root)), 'replacements': sum(stats.values())})
            all_stats.update(stats)
            if not args.dry_run:
                path.write_text(updated, encoding='utf-8', newline='')
    total = sum(all_stats.values())
    report = {
        'patch': 'AndroidHarness zh-CN UI (source-string substitution)',
        'mode': 'dry-run' if args.dry_run else 'apply',
        'dictionary_entries': len(dic),
        'total_replacements': total,
        'previously_localized_strings_detected': already_localized,
        'changed_files': changed,
        'replaced_strings': dict(all_stats.most_common()),
        'limitations': [
            'Only exact known labels in ui/**/*.kt are translated.',
            'Not a locale toggle; strings become Chinese regardless of phone locale.',
            'Dynamic English from server, model, plugins and non-ui directories remains English.',
            'Some single-token strings intentionally remain English to protect internal IDs.',
        ],
    }
    print(f"Entries: {len(dic)} | UI files changed: {len(changed)} | replacements: {total}")
    for entry in changed[:12]:
        print(f"  {entry['file']}: {entry['replacements']}")
    if len(changed) > 12:
        print(f'  ...and {len(changed) - 12} more files')
    if args.report:
        target = args.report if args.report.is_absolute() else root / args.report
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f'Report: {target}')
    if total + already_localized < args.min_hits:
        print('WARNING: Fewer matches than expected. You may be using an incompatible upstream version, '
              'or the patch has already been applied.', file=sys.stderr)
        return 2
    return 0

if __name__ == '__main__':
    sys.exit(main())
