# ============================================================
# Pyod 核心异常检测算法
# ============================================================
# 注意：PyOD 是离线检测库，需要批量数据训练

# 隔离森林 - 适合高维数据
from pyod.models.iforest import IForest

# 局部异常因子 - 适合密度不均数据
from pyod.models.lof import LOF

# K-近邻异常检测
from pyod.models.knn import KNN

# ABOD: 角度异常检测
from pyod.models.abod import ABOD

# PCA-based 异常检测
from pyod.models.pca import PCA

# ============================================================
# PySAD 在线异常检测算法
# ============================================================
# 注意：PySAD 是流式检测库，逐条处理数据

# 半空间树 - 适合实时监控
from pysad.models import HalfSpaceTrees

# 流式孤立森林
from pysad.models import StreamingRHF

# xStream: 流式异常检测
from pysad.models import xStream

# ============================================================
# 导出可用算法
# ============================================================
__all__ = [
    # 离线检测器
    "IForest",
    "LOF",
    "KNN",
    "ABOD",
    "PCA",
    # 在线检测器
    "HalfSpaceTrees",
    "StreamingRHF",
    "xStream",
]
