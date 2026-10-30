"""Freeze source-annotated public cohorts before inference, then download silently."""
import concurrent.futures
import csv
from datetime import datetime
import hashlib
import json
from pathlib import Path
import random
import subprocess
import sys
import urllib.request
import wave
from zoneinfo import ZoneInfo
HERE=Path(__file__).resolve().parent;IMP=HERE.parent;OLD=IMP/'wednesday_two_models_20261028/tuesday_public_20261027';DATA=HERE/'data/new_public';DATA.mkdir(parents=True,exist_ok=True)
SEED=202610280500;COMMIT='8b7dd51055ffe422f3e88f3e8be6b46056874ef7';MIRROR='ccf1acaff12f3f4a4c10052dddafbb6d22152c9b'
LICENSES={'http://creativecommons.org/publicdomain/zero/1.0/','http://creativecommons.org/licenses/by/3.0/'}


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def earlier():
    ids=set();authors=set();hashes=set();audit=[]
    files=sorted(OLD.glob('**/data/*manifest*.json'))+[IMP/'silent_root_cause_20261025/ast-data-frozen.json']
    files += [IMP/rel for rel in ('live_comparison_20261024_evening/final_d/trials-frozen.json','live_comparison_20261024_evening/reserve_e/trials-frozen.json','live_validation_20261025/final36-frozen.json')]
    devmeta=json.loads((OLD/'round11/data/FSD-dev-metadata.json').read_text());evalmeta=json.loads((OLD/'round10/data/FSD-eval-metadata.json').read_text());allmeta={**devmeta,**evalmeta}
    for path in files:
        obj=json.loads(path.read_text());rows=obj.get('rows',obj.get('current',[])+obj.get('prior',[])) if isinstance(obj,dict) else obj
        audit.append({'path':str(path),'sha256':sha(path),'count':len(rows)})
        for r in rows:
            for key in ('track_id','parent','parent_source_id','source_freesound_id'):
                value=str(r.get(key,''));ids.add(value)
                for prefix in ('freesound_','fsd_','esc50_'):
                    if value.startswith(prefix):value=value[len(prefix):]
                if value.endswith('.wav') and value[:-4].isdigit():value=value[:-4]
                ids.add(value)
                if value in allmeta:authors.add(allmeta[value]['uploader'])
            for key in ('uploader','uploader_key'):
                if r.get(key):authors.add(str(r[key]).removeprefix('FSD:'))
            if r.get('metadata',{}).get('uploader'):authors.add(r['metadata']['uploader'])
            for key in ('file_hash','file_sha256','sha256','pcm','pcm_sha256','source_sha','source_sha256'):
                if r.get(key):hashes.add(r[key])
    return ids,authors,hashes,audit


