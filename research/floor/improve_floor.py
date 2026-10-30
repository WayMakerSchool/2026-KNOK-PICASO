"""V2 audio-only research experiment with nested spatial revalidation."""
import argparse
from collections import Counter
import csv
from datetime import datetime, timezone, timedelta
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import time

import joblib
import librosa
import numpy as np
import scipy
from scipy.fft import dct
from scipy.ndimage import uniform_filter1d
import sklearn
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.svm import SVC
import soundfile as sf

import train_floor as base

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'v2/artifacts'
FEATURE_VERSION = 'normalized-event-spectrum-decay-v2'


def nested_folds(rows):
    distances = np.array([base.position_parts(r['position'])[1] for r in rows])
    if set(distances) != {0, 6, 12}:
        raise ValueError('Require the three common source distances')
    folds = []
    for held in [0, 6, 12]:
        a, b = [d for d in [0, 6, 12] if d != held]
        folds.append({'distance': held, 'train': np.flatnonzero(distances != held),
            'test': np.flatnonzero(distances == held),
            'inner': [(np.flatnonzero(distances == a), np.flatnonzero(distances == b)),
                      (np.flatnonzero(distances == b), np.flatnonzero(distances == a))]})
    return folds


def hash_inputs(paths):
    digest = hashlib.sha256()
    digest.update(FEATURE_VERSION.encode())
    for version in [np.__version__, librosa.__version__, scipy.__version__, sf.__version__]:
        digest.update(version.encode())
    for path in paths:
        digest.update(str(path).encode())
        with path.open('rb') as f:
            for chunk in iter(lambda: f.read(1024*1024), b''):
                digest.update(chunk)
    return digest.hexdigest()


@lru_cache(maxsize=1)
def mel_basis():
    return librosa.filters.mel(sr=16000, n_fft=1024, n_mels=64,
                              fmin=20, fmax=8000, dtype=np.float64)


def features_array(audio, rate):
    y = np.asarray(audio, dtype=np.float64)
    if y.ndim != 1 or rate != 16000 or len(y) < 16000:
        raise ValueError('Use mono 16000 Hz audio of at least one second')
    if not np.isfinite(y).all():
        raise ValueError('Non-finite samples')
    amplitude = float(np.max(np.abs(y)))
    if amplitude < 1e-7:
        raise ValueError('Cannot classify silent audio')
    raw_rms = float(np.sqrt(np.mean(y*y)))
    y = y / amplitude
    energy = uniform_filter1d(y*y, size=320)
    anchor = int(np.argmax(energy))
    start, stop = anchor - 2560, anchor + 38400
    event = y[max(0, start):min(len(y), stop)]
    event = np.pad(event, (max(0, -start), max(0, stop-len(y))))
    def spectrum(wave):
        return np.abs(librosa.stft(wave, n_fft=1024, hop_length=160))**2
    whole_power, event_power = spectrum(y), spectrum(event)
    whole = np.log(np.maximum(mel_basis() @ whole_power, 1e-10))
    local = np.log(np.maximum(mel_basis() @ event_power, 1e-10))
    local_shape = local - local.mean(axis=0, keepdims=True)
    def stats(values):
        return np.r_[values.mean(axis=1), values.std(axis=1)]
    cepstrum = dct(local, type=2, norm='ortho', axis=0)[1:20]
    cep_stats = np.r_[stats(cepstrum), np.diff(cepstrum, axis=1).std(axis=1)]
    frequencies = np.fft.rfftfreq(1024, 1/16000)
    bands = [20, 50, 100, 200, 400, 800, 1600, 3200, 6400, 8001]
    powers = np.array([event_power[(frequencies >= lo) & (frequencies < hi)].sum(axis=0)
                       for lo, hi in zip(bands[:-1], bands[1:])])
    fractions = powers / np.maximum(powers.sum(axis=0, keepdims=True), 1e-12)
    ratios = np.r_[stats(fractions), np.quantile(fractions, .9, axis=1)]
    tail = y[anchor:min(len(y), anchor+38400)]
    tail = np.pad(tail, (0, 38400-len(tail)))
    reference = max(float(np.mean(tail[:1600]**2)), 1e-12)
    edges = [0, .05, .1, .2, .4, .8, 1.6, 2.4]
    decay = [10*np.log10(max(float(np.mean(tail[int(lo*16000):int(hi*16000)]**2)), 1e-12)/reference)
             for lo, hi in zip(edges[:-1], edges[1:])]
    before = y[max(0, anchor-3200):anchor]
    decay.append(10*np.log10(max(float(np.mean(before**2)) if len(before) else 0., 1e-12)/reference))
    decay = np.array(decay)
    flatness = np.exp(np.mean(np.log(np.maximum(event_power, 1e-12)), axis=0)) / np.maximum(event_power.mean(axis=0), 1e-12)
    zcr = np.mean(np.diff(np.signbit(event)))
    temporal = np.array([np.log(max(float(np.sqrt(np.mean(y*y))), 1e-12)),
        np.log(max(float(np.median(energy)), 1e-12) / max(float(np.max(energy)), 1e-12)),
        float(np.mean(energy > .1*np.max(energy))), float(zcr),
        float(np.mean(flatness)), float(np.std(flatness))])
    shape = np.r_[stats(local_shape), cep_stats, ratios, decay, temporal]
    full = np.r_[stats(whole), stats(local), shape, np.log(max(raw_rms, 1e-12)), np.log(amplitude)]
    if not np.isfinite(full).all():
        raise ValueError('Non-finite features')
    return {'shape': shape.astype(np.float32), 'full': full.astype(np.float32), 'decay': decay}


