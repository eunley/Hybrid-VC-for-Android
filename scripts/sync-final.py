#!/usr/bin/env python3
"""Run only after the production editor has finished. No network or archive creation."""
import argparse, pathlib, shutil, hashlib, json
p=argparse.ArgumentParser();p.add_argument('production',type=pathlib.Path);p.add_argument('--apply',action='store_true');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]; prod=a.production.resolve()
if prod==root or root in prod.parents or prod in root.parents: p.error('Source and destination must be separate trees')
files=[]
for name in ('src','res'):
 d=prod/name
 if not d.is_dir():p.error(f'Missing {name}')
 for f in sorted(d.rglob('*')):
  if f.is_symlink():p.error(f'Nested symlink needs review: {f.relative_to(prod)}')
  if f.is_file(): files.append((f,pathlib.Path(name)/f.relative_to(d)))
print(f'{len(files)} files; '+('applying' if a.apply else 'dry run; use --apply after editor finishes'))
if a.apply:
 for name in ('src','res'):
  if (root/name).exists():p.error(f'{name} exists; move old snapshot aside before syncing')
 records=[]
 for f,rel in files:
  data=f.read_bytes();dest=root/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
  records.append({'path':str(rel),'sha256':hashlib.sha256(data).hexdigest()})
 # Manifest is refreshed with final snapshot, never modified in production.
 shutil.copy2(prod/'AndroidManifest.xml',root/'AndroidManifest.xml')
 (root/'final-sync.json').write_text(json.dumps(records,indent=2)+'\n')
 print('Review secrets, licenses and translations again before creating any release archive.')