def freeze():
    dest=DATA/'selected.json'
    if dest.exists():raise RuntimeError('Preserve frozen cohort')
    used,authors,_,audit=earlier();ratings=json.loads((HERE/'data/source_metadata/pp_pnp_ratings_FSD50K.json').read_text());rng=random.Random(SEED);pools={}
    allowed={
      'Hammer':{'Hammer','Tools','Wood','Knock'},
      'Knock':{'Knock','Door','Wood','Domestic_sounds_and_home_sounds'},
      'Walk_and_footsteps':{'Walk_and_footsteps','Run','Wood','Squeak','Crumpling_and_crinkling','Crackle','Rustle','Rustle_and_rustling'},
      'Coin_(dropping)':{'Coin_(dropping)','Domestic_sounds_and_home_sounds','Chink_and_clink','Metal'},
      'Wind':{'Wind','Wind_noise_(microphone)','Rustle','Rustle_and_rustling'},
      'Water':{'Water','Liquid','Stream','Pour','Fill_(with_liquid)','Splash_and_splatter','Gurgling','Trickle_and_dribble','Drip','Rain'},
      'Chirp_and_tweet':{'Chirp_and_tweet','Bird','Wild_animals','Animal','Bird_vocalization_and_bird_call_and_bird_song'},
      'Speech':{'Speech','Human_voice','Male_speech_and_man_speaking','Female_speech_and_woman_speaking','Child_speech_and_kid_speaking','Conversation','Narration_and_monologue'}}
    for split,folder in [('dev','round11'),('eval','round10')]:
        meta=json.loads((OLD/folder/'data'/f'FSD-{split}-metadata.json').read_text())
        for r in csv.DictReader((OLD/folder/'data'/f'FSD-{split}.csv').open()):
            fid=r['fname'];labels=r['labels'].split(',');m=meta[fid]
            if fid in used or m['uploader'] in authors or m['license'] not in LICENSES:continue
            for family,acceptable in allowed.items():
                if family not in labels or not set(labels)<=acceptable:continue
                mid=dict(zip(labels,r['mids'].split(',')))[family];votes=[v for v in ratings.get(fid,{}).get(mid,[]) if v>=0]
                if len(votes)<2 or any(v!=1 for v in votes):continue
                pools.setdefault(family,[]).append({'kind':'FSD','track_id':fid,'family':family,'split':split,'parent':'freesound_'+fid,'uploader':m['uploader'],'uploader_key':'FSD:'+m['uploader'],'title':m['title'],'source_labels':labels,'mid':mid,'quality_votes':votes,'license':m['license'],'attribution_url':'https://freesound.org/people/'+m['uploader']+'/sounds/'+fid+'/',
                    'source_url':f'https://huggingface.co/datasets/Fhrozen/FSD50k/resolve/{MIRROR}/clips/{split}/{fid}.wav'})
    # One recording per uploader over all families, disjoint people between new fit/test.
    chosen=[];selected_authors=set();availability={k:{'clips':len(v),'authors':len({r['uploader'] for r in v})} for k,v in pools.items()}
    def choose_fsd(role,family,label,count):
        candidates=sorted(pools.get(family,[]),key=lambda r:(r['uploader'],r['track_id']));rng.shuffle(candidates);picked=[]
        for r in candidates:
            if r['uploader'] in selected_authors:continue
            selected_authors.add(r['uploader']);picked.append(dict(r,role=role,label=label,domain='new_FSD'))
            if len(picked)==count:break
        if len(picked)!=count:raise RuntimeError(f'Insufficient disjoint strong annotations: {role}/{family}:{len(picked)}/{count}')
        chosen.extend(picked)
    print('AVAILABILITY',json.dumps(availability),flush=True)
    # Test metadata chosen first and locked before ANY inference on these WAVs.
    for family,label,count in [('Walk_and_footsteps','footstep',20),('Hammer','hammering',10),('Knock','hammering',10),('Coin_(dropping)','instant_impact',10),('Water','normal_or_unknown',10),('Speech','normal_or_unknown',10)]:choose_fsd('sound_test',family,label,count)
    # Only21 eligible unseen footstep uploaders remain: retain20 for the test;
    # use existing source-grouped footsteps for fitting rather than weakening labels.
    for family,label,count in [('Hammer','hammering',8),('Knock','hammering',24),('Coin_(dropping)','instant_impact',16),('Water','normal_or_unknown',8),('Speech','normal_or_unknown',8)]:choose_fsd('fit',family,label,count)
    conflicting=json.loads((OLD/'source-integrity/upstream-conflict-correction.json').read_text())['conflicting_blobs'];forbidden={r['track_id'] for group in conflicting.values() for r in group};metadata=list(csv.DictReader((OLD/'data/source-metadata.csv').open()))
    for role,kind,label,count in [('sound_test','CD','dragging_furniture',20),('sound_test','VC','vacuum_cleaner',20),('sound_test','MB','instant_impact',10),('fit','CD','dragging_furniture',16),('fit','VC','vacuum_cleaner',16),('fit','MB','instant_impact',16)]:
        candidates=[r for r in metadata if r['building'] in ('B','C') and r['type']==kind and r['track-id'] not in used and r['track-id'] not in forbidden and (r['noisy']=='0' or (kind=='VC' and r['noisy']=='UNKNOWN'))];candidates.sort(key=lambda r:r['track-id']);rng.shuffle(candidates)
        if len(candidates)<count:raise RuntimeError('Not enough unused apartment events')
        for r in candidates[:count]:
            fid=r['track-id'];used.add(fid);folder='BDML-APT' if r['building']=='B' else 'CS-APT';chosen.append({'kind':'APT','track_id':fid,'parent':'apartment_'+fid,'role':role,'label':label,'domain':'new_apt_'+r['building'],'building':r['building'],'position':r['position'],'family':kind,'source_labels':[kind],'source_noise_metadata':r['noisy'],
              'license':'upstream academic research dataset; retain repository attribution; no redistribution authorized here','attribution_url':'https://github.com/yodacatmeow/indoor-noise','source_url':f'https://raw.githubusercontent.com/yodacatmeow/indoor-noise/{COMMIT}/indoor-noise-set/{folder}/audio/{fid}'})
    report={'status':'frozen_before_download_and_inference','frozen_at':datetime.now(ZoneInfo('Asia/Seoul')).isoformat(),'seed':SEED,'selection_uses_model_predictions':False,'rules':{'strong_annotation':'At least2 known PP votes and no PNP/NP; -1 ignored','pure_label_sets':{k:sorted(v) for k,v in allowed.items()},'holdout_repeated_impact_mix':'10 annotated hammer +10 annotated knocking','old_ids_uploaders_excluded':True,'one_new_fsd_per_uploader':True,'known_conflict_files_excluded':len(forbidden),'scope':'New source events, prior B/C buildings; public proxy, not KNOK real-device or new-building accuracy'},'availability':availability,'prior_manifest_audit':audit,'rows':chosen}
    dest.write_text(json.dumps(report,ensure_ascii=False,indent=2));print('COHORT_FROZEN',len(chosen),'test',sum(r['role']=='sound_test' for r in chosen),'fit',sum(r['role']=='fit' for r in chosen),'sha',sha(dest),flush=True)