def audio_features(path):
    audio, rate = sf.read(path, dtype='float64')
    return features_array(audio, rate)


def training_weights(rows, target):
    labels = [base.floor_label(r['position']) for r in rows]
    if target == 'joint':
        labels = [r['type']+'|'+label for r, label in zip(rows, labels)]
    counts = Counter(labels)
    return np.array([len(rows)/(len(counts)*counts[label]) for label in labels])


def decode_labels(prediction, target):
    if target == 'joint':
        return np.array([str(label).split('|')[1] for label in prediction])
    return np.asarray(prediction, dtype=str)


def fit_model(config, x, rows):
    target = np.array([base.floor_label(r['position']) for r in rows])
    if config['target'] == 'joint':
        target = np.array([r['type']+'|'+label for r, label in zip(rows, target)])
    weights = training_weights(rows, config['target'])
    if config['algorithm'] == 'logistic':
        estimator = LogisticRegression(C=config['c'], max_iter=4000)
    else:
        estimator = SVC(C=config['c'], kernel='rbf', gamma='scale', break_ties=True)
    model = Pipeline([('scaler', StandardScaler()), ('classifier', estimator)])
    model.fit(x, target, scaler__sample_weight=weights, classifier__sample_weight=weights)
    return {'pipeline': model, 'config': config, 'feature_version': FEATURE_VERSION,
            'feature_dimensions': x.shape[1], 'training_recordings': len(rows)}


def predict_vectors(payload, vectors):
    return decode_labels(payload['pipeline'].predict(vectors), payload['config']['target'])


def configs():
    return [dict(family=f, target=t, algorithm=a, c=c, augment=aug)
        for f in ['full', 'shape'] for t in ['floor', 'joint']
        for a in ['logistic', 'svm'] for c in [1., 10.] for aug in [False, True]]


def measure(y, prediction, rows):
    result = base.metrics(np.asarray(y), np.asarray(prediction), rows)
    above = np.asarray(y) == 'above'
    count = int(np.sum(above & (np.asarray(prediction) == 'below')))
    result['above_to_below_count'] = count
    result['above_to_below_rate'] = count / int(np.sum(above))
    result['selection_score'] = result['macro_f1'] - .10*result['above_to_below_rate']
    return result


