# NetData 智能运维异常检测服务

基于 PyOD 和 PySAD 的实时异常检测微服务。

## 功能特性

- **批量检测**: 使用离线算法（Isolation Forest, LOF, KNN）
- **流式检测**: 使用在线算法（Half-Space Trees, xStream）
- **NetData 集成**: 直接从 NetData API 获取指标数据

## 快速开始

```bash
# 安装依赖
pip install -r requirements.txt

# 启动服务
uvicorn app.main:app --reload --port 8001

# 访问 API 文档
# http://localhost:8001/api/docs
```

## API 端点

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/health` | GET | 健康检查 |
| `/api/v1/detection/batch` | POST | 批量异常检测 |
| `/api/v1/detection/stream` | POST | 流式异常检测 |
| `/api/v1/detection/train` | POST | 训练检测器 |

## 检测器类型

| 类型 | 算法 | 适用场景 |
|------|------|----------|
| `isolation_forest` | 隔离森林 | 高维数据，快速检测 |
| `lof` | 局部异常因子 | 密度不均数据 |
| `knn` | K-近邻 | 低维数据 |
| `half_space_trees` | 半空间树 | 实时流式检测 |
| `xstream` | xStream | 高维流式数据 |