def fetch(row):
    suffix='.wav' if row['kind']=='FSD' else '.m4a';raw=DATA/'raw'/(row['kind']+'-'+row['track_id']+suffix);raw.parent.mkdir(exist_ok=True)
    if not raw.exists():
        last=None
        for attempt in range(3):
            try:
                req=urllib.request.Request(row['source_url'],headers={'User-Agent':'KNOK-private-research/1'})
                with urllib.request.urlopen(req,timeout=35) as response:body=response.read()
                if len(body)<1024:raise ValueError('Audio response too small')
                raw.write_bytes(body);break
            except Exception as e:last=e
        if not raw.exists():raise RuntimeError(str(last))
    wav=DATA/'wav'/(row['kind']+'-'+row['track_id']+'.wav');wav.parent.mkdir(exist_ok=True)
    if not wav.exists():subprocess.run(['/opt/homebrew/bin/ffmpeg','-nostdin','-hide_banner','-loglevel','error','-threads','1','-i',str(raw),'-ac','1','-ar','16000','-c:a','pcm_s16le','-y',str(wav)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    with wave.open(str(wav),'rb') as w:pcm=w.readframes(w.getnframes());n=w.getnframes()
    if not 4000<=n<=16000*60:raise ValueError('Predeclared duration range0.25–60seconds')
    return dict(row,path=str(wav),source_sha=sha(raw),file_hash=sha(wav),pcm=hashlib.sha256(pcm).hexdigest(),sample_count=n,group=row.get('uploader_key',row['parent']),source_group='uploader:'+row['uploader'] if row.get('uploader') else 'new-event:'+row['parent'])


def download():
    dest=DATA/'manifest.json'
    if dest.exists():raise RuntimeError('Preserve completed download manifest')
    rows=json.loads((DATA/'selected.json').read_text())['rows'];result={};errors={}
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        tasks={pool.submit(fetch,r):i for i,r in enumerate(rows)}
        for f in concurrent.futures.as_completed(tasks):
            i=tasks[f]
            try:result[i]=f.result()
            except Exception as e:errors[i]=str(e)
            if (len(result)+len(errors))%25==0:print('NEW_AUDIO',len(result),'errors',len(errors),flush=True)
    _,_,oldhashes,_=earlier();seen={};valid=[]
    for i in range(len(rows)):
        if i in errors:continue
        r=result[i];aliases=set(r[k] for k in ('file_hash','pcm','source_sha'))
        if aliases&oldhashes:errors[i]='Content matches earlier source/PCM';continue
        if any(h in seen for h in aliases):errors[i]='Duplicate content in fresh cohort';continue
        for h in aliases:seen[h]=i
        valid.append(r)
    report={'source_selection_sha256':sha(DATA/'selected.json'),'rows':valid,'errors':[{'index':i,'track_id':rows[i]['track_id'],'role':rows[i]['role'],'reason':e} for i,e in sorted(errors.items())],'selection_after_scores':False,'old_content_overlap_retained':0}
    dest.write_text(json.dumps(report,ensure_ascii=False,indent=2));print('NEW_PUBLIC_READY',len(valid),'test',sum(r['role']=='sound_test' for r in valid),'excluded',len(errors),'sha',sha(dest),flush=True)


if __name__=='__main__':
    {'freeze':freeze,'download':download}[sys.argv[1]]()