def feature_cache(rows):
    paths = [base.wav_path(row) for row in rows]
    fingerprint = hash_inputs([Path(__file__), ROOT/'data/metadata.csv']+paths)
    path = OUT / 'enhanced-features.npz'
    ids = np.array([int(r['track_id']) for r in rows])
    if path.exists():
        with np.load(path, allow_pickle=False) as cache:
            if str(cache['fingerprint']) == fingerprint and np.array_equal(cache['ids'], ids):
                return {key: cache[key].copy() for key in ['full','shape']}
    values = {'full': [], 'shape': []}
    start = time.monotonic()
    for i, row in enumerate(rows):
        result = audio_features(paths[i])
        for key in values:
            values[key].append(result[key])
        if (i+1) % 100 == 0 or i+1 == len(rows):
            status = {'phase': 'features', 'done': i+1, 'total': len(rows), 'elapsed_s': round(time.monotonic()-start, 1)}
            base.write_json(OUT/'status.json', status)
            print(json.dumps(status), flush=True)
    arrays = {key: np.array(value, dtype=np.float32) for key, value in values.items()}
    np.savez_compressed(path, ids=ids, fingerprint=np.array(fingerprint), **arrays)
    return arrays


def fit_subset(config, indices, common, extra, common_x, extra_x):
    rows = [common[i] for i in indices]
    x = common_x[config['family']][indices]
    if config['augment']:
        rows += extra
        x = np.concatenate([x, extra_x[config['family']]])
    return fit_model(config, x, rows)


def select_config(common, extra, common_x, extra_x, folds, phase):
    trials = []
    for number, config in enumerate(configs()):
        actual, predicted, validation_rows = [], [], []
        for train_i, valid_i in folds:
            model = fit_subset(config, train_i, common, extra, common_x, extra_x)
            pred = predict_vectors(model, common_x[config['family']][valid_i])
            rows = [common[i] for i in valid_i]
            actual.extend(base.floor_label(r['position']) for r in rows)
            predicted.extend(pred)
            validation_rows.extend(rows)
        result = measure(actual, predicted, validation_rows)
        trial = {'config': config, 'macro_f1': result['macro_f1'], 'accuracy': result['accuracy'],
                 'above_to_below_rate': result['above_to_below_rate'], 'score': result['selection_score']}
        trials.append(trial)
        base.write_json(OUT/'status.json', {'phase': phase, 'candidate': number+1, 'total_candidates': len(configs())})
        print(json.dumps({'phase': phase, **trial}), flush=True)
    selected = max(trials, key=lambda row: row['score'])
    base.write_json(OUT/(phase+'-selection.json'), {'selected': selected, 'trials': trials})
    return selected['config']


def save_predictions(path, rows, actual, predictions):
    with path.open('w') as f:
        writer = csv.DictWriter(f, fieldnames=['track_id','type','position','actual','predicted'])
        writer.writeheader()
        for row, truth, prediction in zip(rows, actual, predictions):
            writer.writerow({k: row[k] for k in ['track_id','type','position']} |
                            {'actual': truth, 'predicted': prediction})


