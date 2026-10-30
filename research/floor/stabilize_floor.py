"""Post-hoc shape-SVM restriction; exploratory reused-data spatial validation."""
import argparse
import csv
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path

import joblib
import numpy as np

import improve_floor as v2

ROOT = v2.ROOT
OUT = ROOT/'v3/artifacts'


def select_restricted(trials):
    eligible = [t for t in trials if t['config']['family'] == 'shape'
                and t['config']['target'] == 'floor'
                and t['config']['algorithm'] == 'svm'
                and t['config']['c'] in [1., 10.]
                and isinstance(t['config']['augment'], bool)]
    if not eligible:
        raise ValueError('No eligible shape-SVM candidate')
    return max(eligible, key=lambda t: t['score'])


def checked_predict(payload, vectors):
    if payload['feature_version'] != v2.FEATURE_VERSION:
        raise ValueError('Incompatible feature version; use the matching extractor')
    if vectors.ndim != 2 or vectors.shape[1] != payload['feature_dimensions']:
        raise ValueError('Incompatible feature dimensions')
    return v2.predict_vectors(payload, vectors)


def run():
    OUT.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone(timedelta(hours=9))).isoformat()
    with (ROOT/'data/metadata.csv').open() as f:
        rows = sorted(csv.DictReader(f), key=lambda r: int(r['track_id']))
    parts = v2.base.partition_records(rows)
    common = sorted(parts['development']+parts['test'], key=lambda r: int(r['track_id']))
    extra = parts['extra_upper']
    # Revalidate source bytes/front-end version before reusing numerical features.
    values = v2.feature_cache(common+extra)
    common_x = {k: a[:1800] for k, a in values.items()}
    extra_x = {k: a[1800:] for k, a in values.items()}
    truth = np.array([v2.base.floor_label(r['position']) for r in common])
    with (v2.OUT/'baseline-spatial-predictions.csv').open() as f:
        baseline_rows = list(csv.DictReader(f))
    assert [r['track_id'] for r in baseline_rows] == [r['track_id'] for r in common]
    baseline = np.array([r['predicted'] for r in baseline_rows])
    prediction = np.empty(len(common), dtype='U5')
    folds = []
    selection_sources = {}
    for fold in v2.nested_folds(common):
        held = fold['distance']
        source = v2.OUT/f'outer-{held}m-selection.json'
        selection_sources[str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
        trials = json.loads(source.read_text())['trials']
        selected = select_restricted(trials)
        config = selected['config']
        model = v2.fit_subset(config, fold['train'], common, extra, common_x, extra_x)
        destination = OUT/f'fold-{held}m.joblib'
        joblib.dump(model, destination, compress=3)
        restored = joblib.load(destination)
        pred = checked_predict(restored, common_x[config['family']][fold['test']])
        prediction[fold['test']] = pred
        test_rows = [common[i] for i in fold['test']]
        result = {'distance_m': held, 'selection': selected,
                  'baseline': v2.measure(truth[fold['test']], baseline[fold['test']], test_rows),
                  'improved': v2.measure(truth[fold['test']], pred, test_rows)}
        folds.append(result)
        v2.base.write_json(OUT/f'fold-{held}m-metrics.json', result)
        v2.save_predictions(OUT/f'fold-{held}m-predictions.csv', test_rows, truth[fold['test']], pred)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    result = {'evaluation_status': 'POST-HOC exploratory reused-data spatial revalidation; family restricted after inspecting V2; no independent test',
              'baseline': v2.measure(truth, baseline, common),
              'improved': v2.measure(truth, prediction, common), 'folds': folds}
    v2.base.write_json(OUT/'comparison.json', result)
    v2.save_predictions(OUT/'spatial-predictions.csv', common, truth, prediction)
    source = v2.OUT/'full-fit-selection.json'
    selection_sources[str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    selected = select_restricted(json.loads(source.read_text())['trials'])
    model = v2.fit_subset(selected['config'], np.arange(len(common)), common, extra, common_x, extra_x)
    destination = OUT/'floor-model-v3.joblib'
    joblib.dump(model, destination, compress=3)
    np.testing.assert_array_equal(checked_predict(model, common_x['shape']),
                                  checked_predict(joblib.load(destination), common_x['shape']))
    card = {'model_id':'knok-floor-snu-v3', 'status':'trained_research_candidate',
            'started_at_kst':started, 'completed_at_kst':datetime.now(timezone(timedelta(hours=9))).isoformat(),
            'selected':selected, 'model_sha256':hashlib.sha256(destination.read_bytes()).hexdigest(),
            'feature_version':v2.FEATURE_VERSION, 'feature_dimensions':model['feature_dimensions'],
            'training_recordings':model['training_recordings'], 'labels':v2.base.LABELS_KO,
            'selection_sources_sha256':selection_sources, 'evaluation':result,
            'limits':['Post-hoc model-family restriction can inflate reported performance; all ZIP data were examined.',
                      'Outer models exclude corresponding source distance; final all-data candidate is NOT independently evaluated.',
                      'One building/phone, four known sound types; no unknown/noise rejection or calibrated confidence.',
                      'No vibration features, new-building/KNOK-device validation, app/firmware or GitHub deployment.']}
    v2.base.write_json(OUT/'model-card.json', card)
    v2.base.write_json(OUT/'status.json', {'phase':'complete','accuracy':result['improved']['accuracy'],
                                         'above_to_below':result['improved']['above_to_below_count']})
    print(json.dumps({'complete':True, 'overall':result['improved'], 'final_selection':selected},ensure_ascii=False,indent=2))


def predict(path, model_path):
    model = joblib.load(model_path)
    if model['feature_version'] != v2.FEATURE_VERSION:
        raise ValueError('Incompatible feature version; use the matching extractor')
    features = v2.audio_features(path)
    label = str(checked_predict(model, features[model['config']['family']][None,:])[0])
    return {'label':label, 'label_ko':v2.base.LABELS_KO[label], 'model':str(model_path),
            'scope':'Exploratory one-building audio-only research candidate; real KNOK performance unvalidated'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predict', type=Path)
    parser.add_argument('--model', type=Path, default=OUT/'floor-model-v3.joblib')
    args = parser.parse_args()
    if args.predict:
        print(json.dumps(predict(args.predict, args.model),ensure_ascii=False,indent=2))
    else:
        run()
