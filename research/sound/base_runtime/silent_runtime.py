import math
def mps_memory_environment(high=.3,low=.2):
 if not(math.isfinite(high)and math.isfinite(low)and 0<low<=high<=1):raise ValueError('MPS low watermark must not exceed the bounded high watermark')
 return dict(PYTORCH_MPS_HIGH_WATERMARK_RATIO=str(high),PYTORCH_MPS_LOW_WATERMARK_RATIO=str(low),PYTORCH_ENABLE_MPS_FALLBACK='0')
