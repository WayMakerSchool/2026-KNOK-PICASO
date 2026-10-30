"""NumPy-only research head. Preserve sklearn's float32 scaler operations."""
import numpy as np

LABELS = ['dragging_furniture', 'footstep', 'hammering', 'instant_impact',
          'normal_or_unknown', 'vacuum_cleaner']


def pack_head(fitted):
    source = fitted['source_classifier']
    classifier = source['classifier']
    if not np.array_equal(classifier.classes_, np.arange(6)):
        raise ValueError('Unexpected six-class order')
    return {
        'schema_version': np.array(1),
        'residual': np.array(bool(fitted['residual'])),
        'recorded_mean': fitted['recorded_scaler'].mean_,
        'recorded_scale': fitted['recorded_scaler'].scale_,
        'mapping_coef': fitted['mapping'].coef_,
        'mapping_intercept': fitted['mapping'].intercept_,
        'source_mean': source['scaler'].mean_,
        'source_scale': source['scaler'].scale_,
        'classifier_coef': classifier.coef_,
        'classifier_intercept': classifier.intercept_,
    }


def predict_head(head, embeddings):
    x = np.asarray(embeddings, dtype='float32')
    if x.ndim != 2 or x.shape[1] != 768 or not len(x) or not np.isfinite(x).all():
        raise ValueError('Finite nonempty N by 768 embeddings required')
    shapes = {'recorded_mean': (768,), 'recorded_scale': (768,),
              'mapping_coef': (768, 768), 'mapping_intercept': (768,),
              'source_mean': (768,), 'source_scale': (768,),
              'classifier_coef': (6, 768), 'classifier_intercept': (6,)}
    if int(head['schema_version']) != 1:
        raise ValueError('Unsupported head schema')
    for name, shape in shapes.items():
        value = np.asarray(head[name])
        if value.shape != shape or not np.isfinite(value).all():
            raise ValueError('Invalid head array: ' + name)
    if np.any(head['recorded_scale'] <= 0) or np.any(head['source_scale'] <= 0):
        raise ValueError('Positive scaler scales required')
    z = x.copy()
    z -= head['recorded_mean']
    z /= head['recorded_scale']
    # Fixed contraction avoids the isolated NumPy/Accelerate matmul FP-status
    # error reproduced on bounded, finite saved features. No warning suppression.
    restored = np.einsum('ij,kj->ik', z, head['mapping_coef'], optimize=False) + head['mapping_intercept']
    if bool(head['residual']):
        restored = x + restored
    restored = np.asarray(restored, dtype='float32').copy()
    restored -= head['source_mean']
    restored /= head['source_scale']
    logits = np.einsum('ij,kj->ik', restored, head['classifier_coef'], optimize=False) + head['classifier_intercept']
    logits -= logits.max(axis=1, keepdims=True)
    probability = np.exp(logits)
    return probability / probability.sum(axis=1, keepdims=True)


def decide(probabilities):
    p = np.asarray(probabilities)
    if (p.ndim != 2 or p.shape[1] != 6 or not len(p)
            or not np.isfinite(p).all() or np.any(p < 0) or np.any(p > 1)
            or not np.allclose(p.sum(axis=1), 1, atol=1e-5)):
        raise ValueError('Six finite normalized probabilities required')
    best = p.argmax(axis=1)
    return np.where(p.max(axis=1) >= .65, best, 4)