def run():
    started = datetime.now(timezone(timedelta(hours=9))).isoformat()
    with (ROOT/'data/metadata.csv').open() as f:
        rows = sorted(list(csv.DictReader(f)), key=lambda r: int(r['track_id']))
    parts = base.partition_records(rows)
    common = sorted(parts['development']+parts['test'], key=lambda r: int(r['track_id']))
    extra = parts['extra_upper']
    assert len(common) == 1800 and len(extra) == 1000
    values = feature_cache(common+extra)
    common_x = {k: v[:1800] for k, v in values.items()}
    extra_x = {k: v[1800:] for k, v in values.items()}
    with np.load(ROOT/'artifacts/features.npz', allow_pickle=False) as old:
        lookup = {int(track): i for i, track in enumerate(old['track_ids'])}
        baseline_x = old['features'][[lookup[int(r['track_id'])] for r in common]]
    truth = np.array([base.floor_label(r['position']) for r in common])
    baseline_prediction = np.empty(1800, dtype='U5')
    improved_prediction = np.empty(1800, dtype='U5')
    fold_results = []
    for fold in nested_folds(common):
        held = fold['distance']
        selected = select_config(common, extra, common_x, extra_x, fold['inner'], f'outer-{held}m')
        model = fit_subset(selected, fold['train'], common, extra, common_x, extra_x)
        destination = OUT/f'fold-{held}m.joblib'
        joblib.dump(model, destination, compress=3)
        restored = joblib.load(destination)
        pred = predict_vectors(restored, common_x[selected['family']][fold['test']])
        improved_prediction[fold['test']] = pred
        baseline = base.fit_candidate('logistic-c10', baseline_x[fold['train']], truth[fold['train']])
        old_pred = baseline.predict(baseline_x[fold['test']])
        baseline_prediction[fold['test']] = old_pred
        test_rows = [common[i] for i in fold['test']]
        result = {'distance_m': held, 'selected': selected,
            'baseline': measure(truth[fold['test']], old_pred, test_rows),
            'improved': measure(truth[fold['test']], pred, test_rows)}
        fold_results.append(result)
        base.write_json(OUT/f'fold-{held}m-metrics.json', result)
        save_predictions(OUT/f'fold-{held}m-predictions.csv', test_rows, truth[fold['test']], pred)
        print(json.dumps({'completed_fold': held, 'baseline_accuracy': result['baseline']['accuracy'],
            'improved_accuracy': result['improved']['accuracy'],
            'above_to_below': result['improved']['above_to_below_count']}), flush=True)
    overall = {'evaluation_status': 'reused-public-data nested spatial revalidation; no fresh independent test',
        'baseline': measure(truth, baseline_prediction, common),
        'improved': measure(truth, improved_prediction, common), 'folds': fold_results}
    base.write_json(OUT/'nested-comparison.json', overall)
    save_predictions(OUT/'nested-predictions.csv', common, truth, improved_prediction)
    save_predictions(OUT/'baseline-spatial-predictions.csv', common, truth, baseline_prediction)
    all_folds = [(f['train'], f['test']) for f in nested_folds(common)]
    selected = select_config(common, extra, common_x, extra_x, all_folds, 'full-fit')
    final_model = fit_subset(selected, np.arange(1800), common, extra, common_x, extra_x)
    destination = OUT/'floor-model-v2.joblib'
    joblib.dump(final_model, destination, compress=3)
    restored = joblib.load(destination)
    np.testing.assert_array_equal(predict_vectors(restored, common_x[selected['family']]),
                                  predict_vectors(final_model, common_x[selected['family']]))
    card = {'model_id': 'knok-floor-snu-v2', 'status': 'trained_research_candidate',
        'started_at_kst': started, 'completed_at_kst': datetime.now(timezone(timedelta(hours=9))).isoformat(),
        'model_sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
        'selected': selected, 'feature_version': FEATURE_VERSION,
        'feature_dimensions': final_model['feature_dimensions'],
        'training_recordings': final_model['training_recordings'], 'labels': base.LABELS_KO,
        'evaluation': overall,
        'important': ['The final candidate fits all1800 common recordings, plus1000 upper recordings only if augmentation was selected.',
            'Reported validation predictions come from spatially excluded outer models, not the final all-data model.',
            'All data were examined in v1. This is iterative reused-data revalidation, not fresh or cross-building performance.',
            'Known4sound types only; no vibration data, background/unknown rejection evaluation, calibrated probabilities or field validation.',
            'Still one building/phone. Spatial and nested separation do not remove shared building/device/session confounds.',
            'No Android/ESP32 integration performed.']}
    base.write_json(OUT/'model-card.json', card)
    base.write_json(OUT/'status.json', {'phase': 'complete',
        'nested_accuracy': overall['improved']['accuracy'], 'candidate': selected})
    print(json.dumps({'complete': True, 'baseline': overall['baseline'], 'improved': overall['improved'],
                      'final_candidate': selected}, ensure_ascii=False, indent=2), flush=True)


def predict(path, model_path):
    model = joblib.load(model_path)
    features = audio_features(path)
    vector = features[model['config']['family']][None, :]
    label = str(predict_vectors(model, vector)[0])
    return {'label': label, 'label_ko': base.LABELS_KO[label], 'model': str(model_path),
            'scope': 'Audio-only SNU research candidate; external building/device input is unvalidated'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predict', type=Path)
    parser.add_argument('--model', type=Path, default=OUT/'floor-model-v2.joblib')
    args = parser.parse_args()
    if args.predict:
        print(json.dumps(predict(args.predict, args.model), ensure_ascii=False, indent=2))
    else:
        run()
